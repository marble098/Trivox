package com.trivox.client.network

import com.trivox.client.BuildConfig
import com.trivox.client.data.AppSettings
import com.trivox.client.data.ConnectionMode
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
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
        val proxy =
            if (settings.mode == ConnectionMode.PROXY) {
                Proxy(
                    Proxy.Type.SOCKS,
                    InetSocketAddress("127.0.0.1", settings.socksPort)
                )
            } else {
                Proxy.NO_PROXY
            }

        val connection = URL(API_URL).openConnection(proxy) as HttpsURLConnection
        connection.connectTimeout = 7_000
        connection.readTimeout = 7_000
        connection.instanceFollowRedirects = false
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty(
            "User-Agent",
            "Trivox/${BuildConfig.VERSION_NAME} (${BuildConfig.GIT_SHA})"
        )

        return try {
            val status = connection.responseCode
            if (status !in 200..299) {
                throw IllegalStateException("IP service returned HTTP $status")
            }

            val text = connection.inputStream.bufferedReader().use { reader ->
                val value = reader.readText()
                if (value.length > MAX_RESPONSE_SIZE) {
                    throw IllegalStateException("IP service response is too large")
                }
                value
            }

            val json = JSONObject(text)
            if (!json.optBoolean("success", false)) {
                throw IllegalStateException(
                    json.optString("message").ifBlank { "IP lookup failed" }
                )
            }

            val connectionJson = json.optJSONObject("connection")
            val flagJson = json.optJSONObject("flag")

            val ip = json.optString("ip").trim()
            if (ip.isBlank()) {
                throw IllegalStateException("IP service returned an empty IP")
            }

            ExitConnectionInfo(
                ip = ip,
                country = json.optString("country").trim(),
                countryCode = json.optString("country_code").trim(),
                flagEmoji = flagJson?.optString("emoji")?.trim().orEmpty(),
                isp = connectionJson?.optString("isp")?.trim()
                    .orEmpty()
                    .ifBlank {
                        connectionJson?.optString("org")?.trim().orEmpty()
                    },
                city = json.optString("city").trim()
            )
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val API_URL = "https://ipwho.is/"
        private const val MAX_RESPONSE_SIZE = 128 * 1024
    }
}
