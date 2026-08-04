package com.trivox.client.network

import com.trivox.client.data.AppSettings
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.util.Locale

/**
 * Performs bounded HTTP(S) probes whose result is strong enough to prove that
 * application data crossed the selected route. A socket open, TLS alert,
 * redirect, captive portal, or arbitrary HTTP error is never accepted.
 */
internal object VerifiedHttpProbe {
    enum class Route {
        ACTIVE_NETWORK,
        HTTP_PROXY,
        SOCKS_PROXY
    }

    enum class Proof {
        CLOUDFLARE_TRACE,
        EXACT_204,
        SUCCESS_STATUS
    }

    data class Target(
        val url: String,
        val proof: Proof
    )

    data class Result(
        val success: Boolean,
        val latencyMs: Long? = null,
        val errorCategory: String? = null,
        val target: String = ""
    )

    val strongTraceTarget = Target(
        "https://1.1.1.1/cdn-cgi/trace",
        Proof.CLOUDFLARE_TRACE
    )

    val fallback204Targets = listOf(
        Target(
            "https://cp.cloudflare.com/generate_204",
            Proof.EXACT_204
        ),
        Target(
            "https://connectivitycheck.gstatic.com/generate_204",
            Proof.EXACT_204
        ),
        Target(
            "https://www.gstatic.com/generate_204",
            Proof.EXACT_204
        )
    )

    fun targetForUserUrl(value: String): Target? {
        val uri = runCatching { URI(value.trim()) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true)) return null
        if (uri.host.isNullOrBlank() || uri.userInfo != null) return null
        val path = uri.path.orEmpty().lowercase(Locale.ROOT)
        val proof = if (
            path.endsWith("/generate_204") ||
            path.endsWith("/gen_204")
        ) {
            Proof.EXACT_204
        } else {
            Proof.SUCCESS_STATUS
        }
        return Target(uri.toString(), proof)
    }

    fun probe(
        settings: AppSettings,
        route: Route,
        target: Target,
        timeoutMs: Int,
        nonce: Long
    ): Result {
        var connection: HttpURLConnection? = null
        return try {
            val uri = cacheBustedUri(target.url, nonce)
            require(uri.scheme.equals("https", ignoreCase = true)) {
                "Verified probe URL must use HTTPS"
            }

            val raw = when (route) {
                Route.ACTIVE_NETWORK -> uri.toURL().openConnection(Proxy.NO_PROXY)
                Route.HTTP_PROXY -> uri.toURL().openConnection(
                    Proxy(
                        Proxy.Type.HTTP,
                        InetSocketAddress("127.0.0.1", settings.socksPort)
                    )
                )
                Route.SOCKS_PROXY -> uri.toURL().openConnection(
                    Proxy(
                        Proxy.Type.SOCKS,
                        InetSocketAddress("127.0.0.1", settings.socksPort)
                    )
                )
            }

            connection = raw as HttpURLConnection
            connection.connectTimeout = timeoutMs.coerceIn(500, 15_000)
            connection.readTimeout = timeoutMs.coerceIn(500, 15_000)
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.requestMethod = "GET"
            connection.setRequestProperty("Connection", "close")
            connection.setRequestProperty(
                "Cache-Control",
                "no-cache, no-store, max-age=0"
            )
            connection.setRequestProperty("Pragma", "no-cache")
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.setRequestProperty("Accept", "text/plain,*/*;q=0.1")
            connection.setRequestProperty("User-Agent", "Trivox-VerifiedProbe/10")

            val started = System.nanoTime()
            val status = connection.responseCode
            val body = when (target.proof) {
                Proof.CLOUDFLARE_TRACE -> readBody(
                    connection,
                    settings.networkBufferSizeKb,
                    MAX_TRACE_BYTES
                )
                else -> ""
            }
            val elapsedMs = (
                (System.nanoTime() - started) / NANOS_PER_MILLISECOND
            ).coerceAtLeast(1L)

            if (accepts(target, status, body)) {
                Result(
                    success = true,
                    latencyMs = elapsedMs,
                    target = target.url
                )
            } else {
                Result(
                    success = false,
                    errorCategory = when {
                        status in 300..399 -> "http_redirect_$status"
                        target.proof == Proof.CLOUDFLARE_TRACE && status == 200 ->
                            "invalid_trace_body"
                        else -> "http_$status"
                    },
                    target = target.url
                )
            }
        } catch (throwable: Throwable) {
            Result(
                success = false,
                errorCategory = classify(throwable),
                target = target.url
            )
        } finally {
            closeConnection(connection)
        }
    }

    internal fun accepts(
        target: Target,
        statusCode: Int,
        body: String
    ): Boolean = when (target.proof) {
        Proof.EXACT_204 -> statusCode == 204
        Proof.SUCCESS_STATUS -> statusCode in 200..299
        Proof.CLOUDFLARE_TRACE ->
            statusCode == 200 && validCloudflareTrace(body)
    }

    internal fun validCloudflareTrace(body: String): Boolean {
        if (body.length !in 20..MAX_TRACE_BYTES) return false
        val values = body.lineSequence()
            .map(String::trim)
            .filter { '=' in it }
            .associate { line ->
                line.substringBefore('=').trim() to
                    line.substringAfter('=').trim()
            }
        val ip = values["ip"].orEmpty()
        val colo = values["colo"].orEmpty()
        val tls = values["tls"].orEmpty()
        return ip.isNotBlank() &&
            ('.' in ip || ':' in ip) &&
            colo.length in 3..8 &&
            tls.isNotBlank()
    }

    private fun cacheBustedUri(url: String, nonce: Long): URI {
        val separator = if ('?' in url) '&' else '?'
        return URI("$url${separator}trivox_probe=$nonce")
    }

    private fun readBody(
        connection: HttpURLConnection,
        preferredBufferKb: Int,
        limit: Int
    ): String {
        val input: InputStream = if (connection.responseCode in 200..299) {
            connection.inputStream
        } else {
            connection.errorStream ?: return ""
        }
        return input.use {
            NetworkBufferPool.readUtf8Bounded(
                it,
                limit,
                preferredBufferKb
            )
        }
    }

    private fun closeConnection(connection: HttpURLConnection?) {
        runCatching { connection?.inputStream?.close() }
        runCatching { connection?.errorStream?.close() }
        connection?.disconnect()
    }

    private fun classify(throwable: Throwable): String {
        val root = generateSequence(throwable) { it.cause }.last()
        return root.javaClass.simpleName
            .ifBlank { "verified_probe_failed" }
            .lowercase(Locale.ROOT)
    }

    private const val MAX_TRACE_BYTES = 8 * 1024
    private const val NANOS_PER_MILLISECOND = 1_000_000L
}
