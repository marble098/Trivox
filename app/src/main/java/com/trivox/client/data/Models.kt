package com.trivox.client.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class ConnectionMode { PROXY, VPN }
enum class ConnectionState { DISCONNECTED, PREPARING, CONNECTING, CONNECTED, RECONNECTING, STOPPING, ERROR }
enum class TestStatus { UNTESTED, TESTING, ALIVE, DEAD, ERROR }
enum class DnsMode { IMPORTED, TRIVOX_DEFAULT, CUSTOM, SYSTEM, DIRECT, THROUGH_PROXY }
enum class AppRoutingMode { ALL, ALLOW_SELECTED, BYPASS_SELECTED }

enum class PingMethod { TCP_CONNECT, XRAY_HTTP;
    companion object {
        fun fromStored(
            value: String?,
            fallback: PingMethod = XRAY_HTTP
        ): PingMethod =
            entries.firstOrNull {
                it.name.equals(value, true)
            } ?: fallback
    }
}
enum class ProfileSortMode { SMART, LOWEST_LATENCY, NAME, LAST_TESTED, GROUP;
    companion object { fun fromStored(value: String?): ProfileSortMode = entries.firstOrNull { it.name.equals(value, true) } ?: SMART }
}
enum class ThemeMode { LIGHT, DARK;
    companion object {
        fun fromStored(
            value: String?,
            legacyDark: Boolean
        ): ThemeMode =
            when (value?.trim()?.uppercase()) {
                LIGHT.name -> LIGHT
                DARK.name, "NEON" -> DARK
                else -> if (legacyDark) DARK else LIGHT
            }
    }
}

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
    var latencyJitterMs: Long? = null,
    var latencySuccessRatio: Double = 0.0,
    var latencyMethod: String = "",
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
        .put("id", id).put("name", name).put("protocol", protocol)
        .put("server", server).put("port", port).put("raw", raw)
        .put("outboundJson", outboundJson).put("originalDnsJson", originalDnsJson)
        .put("enabled", enabled).put("favorite", favorite).put("group", group)
        .put("subscriptionId", subscriptionId).put("latencyMs", latencyMs)
        .put("latencyJitterMs", latencyJitterMs).put("latencySuccessRatio", latencySuccessRatio)
        .put("latencyMethod", latencyMethod).put("testStatus", testStatus.name)
        .put("lastTestAt", lastTestAt).put("lastSessionMs", lastSessionMs)
        .put("cumulativeSessionMs", cumulativeSessionMs).put("exitIp", exitIp)
        .put("exitCountry", exitCountry).put("exitCountryCode", exitCountryCode)
        .put("exitFlag", exitFlag).put("exitIsp", exitIsp).put("lastExitCheckAt", lastExitCheckAt)

    companion object {
        fun fromJson(json: JSONObject) = ConfigProfile(
            id = json.optString("id", UUID.randomUUID().toString()), name = json.optString("name", "Unnamed"),
            protocol = json.optString("protocol", "unknown"), server = json.optString("server"), port = json.optInt("port"),
            raw = json.optString("raw"), outboundJson = json.getString("outboundJson"),
            originalDnsJson = json.optString("originalDnsJson").ifBlank { null }, enabled = json.optBoolean("enabled", true),
            favorite = json.optBoolean("favorite"), group = json.optString("group", "Default"),
            subscriptionId = json.optString("subscriptionId").ifBlank { null },
            latencyMs = if (json.isNull("latencyMs")) null else json.optLong("latencyMs"),
            latencyJitterMs = if (json.isNull("latencyJitterMs")) null else json.optLong("latencyJitterMs"),
            latencySuccessRatio = json.optDouble("latencySuccessRatio", 0.0).coerceIn(0.0, 1.0),
            latencyMethod = json.optString("latencyMethod"),
            testStatus = runCatching { TestStatus.valueOf(json.optString("testStatus")) }.getOrDefault(TestStatus.UNTESTED).let { if (it == TestStatus.TESTING) TestStatus.UNTESTED else it },
            lastTestAt = json.optLong("lastTestAt"), lastSessionMs = json.optLong("lastSessionMs"),
            cumulativeSessionMs = json.optLong("cumulativeSessionMs"), exitIp = json.optString("exitIp"),
            exitCountry = json.optString("exitCountry"), exitCountryCode = json.optString("exitCountryCode"),
            exitFlag = json.optString("exitFlag"), exitIsp = json.optString("exitIsp"), lastExitCheckAt = json.optLong("lastExitCheckAt")
        )
    }
}

data class SubscriptionSource(
    val id: String = UUID.randomUUID().toString(), var name: String, var url: String,
    var enabled: Boolean = true, var lastSuccessAt: Long = 0, var lastError: String = ""
) {
    fun toJson() = JSONObject().put("id", id).put("name", name).put("url", url).put("enabled", enabled).put("lastSuccessAt", lastSuccessAt).put("lastError", lastError)
    companion object { fun fromJson(json: JSONObject) = SubscriptionSource(
        id = json.optString("id", UUID.randomUUID().toString()), name = json.optString("name", "Subscription"),
        url = json.getString("url"), enabled = json.optBoolean("enabled", true), lastSuccessAt = json.optLong("lastSuccessAt"), lastError = json.optString("lastError")) }
}

data class AppSettings(
    var mode: ConnectionMode = ConnectionMode.VPN,
    var socksPort: Int = DEFAULT_MIXED_PORT,
    var httpPort: Int = DEFAULT_MIXED_PORT,
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
    var pingMethod: PingMethod = PingMethod.XRAY_HTTP,
    var sortMode: ProfileSortMode = ProfileSortMode.SMART,
    var darkMode: Boolean = false,
    var themeMode: ThemeMode = ThemeMode.LIGHT,
    var localProxyInVpn: Boolean = true,
    var autoUpdateCheck: Boolean = true,
    var testUrl: String = DEFAULT_TEST_URL,
    var testAttempts: Int = 3
) {
    fun normalize(): AppSettings {
        if (socksPort == LEGACY_SOCKS_PORT || socksPort !in 1..65535) socksPort = DEFAULT_MIXED_PORT
        httpPort = socksPort
        testAttempts = testAttempts.coerceIn(2, 5)
        if (testUrl.isBlank() || testUrl == LEGACY_TEST_URL) testUrl = DEFAULT_TEST_URL
        darkMode = themeMode == ThemeMode.DARK
        return this
    }
    fun toJson() = JSONObject().put("mode", mode.name).put("socksPort", socksPort).put("httpPort", socksPort)
        .put("mtu", mtu).put("ipv6", ipv6).put("dnsMode", dnsMode.name).put("customDns", JSONArray(customDns))
        .put("appRoutingMode", appRoutingMode.name).put("routedPackages", JSONArray(routedPackages.toList()))
        .put("showSystemApps", showSystemApps).put("reconnectOnNetworkChange", reconnectOnNetworkChange)
        .put("reconnectOnBoot", reconnectOnBoot).put("blocking", blocking).put("gridMode", gridMode)
        .put("pingMethod", pingMethod.name).put("sortMode", sortMode.name).put("darkMode", darkMode)
        .put("themeMode", themeMode.name).put("localProxyInVpn", localProxyInVpn)
        .put("autoUpdateCheck", autoUpdateCheck).put("testUrl", testUrl).put("testAttempts", testAttempts)

    companion object {
        const val DEFAULT_MIXED_PORT = 10202
        const val DEFAULT_TEST_URL = "http://www.google.com/gen_204"
        private const val LEGACY_SOCKS_PORT = 10808
        private const val LEGACY_TEST_URL = "https://cp.cloudflare.com/"
        fun fromJson(json: JSONObject): AppSettings {
            fun strings(key: String): List<String> = json.optJSONArray(key)?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).takeIf(String::isNotBlank) } } ?: emptyList()
            val legacyDark = json.optBoolean("darkMode", false)
            return AppSettings(
                mode = runCatching { ConnectionMode.valueOf(json.optString("mode")) }.getOrDefault(ConnectionMode.VPN),
                socksPort = json.optInt("socksPort", DEFAULT_MIXED_PORT), httpPort = json.optInt("httpPort", DEFAULT_MIXED_PORT),
                mtu = json.optInt("mtu", 1500), ipv6 = json.optBoolean("ipv6", true),
                dnsMode = runCatching { DnsMode.valueOf(json.optString("dnsMode")) }.getOrDefault(DnsMode.TRIVOX_DEFAULT),
                customDns = strings("customDns"), appRoutingMode = runCatching { AppRoutingMode.valueOf(json.optString("appRoutingMode")) }.getOrDefault(AppRoutingMode.ALL),
                routedPackages = strings("routedPackages").toSet(), showSystemApps = json.optBoolean("showSystemApps"),
                reconnectOnNetworkChange = json.optBoolean("reconnectOnNetworkChange", true), reconnectOnBoot = json.optBoolean("reconnectOnBoot"),
                blocking = json.optBoolean("blocking", true), gridMode = json.optBoolean("gridMode"),
                pingMethod = PingMethod.fromStored(json.optString("pingMethod")), sortMode = ProfileSortMode.fromStored(json.optString("sortMode")),
                darkMode = legacyDark, themeMode = ThemeMode.fromStored(json.optString("themeMode"), legacyDark),
                localProxyInVpn = json.optBoolean("localProxyInVpn", true), autoUpdateCheck = json.optBoolean("autoUpdateCheck", true),
                testUrl = json.optString("testUrl", DEFAULT_TEST_URL), testAttempts = json.optInt("testAttempts", 3).coerceIn(2, 5)
            ).normalize()
        }
    }
}

data class PingResult(
    val method: String, val success: Boolean, val latencyMs: Long?, val jitterMs: Long?,
    val successRatio: Double, val resolvedIp: String?, val timestamp: Long = System.currentTimeMillis(), val errorCategory: String? = null
)
