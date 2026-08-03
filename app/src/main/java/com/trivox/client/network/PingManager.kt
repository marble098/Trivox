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
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
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
        Executors.newFixedThreadPool(
            MAX_DNS_WORKERS,
            namedFactory("trivox-dns")
        )

    fun measure(
        profile: ConfigProfile,
        settings: AppSettings,
        workDir: File
    ): PingResult =
        when (settings.pingMethod) {
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

        val addresses =
            try {
                resolveAll(
                    profile.server,
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
                    port = profile.port,
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
                            port = profile.port,
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
        timeoutSeconds: Int = 8
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

        val uri =
            runCatching {
                URI(settings.testUrl)
            }.getOrNull()

        if (
            uri == null ||
            uri.host.isNullOrBlank() ||
            uri.scheme
                ?.lowercase() !in
                setOf("http", "https")
        ) {
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

        val count =
            attempts.coerceIn(3, 5)
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
                repeat(sampleCount) { index ->
                    if (
                        Thread.currentThread()
                            .isInterrupted
                    ) {
                        return
                    }

                    val xrayRequestUrl =
                        cacheBustedUrl(
                            settings.testUrl,
                            timestamp +
                                samples.size +
                                index
                        )
                    val result =
                        adapter.realDelay(
                            configFile.absolutePath,
                            boundedTimeout,
                            xrayRequestUrl
                        )
                    val delay =
                        parseXrayDelay(
                            result.data,
                            boundedTimeout
                        )

                    if (
                        result.success &&
                        delay != null
                    ) {
                        samples +=
                            delay *
                                NANOS_PER_MILLISECOND
                    } else {
                        lastError =
                            result.error
                                .ifBlank {
                                    "xray_test_failure"
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
            attempts.coerceIn(3, 5)
        val boundedTimeout =
            timeoutMs.coerceIn(
                1_500,
                15_000
            )
        val uri =
            runCatching {
                URI(url)
            }.getOrNull()

        if (
            uri == null ||
            uri.host.isNullOrBlank() ||
            uri.scheme
                ?.lowercase() !in
                setOf("http", "https")
        ) {
            return PingResult(
                method = PingMethod.XRAY_HTTP.name,
                success = false,
                latencyMs = null,
                jitterMs = null,
                successRatio = 0.0,
                resolvedIp = "127.0.0.1",
                timestamp = timestamp,
                errorCategory = "invalid_test_url"
            )
        }

        var samples = mutableListOf<Long>()
        var lastFailure: Throwable? = null
        var activeProxyType = Proxy.Type.HTTP

        fun runSamples(
            proxyType: Proxy.Type,
            sampleCount: Int
        ) {
            activeProxyType = proxyType

            repeat(sampleCount) { index ->
                if (Thread.currentThread().isInterrupted) {
                    return
                }

                var connection: HttpURLConnection? = null

                try {
                    val proxy =
                        Proxy(
                            proxyType,
                            InetSocketAddress(
                                "127.0.0.1",
                                settings.socksPort
                            )
                        )
                    val requestUrl =
                        cacheBustedUrl(
                            url,
                            timestamp +
                                samples.size +
                                index
                        )
                    connection =
                        URI(requestUrl)
                            .toURL()
                            .openConnection(proxy) as
                            HttpURLConnection
                    connection.connectTimeout =
                        boundedTimeout
                    connection.readTimeout =
                        boundedTimeout
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
                        "Trivox-AlivePing/3"
                    )
                    connection.setRequestProperty(
                        "X-Trivox-Ping",
                        (timestamp + index).toString()
                    )

                    val startNanos =
                        System.nanoTime()
                    val code =
                        connection.responseCode
                    val elapsed =
                        System.nanoTime() - startNanos

                    if (isExpectedHttpResponse(uri, code)) {
                        samples += elapsed
                    } else {
                        lastFailure =
                            IllegalStateException(
                                "Unexpected HTTP status $code"
                            )
                    }
                } catch (throwable: Throwable) {
                    lastFailure = throwable
                } finally {
                    runCatching {
                        connection?.inputStream?.close()
                    }
                    runCatching {
                        connection?.errorStream?.close()
                    }
                    connection?.disconnect()
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
            method = PingMethod.XRAY_HTTP.name,
            success = summary.success,
            latencyMs = summary.latencyMs,
            jitterMs = summary.jitterMs,
            successRatio = summary.successRatio,
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
        val googleGenerate204 =
            uri.host.equals(
                "www.google.com",
                ignoreCase = true
            ) &&
                uri.path == "/gen_204"

        return if (googleGenerate204) {
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
        profiles.map {
                profile ->
            tcpExecutor.submit(
                Callable {
                    callback(
                        profile,
                        tcp(
                            profile,
                            attempts
                        )
                    )
                }
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

        return profiles.map {
                profile ->
            executor.submit(
                Callable {
                    callback(
                        profile,
                        measure(
                            profile,
                            settings,
                            workDir
                        )
                    )
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
        resolverExecutor.shutdownNow()
    }

    private fun resolveAll(
        host: String,
        timeoutMs: Int
    ): List<InetAddress> {
        val task =
            resolverExecutor.submit<
                List<InetAddress>
                > {
                InetAddress
                    .getAllByName(host)
                    .distinctBy {
                        it.hostAddress
                    }
                    .take(
                        MAX_RESOLVED_ADDRESSES
                    )
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
                ?: throw UnknownHostException(
                    host
                )
        } catch (
            _: TimeoutException
        ) {
            task.cancel(true)
            throw SocketTimeoutException(
                "DNS resolution timed out"
            )
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
            NANOS_PER_MILLISECOND =
                1_000_000L
    }
}
