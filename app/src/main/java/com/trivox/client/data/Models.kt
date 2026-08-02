package com.trivox.client.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class ConnectionMode { PROXY, VPN }
enum class ConnectionState { DISCONNECTED, PREPARING, CONNECTING, CONNECTED, RECONNECTING, STOPPING, ERROR }
enum class TestStatus { UNTESTED, TESTING, ALIVE, DEAD, ERROR }
enum class DnsMode { IMPORTED, TRIVOX_DEFAULT, CUSTOM, SYSTEM, DIRECT, THROUGH_PROXY }
enum class AppRoutingMode { ALL, ALLOW_SELECTED, BYPASS_SELECTED }

data class ConfigProfile(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    val protocol: String,
    val server: String,
    val port: Int,
    val raw: String,
    val outboundJson: String,
    val originalDnsJson: String? = null,
    var enabled: Boolean = true,
    var favorite: Boolean = false,
    var group: String = "Default",
    var subscriptionId: String? = null,
    var latencyMs: Long? = null,
    var testStatus: TestStatus = TestStatus.UNTESTED,
    var lastTestAt: Long = 0,
    var lastSessionMs: Long = 0,
    var cumulativeSessionMs: Long = 0,
    var exitIp: String = "",
    var exitCountry: String = "",
    var exitCountryCode: String = "",
    var exitFlag: String = "",
    var exitIsp: String = "",
    var lastExitCheckAt: Long = 0
) {
    fun toJson() = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("protocol", protocol)
        .put("server", server)
        .put("port", port)
        .put("raw", raw)
        .put("outboundJson", outboundJson)
        .put("originalDnsJson", originalDnsJson)
        .put("enabled", enabled)
        .put("favorite", favorite)
        .put("group", group)
        .put("subscriptionId", subscriptionId)
        .put("latencyMs", latencyMs)
        .put("testStatus", testStatus.name)
        .put("lastTestAt", lastTestAt)
        .put("lastSessionMs", lastSessionMs)
        .put("cumulativeSessionMs", cumulativeSessionMs)
        .put("exitIp", exitIp)
        .put("exitCountry", exitCountry)
        .put("exitCountryCode", exitCountryCode)
        .put("exitFlag", exitFlag)
        .put("exitIsp", exitIsp)
        .put("lastExitCheckAt", lastExitCheckAt)

    companion object {
        fun fromJson(json: JSONObject) = ConfigProfile(
            id = json.optString("id", UUID.randomUUID().toString()),
            name = json.optString("name", "Unnamed"),
            protocol = json.optString("protocol", "unknown"),
            server = json.optString("server"),
            port = json.optInt("port"),
            raw = json.optString("raw"),
            outboundJson = json.getString("outboundJson"),
            originalDnsJson = json.optString("originalDnsJson").ifBlank { null },
            enabled = json.optBoolean("enabled", true),
            favorite = json.optBoolean("favorite"),
            group = json.optString("group", "Default"),
            subscriptionId = json.optString("subscriptionId").ifBlank { null },
            latencyMs = if (json.isNull("latencyMs")) null else json.optLong("latencyMs"),
            testStatus = runCatching {
                TestStatus.valueOf(json.optString("testStatus"))
            }.getOrDefault(TestStatus.UNTESTED),
            lastTestAt = json.optLong("lastTestAt"),
            lastSessionMs = json.optLong("lastSessionMs"),
            cumulativeSessionMs = json.optLong("cumulativeSessionMs"),
            exitIp = json.optString("exitIp"),
            exitCountry = json.optString("exitCountry"),
            exitCountryCode = json.optString("exitCountryCode"),
            exitFlag = json.optString("exitFlag"),
            exitIsp = json.optString("exitIsp"),
            lastExitCheckAt = json.optLong("lastExitCheckAt")
        )
    }
}

data class SubscriptionSource(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    var url: String,
    var enabled: Boolean = true,
    var lastSuccessAt: Long = 0,
    var lastError: String = ""
) {
    fun toJson() = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("url", url)
        .put("enabled", enabled)
        .put("lastSuccessAt", lastSuccessAt)
        .put("lastError", lastError)

    companion object {
        fun fromJson(json: JSONObject) = SubscriptionSource(
            id = json.optString("id", UUID.randomUUID().toString()),
            name = json.optString("name", "Subscription"),
            url = json.getString("url"),
            enabled = json.optBoolean("enabled", true),
            lastSuccessAt = json.optLong("lastSuccessAt"),
            lastError = json.optString("lastError")
        )
    }
}

data class AppSettings(
    var mode: ConnectionMode = ConnectionMode.VPN,
    var socksPort: Int = 10808,
    var httpPort: Int = 10809,
    var mtu: Int = 1500,
    var ipv6: Boolean = true,
    var dnsMode: DnsMode = DnsMode.TRIVOX_DEFAULT,
    var customDns: List<String> = emptyList(),
    var appRoutingMode: AppRoutingMode = AppRoutingMode.ALL,
    var routedPackages: Set<String> = emptySet(),
    var showSystemApps: Boolean = false,
    var reconnectOnNetworkChange: Boolean = true,
    var reconnectOnBoot: Boolean = false,
    var blocking: Boolean = true,
    var gridMode: Boolean = false,
    var testUrl: String = "https://cp.cloudflare.com/",
    var testAttempts: Int = 3
) {
    fun toJson() = JSONObject()
        .put("mode", mode.name)
        .put("socksPort", socksPort)
        .put("httpPort", httpPort)
        .put("mtu", mtu)
        .put("ipv6", ipv6)
        .put("dnsMode", dnsMode.name)
        .put("customDns", JSONArray(customDns))
        .put("appRoutingMode", appRoutingMode.name)
        .put("routedPackages", JSONArray(routedPackages.toList()))
        .put("showSystemApps", showSystemApps)
        .put("reconnectOnNetworkChange", reconnectOnNetworkChange)
        .put("reconnectOnBoot", reconnectOnBoot)
        .put("blocking", blocking)
        .put("gridMode", gridMode)
        .put("testUrl", testUrl)
        .put("testAttempts", testAttempts)

    companion object {
        fun fromJson(json: JSONObject): AppSettings {
            fun strings(key: String): List<String> =
                json.optJSONArray(key)?.let { array ->
                    (0 until array.length()).mapNotNull {
                        array.optString(it).takeIf(String::isNotBlank)
                    }
                } ?: emptyList()

            return AppSettings(
                mode = runCatching {
                    ConnectionMode.valueOf(json.optString("mode"))
                }.getOrDefault(ConnectionMode.VPN),
                socksPort = json.optInt("socksPort", 10808),
                httpPort = json.optInt("httpPort", 10809),
                mtu = json.optInt("mtu", 1500),
                ipv6 = json.optBoolean("ipv6", true),
                dnsMode = runCatching {
                    DnsMode.valueOf(json.optString("dnsMode"))
                }.getOrDefault(DnsMode.TRIVOX_DEFAULT),
                customDns = strings("customDns"),
                appRoutingMode = runCatching {
                    AppRoutingMode.valueOf(json.optString("appRoutingMode"))
                }.getOrDefault(AppRoutingMode.ALL),
                routedPackages = strings("routedPackages").toSet(),
                showSystemApps = json.optBoolean("showSystemApps"),
                reconnectOnNetworkChange = json.optBoolean("reconnectOnNetworkChange", true),
                reconnectOnBoot = json.optBoolean("reconnectOnBoot"),
                blocking = json.optBoolean("blocking", true),
                gridMode = json.optBoolean("gridMode"),
                testUrl = json.optString("testUrl", "https://cp.cloudflare.com/"),
                testAttempts = json.optInt("testAttempts", 3).coerceIn(1, 5)
            )
        }
    }
}

data class PingResult(
    val method: String,
    val success: Boolean,
    val latencyMs: Long?,
    val jitterMs: Long?,
    val successRatio: Double,
    val resolvedIp: String?,
    val timestamp: Long = System.currentTimeMillis(),
    val errorCategory: String? = null
)
