package com.trivox.client.network

import com.trivox.client.BuildConfig
import com.trivox.client.data.AppSettings
import com.trivox.client.data.ConnectionMode
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.util.Locale
import javax.net.ssl.HttpsURLConnection

data class ExitConnectionInfo(
    val ip: String,
    val country: String,
    val countryCode: String,
    val flagEmoji: String,
    val isp: String,
    val city: String
)

class ConnectionInfoManager {
    fun fetch(settings: AppSettings): ExitConnectionInfo {
        val proxy = if (settings.mode == ConnectionMode.PROXY) {
            Proxy(
                Proxy.Type.SOCKS,
                InetSocketAddress("127.0.0.1", settings.socksPort)
            )
        } else {
            Proxy.NO_PROXY
        }

        val failures = mutableListOf<String>()
        providers.forEach { provider ->
            runCatching {
                val body = request(
                    provider.url,
                    proxy,
                    settings.networkBufferSizeKb
                )
                provider.parse(body)
            }.onSuccess {
                if (it.ip.isNotBlank()) return it
                failures += "${provider.name}: empty_ip"
            }.onFailure {
                failures += "${provider.name}: " +
                    (it.message ?: it.javaClass.simpleName)
            }
        }

        throw IllegalStateException(
            "Exit IP lookup failed across all providers: " +
                failures.takeLast(3).joinToString(" | ")
        )
    }

    private fun request(
        url: String,
        proxy: Proxy,
        bufferSizeKb: Int
    ): String {
        val connection = URI(url).toURL().openConnection(proxy) as HttpsURLConnection
        connection.connectTimeout = PROVIDER_TIMEOUT_MS
        connection.readTimeout = PROVIDER_TIMEOUT_MS
        connection.instanceFollowRedirects = false
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", "application/json,text/plain")
        connection.setRequestProperty("Accept-Encoding", "identity")
        connection.setRequestProperty("Connection", "close")
        connection.setRequestProperty(
            "User-Agent",
            "Trivox/${BuildConfig.VERSION_NAME} (${BuildConfig.GIT_SHA})"
        )

        return try {
            val status = connection.responseCode
            if (status !in 200..299) {
                throw IllegalStateException("HTTP $status")
            }
            connection.inputStream.use { input ->
                NetworkBufferPool.readUtf8Bounded(
                    input = input,
                    maxBytes = MAX_RESPONSE_SIZE,
                    preferredBufferKb = bufferSizeKb
                )
            }
        } finally {
            connection.disconnect()
        }
    }

    private data class Provider(
        val name: String,
        val url: String,
        val parse: (String) -> ExitConnectionInfo
    )

    companion object {
        private val providers = listOf(
            Provider(
                name = "ipwho.is",
                url = "https://ipwho.is/",
                parse = ::parseIpWho
            ),
            Provider(
                name = "ipapi.co",
                url = "https://ipapi.co/json/",
                parse = ::parseIpApi
            ),
            Provider(
                name = "cloudflare-trace",
                url = "https://www.cloudflare.com/cdn-cgi/trace",
                parse = ::parseCloudflareTrace
            )
        )

        private fun parseIpWho(text: String): ExitConnectionInfo {
            val json = JSONObject(text)
            if (!json.optBoolean("success", false)) {
                throw IllegalStateException(
                    json.optString("message").ifBlank { "lookup_failed" }
                )
            }
            val connection = json.optJSONObject("connection")
            val code = json.optString("country_code").trim()
            return ExitConnectionInfo(
                ip = json.optString("ip").trim(),
                country = json.optString("country").trim(),
                countryCode = code,
                flagEmoji = json.optJSONObject("flag")
                    ?.optString("emoji")
                    ?.trim()
                    .orEmpty()
                    .ifBlank { flagEmoji(code) },
                isp = connection?.optString("isp")?.trim()
                    .orEmpty()
                    .ifBlank {
                        connection?.optString("org")?.trim().orEmpty()
                    },
                city = json.optString("city").trim()
            )
        }

        private fun parseIpApi(text: String): ExitConnectionInfo {
            val json = JSONObject(text)
            if (json.optBoolean("error", false)) {
                throw IllegalStateException(
                    json.optString("reason").ifBlank { "lookup_failed" }
                )
            }
            val code = json.optString("country_code").trim()
            return ExitConnectionInfo(
                ip = json.optString("ip").trim(),
                country = json.optString("country_name").trim(),
                countryCode = code,
                flagEmoji = flagEmoji(code),
                isp = json.optString("org").trim(),
                city = json.optString("city").trim()
            )
        }

        private fun parseCloudflareTrace(text: String): ExitConnectionInfo {
            val values = text.lineSequence()
                .mapNotNull { line ->
                    val separator = line.indexOf('=')
                    if (separator <= 0) null
                    else line.substring(0, separator).trim() to
                        line.substring(separator + 1).trim()
                }
                .toMap()
            val ip = values["ip"].orEmpty()
            if (ip.isBlank()) {
                throw IllegalStateException("trace returned an empty IP")
            }
            val code = values["loc"].orEmpty()
            return ExitConnectionInfo(
                ip = ip,
                country = code,
                countryCode = code,
                flagEmoji = flagEmoji(code),
                isp = "",
                city = ""
            )
        }

        private fun flagEmoji(countryCode: String): String {
            val code = countryCode
                .trim()
                .uppercase(Locale.US)
            if (code.length != 2 || code.any { it !in 'A'..'Z' }) return ""
            return buildString {
                code.forEach { letter ->
                    appendCodePoint(
                        REGIONAL_INDICATOR_A + (letter.code - 'A'.code)
                    )
                }
            }
        }

        private const val PROVIDER_TIMEOUT_MS = 5_500
        private const val MAX_RESPONSE_SIZE = 128 * 1024
        private const val REGIONAL_INDICATOR_A = 0x1F1E6
    }
}
