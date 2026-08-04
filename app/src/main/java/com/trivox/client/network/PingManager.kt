package com.trivox.client.network

import com.trivox.client.config.XrayConfigBuilder
import com.trivox.client.core.CoreAdapter
import com.trivox.client.data.AppSettings
import com.trivox.client.data.ConfigProfile
import com.trivox.client.data.ConnectionMode
import com.trivox.client.data.PingMethod
import com.trivox.client.data.PingResult
import java.io.File
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Future
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.math.ceil

class PingManager(
    private val core: CoreAdapter? = null
) : AutoCloseable {
    private val tcpExecutor =
        Executors.newFixedThreadPool(
            MAX_TCP_WORKERS,
            namedFactory("trivox-tcp-ping")
        )
    private val xrayExecutor =
        Executors.newSingleThreadExecutor(
            namedFactory("trivox-xray-ping")
        )
    private val resolverExecutor =
        AtomicReference<ExecutorService>(
            newResolverExecutor()
        )
    private val resolverRotationAt =
        AtomicLong(0L)
    private val dnsCache =
        ConcurrentHashMap<String, CachedAddresses>()
    private val negativeDnsCache =
        ConcurrentHashMap<String, Long>()
    private val preferredXrayTarget =
        AtomicReference<String?>(null)
    private val preferredLocalProxyTarget =
        AtomicReference<String?>(null)

    fun measure(
        profile: ConfigProfile,
        settings: AppSettings,
        workDir: File
    ): PingResult {
        // TCP reachability of a nearby hostname does not prove a WireGuard
        // handshake or usable tunnel. WireGuard profiles always use the full
        // Xray route so an "Alive" result is end-to-end.
        if (profile.protocol.equals("wireguard", ignoreCase = true)) {
            return realXray(
                profile = profile,
                settings = settings,
                workDir = workDir,
                attempts = settings.testAttempts
            )
        }

        return when (settings.pingMethod) {
            PingMethod.TCP_CONNECT ->
                tcp(
                    profile = profile,
                    attempts = settings.testAttempts,
                    timeoutMs = DEFAULT_TCP_TIMEOUT_MS
                )

            PingMethod.XRAY_HTTP ->
                realXray(
                    profile = profile,
                    settings = settings,
                    workDir = workDir,
                    attempts = settings.testAttempts
                )
        }
    }

    fun tcp(
        profile: ConfigProfile,
        attempts: Int = 3,
        timeoutMs: Int = DEFAULT_TCP_TIMEOUT_MS
    ): PingResult {
        val timestamp =
            System.currentTimeMillis()
        val count =
            attempts.coerceIn(2, 5)
        val boundedTimeout =
            timeoutMs.coerceIn(
                MIN_TIMEOUT_MS,
                MAX_TIMEOUT_MS
            )

        val targetHost =
            profile.probeServer
                .trim()
                .ifBlank {
                    profile.server
                }
        val targetPort =
            profile.probePort
                .takeIf {
                    it in 1..65535
                }
                ?: profile.port

        if (
            targetHost.isBlank() ||
            targetPort !in 1..65535
        ) {
            return failure(
                method = PingMethod.TCP_CONNECT.name,
                timestamp = timestamp,
                throwable = IllegalArgumentException(
                    "TCP probe endpoint is unavailable"
                )
            )
        }

        val addresses =
            try {
                resolveAll(
                    targetHost,
                    boundedTimeout
                )
            } catch (
                throwable: Throwable
            ) {
                return failure(
                    method = PingMethod.TCP_CONNECT.name,
                    timestamp = timestamp,
                    throwable = throwable
                )
            }

        var selectedAddress: InetAddress? = null
        var lastFailure: Throwable? = null

        for (address in addresses) {
            if (Thread.currentThread().isInterrupted) {
                return cancelled(
                    PingMethod.TCP_CONNECT.name,
                    timestamp
                )
            }

            try {
                connectOnce(
                    address = address,
                    port = targetPort,
                    timeoutMs =
                        boundedTimeout.coerceAtMost(
                            ADDRESS_PROBE_TIMEOUT_MS
                        )
                )
                selectedAddress = address
                break
            } catch (throwable: Throwable) {
                lastFailure = throwable
            }
        }

        val address =
            selectedAddress
                ?: return failure(
                    method = PingMethod.TCP_CONNECT.name,
                    timestamp = timestamp,
                    throwable =
                        lastFailure
                            ?: ConnectException(
                                "No resolved address accepted the TCP connection"
                            )
                )

        val samples = mutableListOf<Long>()

        fun collect(sampleCount: Int) {
            repeat(sampleCount) {
                if (Thread.currentThread().isInterrupted) {
                    return
                }

                try {
                    samples +=
                        connectOnce(
                            address = address,
                            port = targetPort,
                            timeoutMs = boundedTimeout
                        )
                } catch (throwable: Throwable) {
                    lastFailure = throwable
                }
            }
        }

        collect(count)
        var totalAttempts = count
        var summary =
            PingStatistics.summarize(
                samples,
                totalAttempts
            )

        if (
            summary.success &&
            (summary.latencyMs ?: Long.MAX_VALUE) <
                LOW_LATENCY_RECHECK_THRESHOLD_MS
        ) {
            collect(LOW_LATENCY_RECHECK_ATTEMPTS)
            totalAttempts +=
                LOW_LATENCY_RECHECK_ATTEMPTS
            summary =
                PingStatistics.summarize(
                    samples,
                    totalAttempts
                )
        }

        return PingResult(
            method = PingMethod.TCP_CONNECT.name,
            success = summary.success,
            latencyMs = summary.latencyMs,
            jitterMs = summary.jitterMs,
            successRatio = summary.successRatio,
            resolvedIp = address.hostAddress,
            timestamp = timestamp,
            errorCategory =
                if (summary.success) {
                    null
                } else {
                    classify(
                        lastFailure
                            ?: SocketTimeoutException(
                                "Insufficient verified TCP samples"
                            )
                    )
                }
        )
    }

    fun realXray(
        profile: ConfigProfile,
        settings: AppSettings,
        workDir: File,
        attempts: Int =
            settings.testAttempts,
        timeoutSeconds: Int = 8,
        allowSingleSample: Boolean = false,
        maxTargetsPerSample: Int =
            MAX_HTTP_TARGETS_PER_SAMPLE
    ): PingResult {
        val timestamp =
            System.currentTimeMillis()
        val adapter =
            core ?: return PingResult(
                method =
                    PingMethod
                        .XRAY_HTTP
                        .name,
                success = false,
                latencyMs = null,
                jitterMs = null,
                successRatio = 0.0,
                resolvedIp = null,
                timestamp = timestamp,
                errorCategory =
                    "core_unavailable"
            )
        val targets =
            connectivityTargets(
                settings.testUrl,
                preferredXrayTarget.get()
            )

        if (targets.isEmpty()) {
            return PingResult(
                method =
                    PingMethod
                        .XRAY_HTTP
                        .name,
                success = false,
                latencyMs = null,
                jitterMs = null,
                successRatio = 0.0,
                resolvedIp = null,
                timestamp = timestamp,
                errorCategory =
                    "invalid_test_url"
            )
        }

        val minimumSamples =
            if (allowSingleSample) 1 else 2
        val count =
            attempts.coerceIn(
                minimumSamples,
                5
            )
        val targetLimit =
            maxTargetsPerSample.coerceIn(
                1,
                MAX_HTTP_TARGETS_PER_SAMPLE
            )
        val boundedTimeout =
            timeoutSeconds.coerceIn(3, 15)
        val configFile =
            File(
                workDir,
                "trivox-ping-" +
                    profile.id
                        .replace(
                            Regex(
                                "[^A-Za-z0-9._-]"
                            ),
                            "_"
                        )
                        .take(48) +
                    "-" +
                    System.nanoTime() +
                    ".json"
            )
        val samples =
            mutableListOf<Long>()
        var lastError =
            "xray_test_failure"
        var activeTarget:
            String? = null

        return try {
            workDir.mkdirs()
            configFile.writeText(
                XrayConfigBuilder.build(
                    profile = profile,
                    settings = settings,
                    mode = ConnectionMode.PROXY
                ),
                Charsets.UTF_8
            )

            fun collect(sampleCount: Int) {
                repeat(sampleCount) {
                        sampleIndex ->
                    if (
                        Thread.currentThread()
                            .isInterrupted
                    ) {
                        return
                    }

                    val orderedTargets =
                        connectivityTargets(
                            settings.testUrl,
                            activeTarget
                                ?: preferredXrayTarget
                                    .get()
                        )
                    var accepted = false

                    orderedTargets
                        .take(targetLimit)
                        .forEachIndexed {
                                targetIndex,
                                target ->
                            if (
                                accepted ||
                                Thread.currentThread()
                                    .isInterrupted
                            ) {
                                return@forEachIndexed
                            }

                            val targetTimeout =
                                if (targetIndex == 0) {
                                    boundedTimeout
                                } else {
                                    boundedTimeout
                                        .coerceAtMost(
                                            FALLBACK_TARGET_TIMEOUT_SECONDS
                                        )
                                }
                            val result =
                                adapter.realDelay(
                                    configFile.absolutePath,
                                    targetTimeout,
                                    cacheBustedUrl(
                                        target,
                                        timestamp +
                                            samples.size +
                                            sampleIndex +
                                            targetIndex
                                    )
                                )
                            val delay =
                                parseXrayDelay(
                                    result.data,
                                    targetTimeout
                                )

                            if (
                                result.success &&
                                delay != null
                            ) {
                                samples +=
                                    delay *
                                        NANOS_PER_MILLISECOND
                                activeTarget = target
                                preferredXrayTarget
                                    .set(target)
                                accepted = true
                            } else {
                                lastError =
                                    classifyXrayFailure(
                                        result.error
                                    )

                                if (
                                    preferredXrayTarget
                                        .get() ==
                                    target
                                ) {
                                    preferredXrayTarget
                                        .compareAndSet(
                                            target,
                                            null
                                        )
                                }
                            }
                        }
                }
            }

            collect(count)
            var totalAttempts = count
            var summary =
                PingStatistics.summarize(
                    samples,
                    totalAttempts
                )

            PingResult(
                method =
                    PingMethod
                        .XRAY_HTTP
                        .name,
                success =
                    summary.success,
                latencyMs =
                    summary.latencyMs,
                jitterMs =
                    summary.jitterMs,
                successRatio =
                    summary.successRatio,
                resolvedIp = null,
                timestamp = timestamp,
                errorCategory =
                    if (summary.success) {
                        null
                    } else {
                        lastError
                    }
            )
        } catch (
            throwable: Throwable
        ) {
            failure(
                method =
                    PingMethod
                        .XRAY_HTTP
                        .name,
                timestamp = timestamp,
                throwable = throwable
            )
        } finally {
            runCatching {
                configFile.delete()
            }
        }
    }

    fun realXray(
        configFile: File,
        url: String,
        timeoutSeconds: Int = 8
    ): PingResult {
        val timestamp =
            System.currentTimeMillis()
        val result =
            core?.realDelay(
                configFile.absolutePath,
                timeoutSeconds,
                url
            )
                ?: return PingResult(
                    method = "XRAY_HTTP",
                    success = false,
                    latencyMs = null,
                    jitterMs = null,
                    successRatio = 0.0,
                    resolvedIp = null,
                    timestamp = timestamp,
                    errorCategory =
                        "core_unavailable"
                )
        val delay =
            parseXrayDelay(
                result.data,
                timeoutSeconds
            )
        val success =
            result.success &&
                delay != null

        return PingResult(
            method = "XRAY_HTTP",
            success = success,
            latencyMs =
                delay.takeIf {
                    success
                },
            jitterMs = null,
            successRatio =
                if (success) 1.0 else 0.0,
            resolvedIp = null,
            timestamp = timestamp,
            errorCategory =
                if (success) {
                    null
                } else {
                    result.error
                        .ifBlank {
                            "xray_test_failure"
                        }
                }
        )
    }

    fun httpViaLocalProxy(
        settings: AppSettings,
        attempts: Int = 3,
        timeoutMs: Int = 5_000,
        url: String = settings.testUrl
    ): PingResult {
        val timestamp =
            System.currentTimeMillis()
        val count =
            attempts.coerceIn(2, 5)
        val boundedTimeout =
            timeoutMs.coerceIn(
                1_500,
                15_000
            )
        val baseTargets =
            connectivityTargets(
                url,
                preferredLocalProxyTarget
                    .get()
            )

        if (baseTargets.isEmpty()) {
            return PingResult(
                method =
                    PingMethod
                        .XRAY_HTTP
                        .name,
                success = false,
                latencyMs = null,
                jitterMs = null,
                successRatio = 0.0,
                resolvedIp = "127.0.0.1",
                timestamp = timestamp,
                errorCategory =
                    "invalid_test_url"
            )
        }

        var samples =
            mutableListOf<Long>()
        var lastFailure:
            Throwable? = null
        var activeProxyType =
            Proxy.Type.HTTP
        var activeTarget:
            String? = null

        fun runSamples(
            proxyType: Proxy.Type,
            sampleCount: Int
        ) {
            activeProxyType = proxyType

            repeat(sampleCount) {
                    sampleIndex ->
                if (
                    Thread.currentThread()
                        .isInterrupted
                ) {
                    return
                }

                val targets =
                    connectivityTargets(
                        url,
                        activeTarget
                            ?: preferredLocalProxyTarget
                                .get()
                    )
                var accepted = false

                targets
                    .take(
                        MAX_HTTP_TARGETS_PER_SAMPLE
                    )
                    .forEachIndexed {
                            targetIndex,
                            target ->
                        if (
                            accepted ||
                            Thread.currentThread()
                                .isInterrupted
                        ) {
                            return@forEachIndexed
                        }

                        val result =
                            probeViaLocalProxy(
                                settings = settings,
                                proxyType =
                                    proxyType,
                                url = target,
                                timeoutMs =
                                    if (
                                        targetIndex == 0
                                    ) {
                                        boundedTimeout
                                    } else {
                                        boundedTimeout
                                            .coerceAtMost(
                                                FALLBACK_TARGET_TIMEOUT_MS
                                            )
                                    },
                                nonce =
                                    timestamp +
                                        sampleIndex +
                                        samples.size +
                                        targetIndex
                            )

                        if (
                            result.elapsedNanos !=
                            null
                        ) {
                            samples +=
                                result.elapsedNanos
                            activeTarget = target
                            preferredLocalProxyTarget
                                .set(target)
                            accepted = true
                        } else {
                            lastFailure =
                                result.failure

                            if (
                                preferredLocalProxyTarget
                                    .get() ==
                                target
                            ) {
                                preferredLocalProxyTarget
                                    .compareAndSet(
                                        target,
                                        null
                                    )
                            }
                        }
                    }
            }
        }

        runSamples(
            Proxy.Type.HTTP,
            count
        )
        var totalAttempts = count
        var summary =
            PingStatistics.summarize(
                samples,
                totalAttempts
            )

        if (!summary.success) {
            samples = mutableListOf()
            lastFailure = null
            activeTarget = null

            runSamples(
                Proxy.Type.SOCKS,
                count
            )
            totalAttempts = count
            summary =
                PingStatistics.summarize(
                    samples,
                    totalAttempts
                )
        }

        if (
            summary.success &&
            (summary.latencyMs ?: Long.MAX_VALUE) <
            LOW_LATENCY_RECHECK_THRESHOLD_MS
        ) {
            runSamples(
                activeProxyType,
                LOW_LATENCY_RECHECK_ATTEMPTS
            )
            totalAttempts +=
                LOW_LATENCY_RECHECK_ATTEMPTS
            summary =
                PingStatistics.summarize(
                    samples,
                    totalAttempts
                )
        }

        return PingResult(
            method =
                PingMethod
                    .XRAY_HTTP
                    .name,
            success = summary.success,
            latencyMs = summary.latencyMs,
            jitterMs = summary.jitterMs,
            successRatio =
                summary.successRatio,
            resolvedIp = "127.0.0.1",
            timestamp = timestamp,
            errorCategory =
                if (summary.success) {
                    null
                } else {
                    classify(
                        lastFailure
                            ?: SocketTimeoutException(
                                "Local mixed proxy did not return a verified response"
                            )
                    )
                }
        )
    }

    private fun probeViaLocalProxy(
        settings: AppSettings,
        proxyType: Proxy.Type,
        url: String,
        timeoutMs: Int,
        nonce: Long
    ): HttpProbe {
        var connection:
            HttpURLConnection? = null

        return try {
            val uri =
                URI(url)
            val proxy =
                Proxy(
                    proxyType,
                    InetSocketAddress(
                        "127.0.0.1",
                        settings.socksPort
                    )
                )
            connection =
                URI(
                    cacheBustedUrl(
                        url,
                        nonce
                    )
                ).toURL()
                    .openConnection(
                        proxy
                    ) as
                    HttpURLConnection
            connection.connectTimeout =
                timeoutMs
            connection.readTimeout =
                timeoutMs
            connection.instanceFollowRedirects =
                false
            connection.useCaches = false
            connection.requestMethod = "GET"
            connection.setRequestProperty(
                "Connection",
                "close"
            )
            connection.setRequestProperty(
                "Cache-Control",
                "no-cache, no-store, max-age=0"
            )
            connection.setRequestProperty(
                "Pragma",
                "no-cache"
            )
            connection.setRequestProperty(
                "Accept",
                "*/*"
            )
            connection.setRequestProperty(
                "User-Agent",
                "Trivox-AlivePing/4"
            )

            val startNanos =
                System.nanoTime()
            val code =
                connection.responseCode
            val elapsed =
                (
                    System.nanoTime() -
                        startNanos
                    ).coerceAtLeast(1L)

            if (
                isExpectedHttpResponse(
                    uri,
                    code
                )
            ) {
                HttpProbe(
                    elapsedNanos = elapsed
                )
            } else {
                HttpProbe(
                    failure =
                        IllegalStateException(
                            "Unexpected HTTP status $code"
                        )
                )
            }
        } catch (
            throwable: Throwable
        ) {
            HttpProbe(
                failure = throwable
            )
        } finally {
            runCatching {
                connection?.inputStream
                    ?.close()
            }
            runCatching {
                connection?.errorStream
                    ?.close()
            }
            connection?.disconnect()
        }
    }

    private fun connectivityTargets(
        primary: String,
        preferred: String?
    ): List<String> =
        buildList {
            preferred
                ?.takeIf(::isValidHttpUrl)
                ?.let(::add)
            primary
                .takeIf(::isValidHttpUrl)
                ?.let(::add)
            FALLBACK_CONNECTIVITY_URLS
                .forEach {
                    if (isValidHttpUrl(it)) {
                        add(it)
                    }
                }
        }.distinct()

    private fun isValidHttpUrl(
        value: String
    ): Boolean =
        runCatching {
            val uri =
                URI(value)

            uri.host
                .orEmpty()
                .isNotBlank() &&
                uri.scheme
                    ?.lowercase() in
                    setOf(
                        "http",
                        "https"
                    )
        }.getOrDefault(false)

    private fun cacheBustedUrl(
        url: String,
        nonce: Long
    ): String {
        val separator =
            if ('?' in url) '&' else '?'

        return "$url${separator}trivox_ping=$nonce"
    }

    private fun isExpectedHttpResponse(
        uri: URI,
        statusCode: Int
    ): Boolean {
        val generate204 =
            uri.path.equals(
                "/gen_204",
                ignoreCase = true
            ) ||
                uri.path.equals(
                    "/generate_204",
                    ignoreCase = true
                )

        return if (generate204) {
            statusCode == 204
        } else {
            statusCode in 200..399
        }
    }

    fun tlsHandshake(
        profile: ConfigProfile,
        serverName: String =
            profile.server,
        timeoutMs: Int = 5_000
    ): PingResult {
        val timestamp =
            System.currentTimeMillis()
        val start =
            System.nanoTime()

        return runCatching {
            val socket =
                SSLSocketFactory
                    .getDefault()
                    .createSocket()
                    as SSLSocket

            socket.use {
                it.soTimeout =
                    timeoutMs
                it.connect(
                    InetSocketAddress(
                        profile.server,
                        profile.port
                    ),
                    timeoutMs
                )
                it.sslParameters =
                    SSLParameters().apply {
                        serverNames =
                            listOf(
                                SNIHostName(
                                    serverName
                                )
                            )
                    }
                it.startHandshake()

                PingResult(
                    method =
                        "TLS_HANDSHAKE",
                    success = true,
                    latencyMs =
                        PingStatistics
                            .nanosToDisplayMs(
                                System.nanoTime() -
                                    start
                            ),
                    jitterMs = null,
                    successRatio = 1.0,
                    resolvedIp =
                        it.inetAddress
                            ?.hostAddress,
                    timestamp = timestamp
                )
            }
        }.getOrElse {
            failure(
                method =
                    "TLS_HANDSHAKE",
                timestamp = timestamp,
                throwable = it
            )
        }
    }

    fun icmp(
        host: String,
        timeoutSeconds: Int = 4
    ): PingResult {
        val timestamp =
            System.currentTimeMillis()
        val boundedTimeout =
            timeoutSeconds.coerceIn(1, 10)
        val process =
            try {
                ProcessBuilder(
                    listOf(
                        "ping",
                        "-c",
                        "1",
                        "-W",
                        boundedTimeout
                            .toString(),
                        host
                    )
                )
                    .redirectErrorStream(
                        true
                    )
                    .start()
            } catch (
                throwable: Throwable
            ) {
                return failure(
                    method = "ICMP",
                    timestamp = timestamp,
                    throwable = throwable
                )
            }

        return try {
            val completed =
                process.waitFor(
                    boundedTimeout
                        .toLong() +
                        2L,
                    TimeUnit.SECONDS
                )

            if (!completed) {
                process.destroyForcibly()
                process.waitFor(
                    500,
                    TimeUnit.MILLISECONDS
                )
            }

            val output =
                process.inputStream
                    .bufferedReader()
                    .use {
                        it.readText()
                            .take(8_192)
                    }
            val succeeded =
                completed &&
                    process.exitValue() == 0
            val latency =
                Regex(
                    "time[=<]\\s*" +
                        "([0-9.]+)\\s*ms",
                    RegexOption.IGNORE_CASE
                )
                    .find(output)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toDoubleOrNull()
                    ?.let {
                        ceil(it)
                            .toLong()
                            .coerceAtLeast(1L)
                    }

            PingResult(
                method = "ICMP",
                success =
                    succeeded &&
                        latency != null,
                latencyMs =
                    latency.takeIf {
                        succeeded
                    },
                jitterMs = null,
                successRatio =
                    if (
                        succeeded &&
                        latency != null
                    ) {
                        1.0
                    } else {
                        0.0
                    },
                resolvedIp = null,
                timestamp = timestamp,
                errorCategory =
                    if (
                        succeeded &&
                        latency != null
                    ) {
                        null
                    } else {
                        "icmp_unavailable_or_blocked"
                    }
            )
        } catch (
            throwable: Throwable
        ) {
            failure(
                method = "ICMP",
                timestamp = timestamp,
                throwable = throwable
            )
        } finally {
            if (process.isAlive) {
                process.destroyForcibly()
            }
        }
    }

    fun batchTcp(
        profiles: List<ConfigProfile>,
        attempts: Int,
        callback:
            (
                ConfigProfile,
                PingResult
            ) -> Unit
    ): List<Future<*>> =
        submitBounded(
            profiles = profiles,
            executor = tcpExecutor,
            workers = MAX_TCP_WORKERS
        ) { profile ->
            callback(
                profile,
                tcp(
                    profile,
                    attempts
                )
            )
        }

    fun batch(
        profiles: List<ConfigProfile>,
        settings: AppSettings,
        workDir: File,
        callback:
            (
                ConfigProfile,
                PingResult
            ) -> Unit
    ): List<Future<*>> {
        val executor =
            when (
                settings.pingMethod
            ) {
                PingMethod.TCP_CONNECT ->
                    tcpExecutor

                PingMethod.XRAY_HTTP ->
                    xrayExecutor
            }
        val workers =
            if (
                settings.pingMethod ==
                PingMethod.TCP_CONNECT
            ) {
                MAX_TCP_WORKERS
            } else {
                1
            }

        return submitBounded(
            profiles = profiles,
            executor = executor,
            workers = workers
        ) { profile ->
            val result =
                if (
                    settings.pingMethod ==
                    PingMethod.XRAY_HTTP
                ) {
                    realXray(
                        profile = profile,
                        settings = settings,
                        workDir = workDir,
                        attempts =
                            BATCH_XRAY_ATTEMPTS,
                        timeoutSeconds =
                            BATCH_XRAY_TIMEOUT_SECONDS,
                        allowSingleSample = true,
                        maxTargetsPerSample =
                            BATCH_XRAY_MAX_TARGETS
                    )
                } else {
                    measure(
                        profile,
                        settings,
                        workDir
                    )
                }

            callback(
                profile,
                result
            )
        }
    }

    private fun submitBounded(
        profiles: List<ConfigProfile>,
        executor:
            java.util.concurrent.ExecutorService,
        workers: Int,
        action: (ConfigProfile) -> Unit
    ): List<Future<*>> {
        if (profiles.isEmpty()) {
            return emptyList()
        }

        val cursor =
            AtomicInteger(0)
        val count =
            workers
                .coerceAtLeast(1)
                .coerceAtMost(
                    profiles.size
                )

        return List(count) {
            executor.submit(
                Callable {
                    while (
                        !Thread.currentThread()
                            .isInterrupted
                    ) {
                        val index =
                            cursor
                                .getAndIncrement()

                        if (index >= profiles.size) {
                            break
                        }

                        action(profiles[index])
                    }
                }
            )
        }
    }

    fun cancel(
        tasks: Collection<Future<*>>
    ) {
        tasks.forEach {
            it.cancel(true)
        }
    }

    override fun close() {
        tcpExecutor.shutdownNow()
        xrayExecutor.shutdownNow()
        resolverExecutor
            .getAndSet(
                Executors
                    .newSingleThreadExecutor {
                        runnable ->
                        Thread(
                            runnable,
                            "trivox-dns-closed"
                        ).apply {
                            isDaemon = true
                        }
                    }
            )
            .shutdownNow()
        resolverExecutor.get()
            .shutdownNow()
        dnsCache.clear()
        negativeDnsCache.clear()
        preferredXrayTarget.set(null)
        preferredLocalProxyTarget.set(null)
    }

    private fun resolveAll(
        host: String,
        timeoutMs: Int
    ): List<InetAddress> {
        val normalized =
            host.trim()
                .lowercase()
        val now =
            System.currentTimeMillis()
        val cached =
            dnsCache[normalized]

        if (
            cached != null &&
            now - cached.storedAt <
            DNS_CACHE_TTL_MS
        ) {
            return cached.addresses
        }

        val negativeAt =
            negativeDnsCache[normalized]

        if (
            negativeAt != null &&
            now - negativeAt <
            NEGATIVE_DNS_CACHE_TTL_MS
        ) {
            throw UnknownHostException(
                "Recent DNS failure for $host"
            )
        }

        val executor =
            resolverExecutor.get()
        val task =
            try {
                executor.submit<
                    List<InetAddress>
                    > {
                    val allowPrivate =
                        allowsPrivateResolution(
                            normalized
                        )

                    InetAddress
                        .getAllByName(host)
                        .asSequence()
                        .filter {
                            allowPrivate ||
                                !isPrivateOrLocal(
                                    it
                                )
                        }
                        .distinctBy {
                            it.hostAddress
                        }
                        .sortedWith(
                            compareBy<InetAddress> {
                                if (
                                    it is Inet4Address
                                ) {
                                    0
                                } else {
                                    1
                                }
                            }.thenBy {
                                it.hostAddress
                            }
                        )
                        .take(
                            MAX_RESOLVED_ADDRESSES
                        )
                        .toList()
                }
            } catch (
                rejected:
                    RejectedExecutionException
            ) {
                rotateResolverExecutor(
                    executor
                )

                throw SocketTimeoutException(
                    "DNS resolver is saturated"
                ).apply {
                    initCause(rejected)
                }
            }

        return try {
            task.get(
                timeoutMs
                    .coerceAtMost(
                        DNS_TIMEOUT_MS
                    )
                    .toLong(),
                TimeUnit.MILLISECONDS
            ).takeIf {
                it.isNotEmpty()
            }
                ?.also {
                    negativeDnsCache
                        .remove(normalized)
                    dnsCache[normalized] =
                        CachedAddresses(
                            addresses = it,
                            storedAt = now
                        )
                }
                ?: throw UnknownHostException(
                    "No safe address for $host"
                )
        } catch (
            timeout: TimeoutException
        ) {
            task.cancel(true)
            negativeDnsCache[normalized] =
                now
            rotateResolverExecutor(
                executor
            )

            throw SocketTimeoutException(
                "DNS resolution timed out"
            ).apply {
                initCause(timeout)
            }
        } catch (
            throwable: Throwable
        ) {
            negativeDnsCache[normalized] =
                now
            throw throwable
        }
    }

    private fun rotateResolverExecutor(
        stale: ExecutorService
    ) {
        val now =
            System.currentTimeMillis()
        val previous =
            resolverRotationAt.get()

        if (
            now - previous <
            RESOLVER_ROTATION_COOLDOWN_MS ||
            !resolverRotationAt
                .compareAndSet(
                    previous,
                    now
                )
        ) {
            return
        }

        val replacement =
            newResolverExecutor()

        if (
            resolverExecutor
                .compareAndSet(
                    stale,
                    replacement
                )
        ) {
            stale.shutdownNow()
        } else {
            replacement.shutdownNow()
        }
    }

    private fun newResolverExecutor():
        ExecutorService =
        Executors.newFixedThreadPool(
            MAX_DNS_WORKERS,
            namedFactory(
                "trivox-dns"
            )
        )

    private fun allowsPrivateResolution(
        host: String
    ): Boolean =
        isLiteralAddress(host) ||
            host == "localhost" ||
            host.endsWith(".local") ||
            host.endsWith(".lan") ||
            host.endsWith(".home.arpa")

    private fun isLiteralAddress(
        host: String
    ): Boolean {
        if (
            host.count {
                it == '.'
            } == 3 &&
            host.split('.')
                .all {
                    it.toIntOrNull() in
                        0..255
                }
        ) {
            return true
        }

        return ':' in host
    }

    private fun isPrivateOrLocal(
        address: InetAddress
    ): Boolean {
        if (
            address.isAnyLocalAddress ||
            address.isLoopbackAddress ||
            address.isLinkLocalAddress ||
            address.isSiteLocalAddress ||
            address.isMulticastAddress
        ) {
            return true
        }

        return when (address) {
            is Inet4Address -> {
                val bytes =
                    address.address.map {
                        it.toInt() and 0xff
                    }

                bytes[0] == 0 ||
                    bytes[0] == 10 ||
                    bytes[0] == 127 ||
                    (
                        bytes[0] == 100 &&
                        bytes[1] in 64..127
                        ) ||
                    (
                        bytes[0] == 169 &&
                        bytes[1] == 254
                        ) ||
                    (
                        bytes[0] == 172 &&
                        bytes[1] in 16..31
                        ) ||
                    (
                        bytes[0] == 192 &&
                        bytes[1] == 168
                        ) ||
                    bytes[0] >= 224
            }

            is Inet6Address -> {
                val bytes =
                    address.address

                address.isIPv4CompatibleAddress ||
                    (
                        bytes.isNotEmpty() &&
                        (
                            bytes[0].toInt() and
                                0xfe
                            ) == 0xfc
                        )
            }

            else -> true
        }
    }

    private fun connectOnce(
        address: InetAddress,
        port: Int,
        timeoutMs: Int
    ): Long {
        val start =
            System.nanoTime()

        Socket().use {
            socket ->
            socket.tcpNoDelay = true
            socket.keepAlive = false
            socket.reuseAddress = false
            socket.connect(
                InetSocketAddress(
                    address,
                    port
                ),
                timeoutMs
            )
        }

        return (
            System.nanoTime() -
                start
            ).coerceAtLeast(1L)
    }

    private fun parseXrayDelay(
        response: org.json.JSONObject?,
        timeoutSeconds: Int
    ): Long? {
        val data =
            response
                ?.optJSONObject(
                    "data"
                )
                ?: return null

        if (
            !data.has("delay") ||
            data.isNull("delay")
        ) {
            return null
        }

        val value =
            when (
                val raw =
                    data.opt("delay")
            ) {
                is Number ->
                    raw.toLong()

                is String ->
                    raw.toLongOrNull()

                null -> null
                else -> null
            }
                ?: return null

        return value.takeIf {
            it in
                1L..
                (
                    timeoutSeconds
                        .toLong() *
                        1_000L
                    )
        }
    }

    private fun classifyXrayFailure(
        message: String
    ): String {
        val normalized =
            message.lowercase()

        return when {
            normalized.isBlank() ->
                "xray_test_failure"

            "no such host" in
                normalized ||
                "lookup " in
                normalized ->
                "dns_failure"

            "deadline exceeded" in
                normalized ||
                "timeout" in normalized ->
                "timeout"

            "connection refused" in
                normalized ->
                "connection_refused"

            "connection abort" in
                normalized ||
                "closed pipe" in
                normalized ->
                "connection_aborted"

            "illegal base64" in
                normalized ->
                "invalid_credentials"

            else ->
                "xray_test_failure"
        }
    }

    private fun failure(
        method: String,
        timestamp: Long,
        throwable: Throwable
    ) =
        PingResult(
            method = method,
            success = false,
            latencyMs = null,
            jitterMs = null,
            successRatio = 0.0,
            resolvedIp = null,
            timestamp = timestamp,
            errorCategory =
                classify(throwable)
        )

    private fun classify(
        throwable: Throwable
    ): String {
        val root =
            generateSequence(
                throwable
            ) {
                it.cause
            }.last()

        return when (root) {
            is InterruptedException ->
                "cancelled"

            is TimeoutException,
            is SocketTimeoutException ->
                "timeout"

            is UnknownHostException ->
                "dns_failure"

            is ConnectException ->
                "connection_refused"

            is SecurityException ->
                "operation_blocked"

            else ->
                root
                    .javaClass
                    .simpleName
                    .ifBlank {
                        "ping_failure"
                    }
        }
    }

    private fun cancelled(
        method: String,
        timestamp: Long
    ) =
        PingResult(
            method = method,
            success = false,
            latencyMs = null,
            jitterMs = null,
            successRatio = 0.0,
            resolvedIp = null,
            timestamp = timestamp,
            errorCategory =
                "cancelled"
        )

    private fun namedFactory(
        prefix: String
    ): ThreadFactory {
        val sequence =
            AtomicInteger(0)

        return ThreadFactory {
                runnable ->
            Thread(
                runnable,
                "$prefix-" +
                    sequence
                        .incrementAndGet()
            ).apply {
                isDaemon = true
            }
        }
    }

    private data class HttpProbe(
        val elapsedNanos: Long? = null,
        val failure: Throwable? = null
    )

    private data class CachedAddresses(
        val addresses: List<InetAddress>,
        val storedAt: Long
    )

    companion object {
        private const val
            MAX_TCP_WORKERS = 4
        private const val
            MAX_DNS_WORKERS = 2
        private const val
            MAX_RESOLVED_ADDRESSES = 4
        private const val
            MIN_TIMEOUT_MS = 500
        private const val
            MAX_TIMEOUT_MS = 15_000
        private const val
            DEFAULT_TCP_TIMEOUT_MS = 4_000
        private const val
            ADDRESS_PROBE_TIMEOUT_MS = 1_500
        private const val
            LOW_LATENCY_RECHECK_THRESHOLD_MS = 10L
        private const val
            LOW_LATENCY_RECHECK_ATTEMPTS = 3
        private const val
            DNS_TIMEOUT_MS = 3_000
        private const val
            DNS_CACHE_TTL_MS = 60_000L
        private const val
            NEGATIVE_DNS_CACHE_TTL_MS =
                15_000L
        private const val
            RESOLVER_ROTATION_COOLDOWN_MS =
                5_000L
        private const val
            BATCH_XRAY_ATTEMPTS = 1
        private const val
            BATCH_XRAY_TIMEOUT_SECONDS = 4
        private const val
            BATCH_XRAY_MAX_TARGETS = 2
        private const val
            MAX_HTTP_TARGETS_PER_SAMPLE = 3
        private const val
            FALLBACK_TARGET_TIMEOUT_MS =
                3_500
        private const val
            FALLBACK_TARGET_TIMEOUT_SECONDS =
                5
        private const val
            NANOS_PER_MILLISECOND =
                1_000_000L

        private val FALLBACK_CONNECTIVITY_URLS =
            listOf(
                "https://cp.cloudflare.com/generate_204",
                "https://connectivitycheck.gstatic.com/generate_204",
                "https://www.gstatic.com/generate_204",
                "http://www.google.com/gen_204"
            )
    }
}
