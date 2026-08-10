package com.trivox.client.network

import com.trivox.client.config.ConfigParser
import com.trivox.client.data.ConfigProfile
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.IDN
import java.net.URI
import java.net.URL
import java.util.Locale
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream
import javax.net.ssl.HttpsURLConnection
import kotlin.math.min

class SubscriptionManager {
    data class Result(
        val profiles: List<ConfigProfile>,
        val finalUrl: String,
        val notModified: Boolean = false,
        val etag: String? = null,
        val lastModified: String? = null
    )

    /**
     * [etag]/[lastModified] are the validators persisted from a previous
     * successful fetch of the same URL, if any. When the server still
     * recognizes them it replies 304 Not Modified with no body, and this
     * returns [Result.notModified] = true with an empty profile list -
     * callers must skip re-parsing/re-merging in that case rather than
     * treating an empty list as "subscription has no configs."
     */
    fun fetch(
        url: String,
        etag: String? = null,
        lastModified: String? = null
    ): Result {
        var current =
            normalizeUrl(url)
        val visited =
            LinkedHashSet<String>()

        repeat(MAX_REDIRECTS + 1) {
            ensureActive()

            val canonical =
                current.toASCIIString()

            if (!visited.add(canonical)) {
                throw IOException(
                    "Subscription redirect loop detected"
                )
            }

            val response =
                requestWithRetry(
                    uri = current,
                    etag = etag,
                    lastModified = lastModified
                )

            if (response.redirect != null) {
                current =
                    normalizeRedirect(
                        base = current,
                        location =
                            response.redirect
                    )
                return@repeat
            }

            if (response.notModified) {
                return Result(
                    profiles = emptyList(),
                    finalUrl = canonical,
                    notModified = true,
                    etag = response.etag,
                    lastModified = response.lastModified
                )
            }

            val text =
                response.body
                    ?.toString(Charsets.UTF_8)
                    ?.trim()
                    .orEmpty()

            if (text.isBlank()) {
                throw IOException(
                    "Subscription response is empty"
                )
            }

            if (
                response.contentType
                    .contains(
                        "text/html",
                        ignoreCase = true
                    ) ||
                text.startsWith(
                    "<!doctype html",
                    ignoreCase = true
                ) ||
                text.startsWith(
                    "<html",
                    ignoreCase = true
                )
            ) {
                throw IOException(
                    "Subscription returned an HTML login or error page"
                )
            }

            val profiles =
                ConfigParser.parseText(text)

            if (profiles.isEmpty()) {
                throw IOException(
                    "Subscription contains no supported configs"
                )
            }

            return Result(
                profiles = profiles,
                finalUrl =
                    current.toASCIIString(),
                etag = response.etag,
                lastModified = response.lastModified
            )
        }

        throw IOException(
            "Too many subscription redirects"
        )
    }

    private fun requestWithRetry(
        uri: URI,
        etag: String?,
        lastModified: String?
    ): Response {
        var lastFailure: Throwable? = null

        repeat(REQUEST_ATTEMPTS) {
                attempt ->
            ensureActive()

            try {
                val response =
                    requestOnce(
                        uri = uri,
                        etag = etag,
                        lastModified = lastModified
                    )

                if (
                    response.status in
                    RETRYABLE_HTTP_CODES
                ) {
                    if (
                        attempt + 1 <
                        REQUEST_ATTEMPTS
                    ) {
                        sleepBackoff(
                            attempt = attempt,
                            retryAfterMillis =
                                response.retryAfterMillis
                        )
                        return@repeat
                    }

                    throw IOException(
                        "Subscription server returned HTTP " +
                            response.status
                    )
                }

                return response
            } catch (
                throwable: Throwable
            ) {
                if (
                    throwable
                        .isSubscriptionCancellation()
                ) {
                    throwSubscriptionCancelled(
                        throwable
                    )
                }

                lastFailure = throwable

                if (
                    throwable is
                    NonRetryableSubscriptionException ||
                    attempt + 1 >=
                    REQUEST_ATTEMPTS
                ) {
                    throw throwable
                }

                sleepBackoff(
                    attempt = attempt,
                    retryAfterMillis = null
                )
            }
        }

        throw IOException(
            "Subscription request failed",
            lastFailure
        )
    }

    private fun requestOnce(
        uri: URI,
        etag: String?,
        lastModified: String?
    ): Response {
        val connection =
            uri.toURL()
                .openConnection() as?
                HttpsURLConnection
                ?: throw IOException(
                    "Only HTTPS subscription URLs are accepted"
                )

        try {
            connection.instanceFollowRedirects =
                false
            connection.connectTimeout =
                CONNECT_TIMEOUT_MS
            connection.readTimeout =
                READ_TIMEOUT_MS
            connection.useCaches = false
            connection.requestMethod = "GET"
            connection.setRequestProperty(
                "Accept",
                "text/plain, application/json;q=0.9, " +
                    "application/octet-stream;q=0.8, */*;q=0.1"
            )
            connection.setRequestProperty(
                "Accept-Encoding",
                "gzip, deflate"
            )
            connection.setRequestProperty(
                "Cache-Control",
                "no-cache"
            )
            connection.setRequestProperty(
                "Connection",
                "close"
            )
            connection.setRequestProperty(
                "User-Agent",
                "Trivox-Subscription/4"
            )
            if (!etag.isNullOrBlank()) {
                connection.setRequestProperty(
                    "If-None-Match",
                    etag
                )
            }
            if (!lastModified.isNullOrBlank()) {
                connection.setRequestProperty(
                    "If-Modified-Since",
                    lastModified
                )
            }

            ensureActive()
            val status =
                connection.responseCode
            val retryAfter =
                parseRetryAfter(
                    connection.getHeaderField(
                        "Retry-After"
                    )
                )

            if (status == HttpURLConnection.HTTP_NOT_MODIFIED) {
                return Response(
                    status = status,
                    notModified = true,
                    etag = connection.getHeaderField("ETag"),
                    lastModified = connection.getHeaderField("Last-Modified"),
                    retryAfterMillis = retryAfter
                )
            }

            if (status in 300..399) {
                val location =
                    connection.getHeaderField(
                        "Location"
                    )
                        ?.trim()
                        .orEmpty()

                if (location.isBlank()) {
                    throw IOException(
                        "Subscription redirect has no Location header"
                    )
                }

                return Response(
                    status = status,
                    redirect = location,
                    retryAfterMillis =
                        retryAfter
                )
            }

            val source =
                if (status in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream
                }

            val body =
                source
                    ?.use {
                        decodedStream(
                            input = it,
                            contentEncoding =
                                connection
                                    .contentEncoding
                                    .orEmpty()
                        ).use { decoded ->
                            readLimited(
                                input = decoded,
                                limit =
                                    if (
                                        status in
                                        200..299
                                    ) {
                                        MAX_RESPONSE_BYTES
                                    } else {
                                        MAX_ERROR_BYTES
                                    }
                            )
                        }
                    }
                    ?: ByteArray(0)

            if (status !in 200..299) {
                val message =
                    body.toString(
                        Charsets.UTF_8
                    )
                        .replace(
                            Regex("\\s+"),
                            " "
                        )
                        .take(240)
                        .trim()

                val error =
                    "Subscription server returned HTTP $status" +
                        message
                            .takeIf(
                                String::isNotBlank
                            )
                            ?.let {
                                ": $it"
                            }
                            .orEmpty()

                if (
                    status !in
                    RETRYABLE_HTTP_CODES
                ) {
                    throw NonRetryableSubscriptionException(
                        error
                    )
                }

                return Response(
                    status = status,
                    retryAfterMillis =
                        retryAfter
                )
            }

            return Response(
                status = status,
                body = body,
                contentType =
                    connection.contentType
                        .orEmpty(),
                retryAfterMillis =
                    retryAfter,
                etag = connection.getHeaderField("ETag"),
                lastModified = connection.getHeaderField("Last-Modified")
            )
        } catch (
            interrupted:
                InterruptedException
        ) {
            throwSubscriptionCancelled(
                interrupted
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun normalizeRedirect(
        base: URI,
        location: String
    ): URI {
        val resolved =
            runCatching {
                base.resolve(location)
            }.getOrElse {
                throw IOException(
                    "Subscription redirect URL is invalid"
                )
            }

        return normalizeUrl(
            resolved.toString()
        )
    }

    private fun decodedStream(
        input: InputStream,
        contentEncoding: String
    ): InputStream {
        val encoding =
            contentEncoding
                .substringBefore(',')
                .trim()
                .lowercase(Locale.ROOT)

        return when (encoding) {
            "", "identity" -> input
            "gzip", "x-gzip" ->
                GZIPInputStream(input)
            "deflate" ->
                InflaterInputStream(input)
            else ->
                throw NonRetryableSubscriptionException(
                    "Unsupported subscription content encoding: " +
                        encoding.take(32)
                )
        }
    }

    private fun readLimited(
        input: InputStream,
        limit: Int
    ): ByteArray {
        val output =
            ByteArrayOutputStream()
        val buffer =
            ByteArray(16 * 1024)
        var total = 0

        while (true) {
            ensureActive()

            val count =
                input.read(buffer)

            if (count < 0) {
                break
            }

            total += count

            if (total > limit) {
                throw IOException(
                    "Subscription response exceeds " +
                        "${limit / (1024 * 1024)} MiB"
                )
            }

            output.write(
                buffer,
                0,
                count
            )
        }

        return output.toByteArray()
    }

    private fun sleepBackoff(
        attempt: Int,
        retryAfterMillis: Long?
    ) {
        val delay =
            min(
                retryAfterMillis
                    ?: (
                        RETRY_BASE_DELAY_MS *
                            (1L shl attempt)
                        ),
                MAX_RETRY_DELAY_MS
            )

        try {
            Thread.sleep(delay)
        } catch (
            interrupted:
                InterruptedException
        ) {
            throwSubscriptionCancelled(
                interrupted
            )
        }
    }

    private fun ensureActive() {
        if (
            Thread.currentThread()
                .isInterrupted
        ) {
            throwSubscriptionCancelled()
        }
    }

    private fun parseRetryAfter(
        value: String?
    ): Long? =
        value
            ?.trim()
            ?.toLongOrNull()
            ?.coerceIn(0L, 30L)
            ?.times(1_000L)

    private data class Response(
        val status: Int,
        val body: ByteArray? = null,
        val contentType: String = "",
        val redirect: String? = null,
        val retryAfterMillis: Long? = null,
        val notModified: Boolean = false,
        val etag: String? = null,
        val lastModified: String? = null
    )

    private class
        NonRetryableSubscriptionException(
            message: String
        ) : IOException(message)

    companion object {
        fun normalizeUrl(
            value: String
        ): URI {
            val raw =
                value.trim()
                    .removePrefix("\uFEFF")

            require(raw.isNotBlank()) {
                "Subscription URL is empty"
            }

            val withScheme =
                if (
                    SCHEME_PATTERN
                        .containsMatchIn(raw)
                ) {
                    raw
                } else {
                    "https://$raw"
                }

            val parsed =
                runCatching {
                    URI(withScheme)
                }.getOrElse {
                    throw IllegalArgumentException(
                        "Subscription URL is invalid"
                    )
                }

            require(
                parsed.scheme.equals(
                    "https",
                    ignoreCase = true
                )
            ) {
                "Only HTTPS subscription URLs are accepted"
            }

            require(
                parsed.userInfo == null
            ) {
                "Subscription URL credentials are not allowed"
            }

            val host =
                parsed.host
                    ?.trim()
                    ?.takeIf(
                        String::isNotBlank
                    )
                    ?: hostFromAuthority(
                        parsed.rawAuthority
                    )

            require(
                !host.isNullOrBlank()
            ) {
                "Subscription URL must contain a host"
            }

            val normalizedHost =
                normalizeHost(host)
            val port =
                parsed.port

            require(
                port == -1 ||
                    port in 1..65535
            ) {
                "Subscription URL port is invalid"
            }

            val authority =
                buildString {
                    if (':' in normalizedHost) {
                        append('[')
                        append(normalizedHost)
                        append(']')
                    } else {
                        append(normalizedHost)
                    }

                    if (port != -1) {
                        append(':')
                        append(port)
                    }
                }
            val path =
                parsed.rawPath
                    .orEmpty()
                    .ifBlank {
                        "/"
                    }
            val query =
                parsed.rawQuery
                    ?.let {
                        "?$it"
                    }
                    .orEmpty()

            return URI(
                "https://$authority$path$query"
            )
        }

        private fun hostFromAuthority(
            authority: String?
        ): String? {
            val clean =
                authority
                    ?.substringAfterLast('@')
                    ?.trim()
                    .orEmpty()

            if (clean.isBlank()) {
                return null
            }

            if (clean.startsWith('[')) {
                val end =
                    clean.indexOf(']')

                if (end > 1) {
                    return clean.substring(
                        1,
                        end
                    )
                }
            }

            return clean
                .substringBeforeLast(
                    ':',
                    clean
                )
                .trim()
                .ifBlank {
                    null
                }
        }

        private fun normalizeHost(
            host: String
        ): String {
            val clean =
                host.trim()
                    .removePrefix("[")
                    .removeSuffix("]")

            return if (':' in clean) {
                clean.lowercase(Locale.ROOT)
            } else {
                IDN.toASCII(
                    clean,
                    IDN.USE_STD3_ASCII_RULES
                ).lowercase(Locale.ROOT)
            }
        }

        private val SCHEME_PATTERN =
            Regex(
                "^[A-Za-z][A-Za-z0-9+.-]*://"
            )
        private const val MAX_REDIRECTS = 6
        private const val REQUEST_ATTEMPTS = 3
        private const val CONNECT_TIMEOUT_MS =
            8_000
        private const val READ_TIMEOUT_MS =
            20_000
        private const val RETRY_BASE_DELAY_MS =
            350L
        private const val MAX_RETRY_DELAY_MS =
            4_000L
        private const val MAX_RESPONSE_BYTES =
            4 * 1024 * 1024
        private const val MAX_ERROR_BYTES =
            32 * 1024
        private val RETRYABLE_HTTP_CODES =
            setOf(
                HttpURLConnection.HTTP_CLIENT_TIMEOUT,
                425,
                429,
                HttpURLConnection.HTTP_INTERNAL_ERROR,
                HttpURLConnection.HTTP_BAD_GATEWAY,
                HttpURLConnection.HTTP_UNAVAILABLE,
                HttpURLConnection.HTTP_GATEWAY_TIMEOUT
            )
    }
}
