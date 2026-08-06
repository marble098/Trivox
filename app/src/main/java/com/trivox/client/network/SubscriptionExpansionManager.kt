package com.trivox.client.network

import com.trivox.client.config.ConfigParser
import com.trivox.client.config.NativeConfigImporter
import com.trivox.client.config.NativeProfileDocument
import com.trivox.client.data.ConfigProfile
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.Locale
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream
import javax.net.ssl.HttpsURLConnection

/**
 * Fetches a subscription as a document graph. This is used by cross-core
 * providers are expanded into portable profiles when conversion is requested.
 */
class SubscriptionExpansionManager {
    data class Result(
        val rootNative: ConfigProfile?,
        val profiles: List<ConfigProfile>,
        val finalUrl: String,
        val warnings: List<String>
    )

    fun expand(source: String): Result {
        val remote = NativeProfileDocument.remoteProfileUrl(source) ?: source
        val rootResponse = fetchText(remote)
        val rootText = rootResponse.text
        val rootNative = NativeProfileDocument.parseExactDocumentOrNull(rootText)
        val unique = LinkedHashMap<String, ConfigProfile>()
        NativeConfigImporter.convertibleProfiles(rootText).forEach { profile ->
            unique.putIfAbsent(identity(profile), profile)
        }
        val warnings = mutableListOf<String>()

        val providers = NativeProfileDocument.providerUrls(rootText, rootResponse.finalUrl)
            .take(MAX_PROVIDER_COUNT)
        providers.forEach { providerUrl ->
            ensureActive()
            runCatching { fetchText(providerUrl) }
                .onSuccess { provider ->
                    val parsed = NativeConfigImporter.convertibleProfiles(provider.text).ifEmpty {
                        runCatching { ConfigParser.parseText(provider.text) }.getOrDefault(emptyList())
                            .filter { NativeProfileDocument.affinity(it) == null }
                    }
                    if (parsed.isEmpty()) {
                        warnings += "Provider returned no portable profiles: ${safeHost(provider.finalUrl)}"
                    }
                    parsed.forEach { profile -> unique.putIfAbsent(identity(profile), profile) }
                }
                .onFailure { error ->
                    warnings += "Provider ${safeHost(providerUrl)}: ${error.message ?: error.javaClass.simpleName}"
                }
        }

        if (rootNative == null && unique.isEmpty()) {
            ConfigParser.parseText(rootText).forEach { profile ->
                unique.putIfAbsent(identity(profile), profile)
            }
        }

        return Result(
            rootNative = rootNative,
            profiles = unique.values.toList(),
            finalUrl = rootResponse.finalUrl,
            warnings = warnings.take(MAX_WARNINGS)
        )
    }

    private data class TextResponse(val text: String, val finalUrl: String)

    private fun fetchText(value: String): TextResponse {
        var current = SubscriptionManager.normalizeUrl(value)
        val visited = linkedSetOf<String>()
        repeat(MAX_REDIRECTS + 1) {
            ensureActive()
            val canonical = current.toASCIIString()
            if (!visited.add(canonical)) throw IOException("Subscription redirect loop detected")
            val connection = current.toURL().openConnection() as? HttpsURLConnection
                ?: throw IOException("Only HTTPS subscription URLs are accepted")
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS
                connection.useCaches = false
                connection.requestMethod = "GET"
                connection.setRequestProperty(
                    "Accept",
                    "text/yaml, application/yaml, application/x-yaml, application/json, text/plain;q=0.9, */*;q=0.1"
                )
                connection.setRequestProperty("Accept-Encoding", "gzip, deflate")
                connection.setRequestProperty("Cache-Control", "no-cache")
                connection.setRequestProperty("Connection", "close")
                connection.setRequestProperty("User-Agent", "Trivox-NativeExpansion/1")
                val status = connection.responseCode
                if (status in 300..399) {
                    val location = connection.getHeaderField("Location")?.trim().orEmpty()
                    if (location.isBlank()) throw IOException("Subscription redirect has no Location header")
                    current = SubscriptionManager.normalizeUrl(current.resolve(location).toString())
                    return@repeat
                }
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val bytes = stream?.use { source ->
                    decoded(source, connection.contentEncoding.orEmpty()).use { readLimited(it) }
                } ?: ByteArray(0)
                if (status !in 200..299) {
                    val details = bytes.toString(Charsets.UTF_8).replace(Regex("\\s+"), " ").take(200)
                    throw IOException("Subscription server returned HTTP $status${details.takeIf(String::isNotBlank)?.let { ": $it" }.orEmpty()}")
                }
                val text = bytes.toString(Charsets.UTF_8).trim().removePrefix("\uFEFF")
                if (text.isBlank()) throw IOException("Subscription response is empty")
                if (text.startsWith("<html", true) || text.startsWith("<!doctype html", true)) {
                    throw IOException("Subscription returned an HTML page")
                }
                return TextResponse(text, current.toASCIIString())
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("Too many subscription redirects")
    }

    private fun decoded(input: InputStream, encodingValue: String): InputStream =
        when (encodingValue.substringBefore(',').trim().lowercase(Locale.ROOT)) {
            "", "identity" -> input
            "gzip", "x-gzip" -> GZIPInputStream(input)
            "deflate" -> InflaterInputStream(input)
            else -> throw IOException("Unsupported subscription content encoding")
        }

    private fun readLimited(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            ensureActive()
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > MAX_RESPONSE_BYTES) throw IOException("Subscription response exceeds 8 MiB")
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun ensureActive() {
        if (Thread.currentThread().isInterrupted) throw IOException("Subscription operation cancelled")
    }

    private fun identity(profile: ConfigProfile): String =
        profile.outboundJson.trim().ifBlank { profile.raw.trim() }

    private fun safeHost(url: String): String =
        runCatching { URI(url).host }.getOrNull()?.takeIf(String::isNotBlank) ?: "provider"

    companion object {
        private const val MAX_REDIRECTS = 6
        private const val MAX_PROVIDER_COUNT = 16
        private const val MAX_WARNINGS = 8
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val READ_TIMEOUT_MS = 25_000
        private const val MAX_RESPONSE_BYTES = 8 * 1024 * 1024
    }
}
