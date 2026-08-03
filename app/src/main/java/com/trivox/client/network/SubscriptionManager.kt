package com.trivox.client.network

import com.trivox.client.config.ConfigParser
import com.trivox.client.data.ConfigProfile
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

class SubscriptionManager {
    data class Result(val profiles: List<ConfigProfile>, val finalUrl: String)

    fun fetch(url: String): Result {
        val uri =
            runCatching {
                URI(url)
            }.getOrElse {
                throw IOException(
                    "Invalid subscription URL"
                )
            }

        require(
            uri.scheme.equals(
                "https",
                ignoreCase = true
            )
        ) {
            "Only HTTPS subscription URLs are accepted"
        }
        require(
            !uri.host.isNullOrBlank() &&
                uri.userInfo == null &&
                uri.fragment == null
        ) {
            "Subscription URL must contain a host and no credentials or fragment"
        }
        var current = uri.toURL()
        repeat(6) {
            val connection = (current.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false; connectTimeout = 10_000; readTimeout = 20_000
                setRequestProperty("Accept", "text/plain, application/json;q=0.9, */*;q=0.1")
                setRequestProperty(
                    "User-Agent",
                    "Trivox/3"
                )
            }
            try {
                val code = connection.responseCode
                if (code in 300..399) {
                    val location = connection.getHeaderField("Location") ?: throw IOException("Redirect has no Location header")
                    val next = URL(current, location)
                    if (next.protocol != "https") throw IOException("Subscription redirect to non-HTTPS URL was rejected")
                    current = next
                    return@repeat
                }
                if (code !in 200..299) throw IOException("Subscription server returned HTTP $code")
                val contentType = connection.contentType.orEmpty().lowercase()
                val bytes = connection.inputStream.use { stream ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(16 * 1024)
                    var total = 0
                    while (true) {
                        val read = stream.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > 4 * 1024 * 1024) throw IOException("Subscription response exceeds 4 MiB")
                        output.write(buffer, 0, read)
                    }
                    output.toByteArray()
                }
                val text = bytes.toString(Charsets.UTF_8).trim()
                if (text.isBlank()) throw IOException("Subscription response is empty")
                if (contentType.contains("text/html") || text.startsWith("<!doctype html", true) || text.startsWith("<html", true)) {
                    throw IOException("Subscription returned an HTML login or error page")
                }
                return Result(ConfigParser.parseText(text), current.toString())
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("Too many subscription redirects")
    }
}
