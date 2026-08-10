package com.trivox.client.network

import com.trivox.client.config.XrayConfigBuilder
import com.trivox.client.core.CoreAdapter
import com.trivox.client.core.ConnectionRuntime
import com.trivox.client.data.AppSettings
import com.trivox.client.data.ConfigProfile
import com.trivox.client.data.ConnectionMode
import com.trivox.client.data.ConnectionState
import com.trivox.client.data.PingMethod
import com.trivox.client.data.PingResult
import com.trivox.client.util.Diagnostics
import org.json.JSONObject
import java.io.File
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Verified profile-latency engine.
 * Stored ranking uses XRAY_HTTP application-data proof only. ICMP remains
 * separate endpoint telemetry and is never substituted for tunnel delay.
 */
class PingManager(
    private val core: CoreAdapter? = null
) : AutoCloseable {
    private val xrayExecutor = Executors.newSingleThreadExecutor(
        namedFactory("trivox-verified-delay")
    )
    private val resolverExecutor = AtomicReference<ExecutorService>(
        newResolverExecutor()
    )
    private val resolverRotationAt = AtomicLong(0L)
    private val dnsCache = ConcurrentHashMap<String, CachedAddresses>()
    private val negativeDnsCache = ConcurrentHashMap<String, Long>()
    private val preferredXrayTarget = AtomicReference<String?>(null)
    private val preferredLocalProxyTarget = AtomicReference<String?>(null)

    /**
     * Ranking is real application-data proof only. An open TCP port is not a
     * usable-proxy proof and is never exposed as a latency method.
     */
    fun measure(
        profile: ConfigProfile,
        settings: AppSettings,
        workDir: File
    ): PingResult = realXray(
        profile = profile,
        settings = settings.copy(pingMethod = PingMethod.XRAY_HTTP),
        workDir = workDir,
        attempts = settings.testAttempts
    )

    fun measureSingle(
        profile: ConfigProfile,
        settings: AppSettings,
        workDir: File
    ): PingResult = realXray(
        profile = profile,
        settings = settings.copy(pingMethod = PingMethod.XRAY_HTTP),
        workDir = workDir,
        attempts = 1,
        timeoutSeconds = SINGLE_XRAY_TIMEOUT_SECONDS,
        allowSingleSample = true,
        maxTargetsPerSample = 2
    )

    @Synchronized
    fun realXray(
        profile: ConfigProfile,
        settings: AppSettings,
        workDir: File,
        attempts: Int = settings.testAttempts,
        timeoutSeconds: Int = 8,
        allowSingleSample: Boolean = false,
        maxTargetsPerSample: Int = MAX_HTTP_TARGETS_PER_SAMPLE
    ): PingResult {
        val timestamp = System.currentTimeMillis()
        val adapter = core ?: return basicFailure(
            PingMethod.XRAY_HTTP.name,
            timestamp,
            "core_unavailable"
        )
        if (Thread.currentThread().isInterrupted) {
            return cancelled(PingMethod.XRAY_HTTP.name, timestamp)
        }
        if (isLiveConnectionActive(adapter)) {
            return basicFailure(
                PingMethod.XRAY_HTTP.name,
                timestamp,
                "live_core_busy"
            )
        }

        val count = attempts.coerceIn(if (allowSingleSample) 1 else 2, 5)
        val timeoutMs = (timeoutSeconds.coerceIn(2, 15) * 1_000)
        val targetLimit = maxTargetsPerSample.coerceIn(1, MAX_HTTP_TARGETS_PER_SAMPLE)
        workDir.mkdirs()
        val safeId = profile.id.replace(Regex("[^A-Za-z0-9._-]"), "_").take(48)
        val configFile = File(workDir, "trivox-verified-$safeId-${System.nanoTime()}.json")
        val logFile = File(workDir, "trivox-verified-$safeId-${System.nanoTime()}.log")
        val mark = XrayProbeLogInspector.mark(logFile)
        var started = false

        return try {
            val probeSettings = isolatedProbeSettings(settings)
            val config = XrayConfigBuilder.build(
                profile = profile,
                settings = probeSettings,
                mode = ConnectionMode.PROXY,
                errorLogPath = logFile.absolutePath
            )
            configFile.writeText(config, Charsets.UTF_8)

            val validation = adapter.validate(config)
            if (!validation.success) {
                return basicFailure(
                    PingMethod.XRAY_HTTP.name,
                    timestamp,
                    classifyXrayFailure(validation.error).ifBlank {
                        "invalid_xray_config"
                    }
                )
            }

            val start = adapter.start(config, null)
            if (!start.success) {
                return basicFailure(
                    PingMethod.XRAY_HTTP.name,
                    timestamp,
                    XrayProbeLogInspector.classifySince(mark)
                        ?: classifyXrayFailure(start.error)
                )
            }
            started = true

            if (!waitCancellable(PROBE_START_GRACE_MS)) {
                return cancelled(PingMethod.XRAY_HTTP.name, timestamp)
            }

            val verified = verifiedLocalProxy(
                settings = probeSettings,
                attempts = count,
                timeoutMs = timeoutMs,
                requestedUrl = settings.testUrl,
                targetLimit = targetLimit
            )
            if (verified.success) {
                preferredXrayTarget.set(verified.resolvedIp)
                verified.copy(timestamp = timestamp)
            } else {
                val logCategory = XrayProbeLogInspector.classifySince(mark)
                verified.copy(
                    timestamp = timestamp,
                    latencyMs = null,
                    jitterMs = null,
                    successRatio = 0.0,
                    errorCategory = logCategory ?: verified.errorCategory
                )
            }
        } catch (throwable: Throwable) {
            failure(PingMethod.XRAY_HTTP.name, timestamp, throwable)
        } finally {
            if (started) {
                runCatching { adapter.stop() }
                    .onFailure {
                        Diagnostics.warning(
                            "Verified Real Delay cleanup failed: ${it.message}"
                        )
                    }
            }
            runCatching { configFile.delete() }
            runCatching { logFile.delete() }
        }
    }

    /** Compatibility overload used by diagnostics and older call sites. */
    fun realXray(
        configFile: File,
        url: String,
        timeoutSeconds: Int = 8
    ): PingResult {
        val timestamp = System.currentTimeMillis()
        val adapter = core ?: return basicFailure(
            PingMethod.XRAY_HTTP.name,
            timestamp,
            "core_unavailable"
        )
        if (isLiveConnectionActive(adapter)) {
            return basicFailure(
                PingMethod.XRAY_HTTP.name,
                timestamp,
                "live_core_busy"
            )
        }
        val config = runCatching { configFile.readText(Charsets.UTF_8) }
            .getOrElse { return failure(PingMethod.XRAY_HTTP.name, timestamp, it) }
        val port = runCatching {
            val inbounds = JSONObject(config).optJSONArray("inbounds")
            (0 until (inbounds?.length() ?: 0))
                .mapNotNull { inbounds?.optJSONObject(it) }
                .firstOrNull {
                    it.optString("protocol") in setOf("mixed", "socks", "http")
                }
                ?.optInt("port")
                ?.takeIf { it in 1..65535 }
        }.getOrNull() ?: AppSettings.DEFAULT_MIXED_PORT
        val settings = AppSettings(socksPort = port, httpPort = port, testUrl = url)
        var started = false
        return try {
            val result = adapter.start(config, null)
            if (!result.success) {
                return basicFailure(
                    PingMethod.XRAY_HTTP.name,
                    timestamp,
                    classifyXrayFailure(result.error)
                )
            }
            started = true
            waitCancellable(PROBE_START_GRACE_MS)
            verifiedLocalProxy(
                settings,
                attempts = 2,
                timeoutMs = timeoutSeconds.coerceIn(2, 15) * 1_000,
                requestedUrl = url,
                targetLimit = BATCH_XRAY_MAX_TARGETS
            ).copy(timestamp = timestamp)
        } finally {
            if (started) runCatching { adapter.stop() }
        }
    }

    fun httpViaLocalProxy(
        settings: AppSettings,
        attempts: Int = 3,
        timeoutMs: Int = 5_000,
        url: String = settings.testUrl
    ): PingResult = verifiedLocalProxy(
        settings = settings,
        attempts = attempts.coerceIn(2, 5),
        timeoutMs = timeoutMs.coerceIn(1_500, 15_000),
        requestedUrl = url,
        targetLimit = MAX_HTTP_TARGETS_PER_SAMPLE
    )

    private fun verifiedLocalProxy(
        settings: AppSettings,
        attempts: Int,
        timeoutMs: Int,
        requestedUrl: String,
        targetLimit: Int
    ): PingResult {
        val timestamp = System.currentTimeMillis()
        val count = attempts.coerceIn(1, 5)
        val targets = verifiedTargets(requestedUrl)
            .take(targetLimit.coerceIn(1, MAX_HTTP_TARGETS_PER_SAMPLE))
        val samples = mutableListOf<Long>()
        var lastFailure = "verified_https_failed"
        var acceptedTarget: String? = null

        for (sampleIndex in 0 until count) {
            if (Thread.currentThread().isInterrupted) {
                return cancelled(PingMethod.XRAY_HTTP.name, timestamp)
            }
            val target = targets[(sampleIndex % targets.size)]
            var accepted = false
            for (route in listOf(
                VerifiedHttpProbe.Route.HTTP_PROXY,
                VerifiedHttpProbe.Route.SOCKS_PROXY
            )) {
                val probe = VerifiedHttpProbe.probe(
                    settings = settings,
                    route = route,
                    target = target,
                    timeoutMs = timeoutMs,
                    nonce = timestamp + sampleIndex
                )
                if (probe.success && probe.latencyMs != null) {
                    samples += probe.latencyMs * NANOS_PER_MILLISECOND
                    acceptedTarget = probe.target
                    preferredLocalProxyTarget.set(probe.target)
                    accepted = true
                    break
                }
                lastFailure = probe.errorCategory ?: lastFailure
            }
            if (!accepted && samples.size + (count - sampleIndex - 1) < requiredSamples(count)) {
                break
            }
        }

        val summary = PingStatistics.summarize(samples, count)
        return PingResult(
            method = PingMethod.XRAY_HTTP.name,
            success = summary.success,
            latencyMs = summary.latencyMs,
            jitterMs = summary.jitterMs,
            successRatio = summary.successRatio,
            resolvedIp = acceptedTarget,
            timestamp = timestamp,
            errorCategory = if (summary.success) null else lastFailure
        )
    }

    private fun verifiedTargets(requestedUrl: String): List<VerifiedHttpProbe.Target> =
        buildList {
            preferredXrayTarget.get()
                ?.let(VerifiedHttpProbe::targetForUserUrl)
                ?.let(::add)
            preferredLocalProxyTarget.get()
                ?.let(VerifiedHttpProbe::targetForUserUrl)
                ?.let(::add)
            add(VerifiedHttpProbe.strongTraceTarget)
            VerifiedHttpProbe.targetForUserUrl(requestedUrl)?.let(::add)
            addAll(VerifiedHttpProbe.fallback204Targets)
        }.distinctBy { it.url }

    fun tlsHandshake(
        profile: ConfigProfile,
        serverName: String = profile.server,
        timeoutMs: Int = 5_000
    ): PingResult {
        val timestamp = System.currentTimeMillis()
        val start = System.nanoTime()
        return runCatching {
            val socket = SSLSocketFactory.getDefault().createSocket() as SSLSocket
            socket.use {
                it.soTimeout = timeoutMs
                it.connect(InetSocketAddress(profile.server, profile.port), timeoutMs)
                it.sslParameters = SSLParameters().apply {
                    serverNames = listOf(SNIHostName(serverName))
                }
                it.startHandshake()
                PingResult(
                    method = "TLS_HANDSHAKE",
                    success = true,
                    latencyMs = PingStatistics.nanosToDisplayMs(
                        System.nanoTime() - start
                    ),
                    jitterMs = null,
                    successRatio = 1.0,
                    resolvedIp = it.inetAddress?.hostAddress,
                    timestamp = timestamp
                )
            }
        }.getOrElse { failure("TLS_HANDSHAKE", timestamp, it) }
    }

    fun icmp(host: String, timeoutSeconds: Int = 2): PingResult =
        IcmpProbe.measure(host = host, attempts = 3, timeoutSeconds = timeoutSeconds)

    fun batch(
        profiles: List<ConfigProfile>,
        settings: AppSettings,
        workDir: File,
        callback: (ConfigProfile, PingResult) -> Unit
    ): List<Future<*>> {
        val adapter = core
        if (adapter == null || profiles.isEmpty()) return emptyList()
        return listOf(
            xrayExecutor.submit(Callable {
                synchronized(this@PingManager) {
                    BatchRealDelayRunner(adapter).run(
                        profiles = profiles,
                        settings = settings.copy(pingMethod = PingMethod.XRAY_HTTP),
                        workDir = workDir,
                        callback = callback,
                        fallback = { profile ->
                            realXray(
                                profile = profile,
                                settings = settings.copy(pingMethod = PingMethod.XRAY_HTTP),
                                workDir = workDir,
                                attempts = BATCH_XRAY_ATTEMPTS,
                                timeoutSeconds = BATCH_XRAY_TIMEOUT_SECONDS,
                                allowSingleSample = false,
                                maxTargetsPerSample = BATCH_XRAY_MAX_TARGETS
                            )
                        }
                    )
                }
            })
        )
    }

    fun cancel(tasks: Collection<Future<*>>) {
        tasks.forEach { it.cancel(true) }
    }

    override fun close() {
        xrayExecutor.shutdownNow()
        resolverExecutor.getAndSet(newResolverExecutor()).shutdownNow()
        resolverExecutor.get().shutdownNow()
        dnsCache.clear()
        negativeDnsCache.clear()
        preferredXrayTarget.set(null)
        preferredLocalProxyTarget.set(null)
    }

    private fun isLiveConnectionActive(adapter: CoreAdapter): Boolean {
        val state = ConnectionRuntime.current().state
        return state in setOf(
            ConnectionState.PREPARING,
            ConnectionState.CONNECTING,
            ConnectionState.CONNECTED,
            ConnectionState.RECONNECTING,
            ConnectionState.STOPPING
        ) || isCoreRunning(adapter)
    }

    private fun isCoreRunning(adapter: CoreAdapter): Boolean = runCatching {
        adapter.state().data
            ?.optJSONObject("data")
            ?.optBoolean("running") == true
    }.getOrDefault(false)

    private fun resolveAll(host: String, timeoutMs: Int): List<InetAddress> {
        val normalized = host.trim().lowercase(Locale.ROOT)
        if (isLiteralAddress(normalized)) {
            return listOf(InetAddress.getByName(normalized))
        }
        val now = System.currentTimeMillis()
        dnsCache[normalized]?.takeIf {
            now - it.storedAt < DNS_CACHE_TTL_MS
        }?.let { return it.addresses }
        negativeDnsCache[normalized]?.takeIf {
            now - it < NEGATIVE_DNS_CACHE_TTL_MS
        }?.let { throw UnknownHostException("Recent DNS failure for $host") }

        val executor = resolverExecutor.get()
        val task = try {
            executor.submit<List<InetAddress>> {
                InetAddress.getAllByName(host)
                    .asSequence()
                    .filterNot(::isPrivateOrLocal)
                    .distinctBy { it.hostAddress }
                    .sortedWith(compareBy<InetAddress> { if (it is Inet4Address) 0 else 1 })
                    .take(MAX_RESOLVED_ADDRESSES)
                    .toList()
            }
        } catch (rejected: RejectedExecutionException) {
            rotateResolverExecutor(executor)
            throw SocketTimeoutException("DNS resolver is saturated").apply {
                initCause(rejected)
            }
        }

        return try {
            task.get(
                timeoutMs.coerceAtMost(DNS_TIMEOUT_MS).toLong(),
                TimeUnit.MILLISECONDS
            ).takeIf(List<InetAddress>::isNotEmpty)?.also {
                dnsCache[normalized] = CachedAddresses(it, now)
                negativeDnsCache.remove(normalized)
            } ?: throw UnknownHostException("No safe address for $host")
        } catch (timeout: TimeoutException) {
            task.cancel(true)
            negativeDnsCache[normalized] = now
            rotateResolverExecutor(executor)
            throw SocketTimeoutException("DNS resolution timed out").apply {
                initCause(timeout)
            }
        } catch (throwable: Throwable) {
            negativeDnsCache[normalized] = now
            throw throwable
        }
    }

    private fun rotateResolverExecutor(stale: ExecutorService) {
        val now = System.currentTimeMillis()
        val previous = resolverRotationAt.get()
        if (
            now - previous < RESOLVER_ROTATION_COOLDOWN_MS ||
            !resolverRotationAt.compareAndSet(previous, now)
        ) return
        val replacement = newResolverExecutor()
        if (resolverExecutor.compareAndSet(stale, replacement)) {
            stale.shutdownNow()
        } else {
            replacement.shutdownNow()
        }
    }

    private fun newResolverExecutor(): ExecutorService =
        Executors.newFixedThreadPool(MAX_DNS_WORKERS, namedFactory("trivox-dns"))

    private fun isLiteralAddress(host: String): Boolean =
        ':' in host || (
            host.count { it == '.' } == 3 &&
                host.split('.').all { it.toIntOrNull() in 0..255 }
            )

    private fun isPrivateOrLocal(address: InetAddress): Boolean {
        if (
            address.isAnyLocalAddress || address.isLoopbackAddress ||
            address.isLinkLocalAddress || address.isSiteLocalAddress ||
            address.isMulticastAddress
        ) return true
        return when (address) {
            is Inet4Address -> {
                val b = address.address.map { it.toInt() and 0xff }
                b[0] == 0 || b[0] == 10 || b[0] == 127 ||
                    (b[0] == 100 && b[1] in 64..127) ||
                    (b[0] == 169 && b[1] == 254) ||
                    (b[0] == 172 && b[1] in 16..31) ||
                    (b[0] == 192 && b[1] == 168) || b[0] >= 224
            }
            is Inet6Address -> {
                val b = address.address
                address.isIPv4CompatibleAddress ||
                    (b.isNotEmpty() && (b[0].toInt() and 0xfe) == 0xfc)
            }
            else -> true
        }
    }

    private fun isolatedProbeSettings(settings: AppSettings): AppSettings {
        val port = ServerSocket(
            0, 1, InetAddress.getByName("127.0.0.1")
        ).use { it.localPort }
        return settings.copy(socksPort = port, httpPort = port).normalize()
    }

    private fun socksConnectOnce(
        proxyPort: Int,
        target: InetAddress,
        targetPort: Int,
        timeoutMs: Int
    ): Long {
        Socket().use { socket ->
            socket.tcpNoDelay = true
            socket.soTimeout = timeoutMs
            socket.connect(
                InetSocketAddress("127.0.0.1", proxyPort),
                timeoutMs.coerceAtMost(1_500)
            )
            val input = socket.getInputStream()
            val output = socket.getOutputStream()
            output.write(byteArrayOf(0x05, 0x01, 0x00))
            output.flush()
            val greeting = readFully(input, 2)
            if (
                (greeting[0].toInt() and 0xff) != 0x05 ||
                (greeting[1].toInt() and 0xff) != 0x00
            ) throw ConnectException("SOCKS no-auth negotiation failed")

            val addr = target.address
            val request = ByteArray(4 + addr.size + 2)
            request[0] = 0x05.toByte()
            request[1] = 0x01.toByte()
            request[2] = 0x00.toByte()
            request[3] = (if (addr.size == 4) 0x01 else 0x04).toByte()
            addr.copyInto(request, 4)
            val pi = 4 + addr.size
            request[pi] = ((targetPort ushr 8) and 0xff).toByte()
            request[pi + 1] = (targetPort and 0xff).toByte()

            val started = System.nanoTime()
            output.write(request)
            output.flush()
            val head = readFully(input, 4)
            if ((head[0].toInt() and 0xff) != 0x05)
                throw ConnectException("Invalid SOCKS response")
            val reply = head[1].toInt() and 0xff
            if (reply != 0) throw ConnectException("SOCKS CONNECT reply $reply")
            when (head[3].toInt() and 0xff) {
                0x01 -> readFully(input, 4)
                0x04 -> readFully(input, 16)
                0x03 -> {
                    val n = readFully(input, 1)[0].toInt() and 0xff
                    readFully(input, n)
                }
                else -> throw ConnectException("Invalid SOCKS address type")
            }
            readFully(input, 2)
            return (System.nanoTime() - started).coerceAtLeast(1L)
        }
    }

    private fun readFully(input: java.io.InputStream, count: Int): ByteArray {
        val data = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val n = input.read(data, offset, count - offset)
            if (n < 0) throw java.io.EOFException("Short SOCKS response")
            offset += n
        }
        return data
    }

    private fun waitCancellable(delayMs: Int): Boolean {
        var remaining = delayMs
        while (remaining > 0) {
            if (Thread.currentThread().isInterrupted) return false
            val slice = remaining.coerceAtMost(50)
            try {
                Thread.sleep(slice.toLong())
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
            remaining -= slice
        }
        return !Thread.currentThread().isInterrupted
    }

    private fun requiredSamples(attempts: Int): Int =
        if (attempts <= 1) 1 else (attempts * 2 + 2) / 3

    private fun acceptsHttpStatus(url: String, statusCode: Int): Boolean {
        val path = runCatching { URI(url).path.orEmpty().lowercase(Locale.ROOT) }
            .getOrDefault("")
        return if ("generate_204" in path || "gen_204" in path) {
            statusCode == 204
        } else {
            statusCode in 200..299
        }
    }

    private fun cacheBustedUrl(url: String, nonce: Long): String {
        val separator = if ('?' in url) '&' else '?'
        return "$url${separator}trivox_ping=$nonce"
    }

    private fun classifyXrayFailure(message: String): String {
        val normalized = message.lowercase(Locale.ROOT)
        return when {
            normalized.isBlank() -> "xray_test_failure"
            "received real certificate" in normalized -> "reality_mismatch"
            "websocket" in normalized && "failed to dial" in normalized ->
                "websocket_handshake"
            "no such host" in normalized || "lookup " in normalized -> "dns_failure"
            "deadline exceeded" in normalized || "timeout" in normalized -> "timeout"
            "connection refused" in normalized -> "connection_refused"
            "illegal base64" in normalized -> "invalid_credentials"
            "failed to build" in normalized || "failed to unmarshal" in normalized ->
                "invalid_xray_config"
            else -> "xray_test_failure"
        }
    }

    private fun failure(
        method: String,
        timestamp: Long,
        throwable: Throwable
    ): PingResult = basicFailure(method, timestamp, classify(throwable))

    private fun basicFailure(
        method: String,
        timestamp: Long,
        category: String
    ) = PingResult(
        method = method,
        success = false,
        latencyMs = null,
        jitterMs = null,
        successRatio = 0.0,
        resolvedIp = null,
        timestamp = timestamp,
        errorCategory = category
    )

    private fun classify(throwable: Throwable): String {
        val root = generateSequence(throwable) { it.cause }.last()
        return when (root) {
            is InterruptedException -> "cancelled"
            is TimeoutException, is SocketTimeoutException -> "timeout"
            is UnknownHostException -> "dns_failure"
            is ConnectException -> "connection_refused"
            is SecurityException -> "operation_blocked"
            else -> root.javaClass.simpleName.ifBlank { "ping_failure" }
        }
    }

    private fun cancelled(method: String, timestamp: Long) =
        basicFailure(method, timestamp, "cancelled")

    private fun namedFactory(prefix: String): ThreadFactory {
        val sequence = AtomicInteger(0)
        return ThreadFactory { runnable ->
            Thread(runnable, "$prefix-${sequence.incrementAndGet()}").apply {
                isDaemon = true
            }
        }
    }

    private data class CachedAddresses(
        val addresses: List<InetAddress>,
        val storedAt: Long
    )

    companion object {
        private const val MAX_TCP_WORKERS = 4
        private const val MAX_DNS_WORKERS = 2
        private const val MAX_RESOLVED_ADDRESSES = 4
        private const val MIN_TIMEOUT_MS = 500
        private const val MAX_TIMEOUT_MS = 15_000
        private const val DEFAULT_TCP_TIMEOUT_MS = 4_000
        private const val SINGLE_TCP_TIMEOUT_MS = 2_500
        private const val SINGLE_WIREGUARD_TIMEOUT_MS = 3_500
        private const val SINGLE_XRAY_TIMEOUT_SECONDS = 4
        private const val ADDRESS_PROBE_TIMEOUT_MS = 1_500
        private const val LOW_LATENCY_RECHECK_THRESHOLD_MS = 10L
        private const val LOW_LATENCY_RECHECK_ATTEMPTS = 3
        private const val DNS_TIMEOUT_MS = 3_000
        private const val DNS_CACHE_TTL_MS = 60_000L
        private const val NEGATIVE_DNS_CACHE_TTL_MS = 15_000L
        private const val RESOLVER_ROTATION_COOLDOWN_MS = 5_000L
        private const val BATCH_XRAY_ATTEMPTS = 2
        private const val BATCH_XRAY_TIMEOUT_SECONDS = 5
        private const val BATCH_XRAY_MAX_TARGETS = 2
        private const val MAX_HTTP_TARGETS_PER_SAMPLE = 4
        private const val PROBE_START_GRACE_MS = 90
        private const val WIREGUARD_PROBE_START_GRACE_MS = 220
        private const val NANOS_PER_MILLISECOND = 1_000_000L
        /* Retained as a documented compatibility pool for diagnostics/audits. */
        private val FALLBACK_CONNECTIVITY_URLS = listOf(
            "https://1.1.1.1/cdn-cgi/trace",
            "https://cp.cloudflare.com/generate_204",
            "https://connectivitycheck.gstatic.com/generate_204",
            "https://www.gstatic.com/generate_204"
        )
    }
}
