package com.trivox.client.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class ConnectionMode { PROXY, VPN }
enum class ConnectionState { DISCONNECTED, PREPARING, CONNECTING, CONNECTED, RECONNECTING, STOPPING, ERROR }
enum class TestStatus { UNTESTED, TESTING, ALIVE, DEAD, ERROR }
enum class DnsMode { IMPORTED, TRIVOX_DEFAULT, CUSTOM, SYSTEM, DIRECT, THROUGH_PROXY }
enum class AppRoutingMode { ALL, ALLOW_SELECTED, BYPASS_SELECTED }

enum class RealDelayProfile {
    TURBO, BALANCED, ACCURATE, CUSTOM;
    companion object { fun fromStored(value: String?): RealDelayProfile = entries.firstOrNull { it.name.equals(value, true) } ?: BALANCED }
}

enum class SubscriptionKind {
    URL, NORDVPN;
    companion object { fun fromStored(value: String?): SubscriptionKind = entries.firstOrNull { it.name.equals(value, true) } ?: URL }
}

enum class PingMethod {
    TCP_CONNECT, XRAY_HTTP;
    companion object {
        fun fromStored(value: String?, fallback: PingMethod = XRAY_HTTP): PingMethod = entries.firstOrNull { it.name.equals(value, true) } ?: fallback
    }
}

enum class ProfileSortMode {
    SMART, LOWEST_LATENCY, NAME, LAST_TESTED, GROUP;
    companion object { fun fromStored(value: String?): ProfileSortMode = entries.firstOrNull { it.name.equals(value, true) } ?: SMART }
}

enum class ThemeMode {
    LIGHT, DARK;
    companion object {
        fun fromStored(value: String?, legacyDark: Boolean): ThemeMode = when (value?.trim()?.uppercase()) {
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
    val probeServer: String = "",
    val probePort: Int = 0,
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
    var tcpLatencyMs: Long? = null,
    var tcpLatencyJitterMs: Long? = null,
    var tcpSuccessRatio: Double = 0.0,
    var tcpTestStatus: TestStatus = TestStatus.UNTESTED,
    var tcpLastTestAt: Long = 0,
    var realLatencyMs: Long? = null,
    var realLatencyJitterMs: Long? = null,
    var realSuccessRatio: Double = 0.0,
    var realTestStatus: TestStatus = TestStatus.UNTESTED,
    var realLastTestAt: Long = 0,
    var lastSessionMs: Long = 0,
    var cumulativeSessionMs: Long = 0,
    var exitIp: String = "",
    var exitCountry: String = "",
    var exitCountryCode: String = "",
    var exitFlag: String = "",
    var exitIsp: String = "",
    var lastExitCheckAt: Long = 0
) {
    fun latencyFor(method: PingMethod): Long? = when (method) {
        PingMethod.TCP_CONNECT -> tcpLatencyMs
        PingMethod.XRAY_HTTP -> realLatencyMs
    }

    fun statusFor(method: PingMethod): TestStatus = when (method) {
        PingMethod.TCP_CONNECT -> tcpTestStatus
        PingMethod.XRAY_HTTP -> realTestStatus
    }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("protocol", protocol)
        .put("server", server)
        .put("port", port)
        .put("raw", raw)
        .put("outboundJson", outboundJson)
        .put("originalDnsJson", originalDnsJson)
        .put("probeServer", probeServer)
        .put("probePort", probePort)
        .put("enabled", enabled)
        .put("favorite", favorite)
        .put("group", group)
        .put("subscriptionId", subscriptionId)
        .put("latencyMs", latencyMs)
        .put("latencyJitterMs", latencyJitterMs)
        .put("latencySuccessRatio", latencySuccessRatio)
        .put("latencyMethod", latencyMethod)
        .put("testStatus", testStatus.name)
        .put("lastTestAt", lastTestAt)
        .put("tcpLatencyMs", tcpLatencyMs)
        .put("tcpLatencyJitterMs", tcpLatencyJitterMs)
        .put("tcpSuccessRatio", tcpSuccessRatio)
        .put("tcpTestStatus", tcpTestStatus.name)
        .put("tcpLastTestAt", tcpLastTestAt)
        .put("realLatencyMs", realLatencyMs)
        .put("realLatencyJitterMs", realLatencyJitterMs)
        .put("realSuccessRatio", realSuccessRatio)
        .put("realTestStatus", realTestStatus.name)
        .put("realLastTestAt", realLastTestAt)
        .put("lastSessionMs", lastSessionMs)
        .put("cumulativeSessionMs", cumulativeSessionMs)
        .put("exitIp", exitIp)
        .put("exitCountry", exitCountry)
        .put("exitCountryCode", exitCountryCode)
        .put("exitFlag", exitFlag)
        .put("exitIsp", exitIsp)
        .put("lastExitCheckAt", lastExitCheckAt)

    companion object {
        fun fromJson(json: JSONObject): ConfigProfile {
            fun optionalLong(key: String): Long? = if (!json.has(key) || json.isNull(key)) null else json.optLong(key)
            fun storedStatus(key: String, fallback: TestStatus): TestStatus = runCatching { TestStatus.valueOf(json.optString(key)) }.getOrDefault(fallback).let { if (it == TestStatus.TESTING) TestStatus.UNTESTED else it }
            val legacyLatency = optionalLong("latencyMs")
            val legacyJitter = optionalLong("latencyJitterMs")
            val legacyRatio = json.optDouble("latencySuccessRatio", 0.0).coerceIn(0.0, 1.0)
            val legacyMethod = PingMethod.fromStored(json.optString("latencyMethod"), PingMethod.TCP_CONNECT)
            val legacyStatus = storedStatus("testStatus", TestStatus.UNTESTED)
            val legacyAt = json.optLong("lastTestAt")
            return ConfigProfile(
                id = json.optString("id", UUID.randomUUID().toString()),
                name = json.optString("name", "Unnamed"),
                protocol = json.optString("protocol", "unknown"),
                server = json.optString("server"),
                port = json.optInt("port"),
                raw = json.optString("raw"),
                outboundJson = json.getString("outboundJson"),
                originalDnsJson = json.optString("originalDnsJson").ifBlank { null },
                probeServer = json.optString("probeServer"),
                probePort = json.optInt("probePort"),
                enabled = json.optBoolean("enabled", true),
                favorite = json.optBoolean("favorite"),
                group = json.optString("group", "Default"),
                subscriptionId = json.optString("subscriptionId").ifBlank { null },
                latencyMs = legacyLatency,
                latencyJitterMs = legacyJitter,
                latencySuccessRatio = legacyRatio,
                latencyMethod = json.optString("latencyMethod"),
                testStatus = legacyStatus,
                lastTestAt = legacyAt,
                tcpLatencyMs = optionalLong("tcpLatencyMs") ?: legacyLatency.takeIf { legacyMethod == PingMethod.TCP_CONNECT },
                tcpLatencyJitterMs = optionalLong("tcpLatencyJitterMs") ?: legacyJitter.takeIf { legacyMethod == PingMethod.TCP_CONNECT },
                tcpSuccessRatio = if (json.has("tcpSuccessRatio")) json.optDouble("tcpSuccessRatio", 0.0).coerceIn(0.0, 1.0) else if (legacyMethod == PingMethod.TCP_CONNECT) legacyRatio else 0.0,
                tcpTestStatus = storedStatus("tcpTestStatus", if (legacyMethod == PingMethod.TCP_CONNECT) legacyStatus else TestStatus.UNTESTED),
                tcpLastTestAt = json.optLong("tcpLastTestAt", if (legacyMethod == PingMethod.TCP_CONNECT) legacyAt else 0L),
                realLatencyMs = optionalLong("realLatencyMs") ?: legacyLatency.takeIf { legacyMethod == PingMethod.XRAY_HTTP },
                realLatencyJitterMs = optionalLong("realLatencyJitterMs") ?: legacyJitter.takeIf { legacyMethod == PingMethod.XRAY_HTTP },
                realSuccessRatio = if (json.has("realSuccessRatio")) json.optDouble("realSuccessRatio", 0.0).coerceIn(0.0, 1.0) else if (legacyMethod == PingMethod.XRAY_HTTP) legacyRatio else 0.0,
                realTestStatus = storedStatus("realTestStatus", if (legacyMethod == PingMethod.XRAY_HTTP) legacyStatus else TestStatus.UNTESTED),
                realLastTestAt = json.optLong("realLastTestAt", if (legacyMethod == PingMethod.XRAY_HTTP) legacyAt else 0L),
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
}

data class SubscriptionSource(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    var url: String,
    var enabled: Boolean = true,
    var lastSuccessAt: Long = 0,
    var lastError: String = "",
    var kind: SubscriptionKind = SubscriptionKind.URL,
    var secretAlias: String = ""
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("url", url)
        .put("enabled", enabled)
        .put("lastSuccessAt", lastSuccessAt)
        .put("lastError", lastError)
        .put("kind", kind.name)
        .put("secretAlias", secretAlias)

    companion object {
        fun fromJson(json: JSONObject): SubscriptionSource = SubscriptionSource(
            id = json.optString("id", UUID.randomUUID().toString()),
            name = json.optString("name", "Subscription"),
            url = json.optString("url"),
            enabled = json.optBoolean("enabled", true),
            lastSuccessAt = json.optLong("lastSuccessAt"),
            lastError = json.optString("lastError"),
            kind = SubscriptionKind.fromStored(json.optString("kind")),
            secretAlias = json.optString("secretAlias")
        )
    }
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
    var livePingMethod: PingMethod = PingMethod.XRAY_HTTP,
    var livePingEnabled: Boolean = true,
    var livePingIntervalSeconds: Int = 8,
    var hideIpOnMain: Boolean = false,
    var sortMode: ProfileSortMode = ProfileSortMode.SMART,
    var darkMode: Boolean = false,
    var themeMode: ThemeMode = ThemeMode.LIGHT,
    var localProxyInVpn: Boolean = true,
    var autoUpdateCheck: Boolean = true,
    var testUrl: String = DEFAULT_TEST_URL,
    var testAttempts: Int = 3,
    var realDelayProfile: RealDelayProfile = RealDelayProfile.BALANCED,
    var realDelayGroupSize: Int = 8,
    var realDelayWorkers: Int = 3,
    var realDelayProbeTimeoutMs: Int = 3_600,
    var realDelayStartGraceMs: Int = 80,
    var realDelayTargetCount: Int = 2,
    var realDelayRequiredProofs: Int = 2,
    var adaptiveHandshake: Boolean = true,
    var networkTuningEnabled: Boolean = true,
    var tcpFastOpen: Boolean = false,
    var tcpKeepAliveIdleSeconds: Int = 60,
    var tcpKeepAliveIntervalSeconds: Int = 15,
    var tcpUserTimeoutMs: Int = 15_000,
    var networkBufferSizeKb: Int = 64,
    var wireGuardMtu: Int = 1360,
    var wireGuardWorkers: Int = 2,
    var wireGuardKeepAliveSeconds: Int = 25,
    var wireGuardHandshakeTimeoutMs: Int = 18_000,
    var wireGuardDomainStrategy: String = DEFAULT_WIREGUARD_DOMAIN_STRATEGY
) {
    fun normalize(): AppSettings {
        if (socksPort == LEGACY_SOCKS_PORT || socksPort !in 1..65535) socksPort = DEFAULT_MIXED_PORT
        httpPort = socksPort
        mtu = mtu.coerceIn(576, 9000)
        testAttempts = testAttempts.coerceIn(2, 5)
        realDelayGroupSize = realDelayGroupSize.coerceIn(2, 16)
        realDelayWorkers = realDelayWorkers.coerceIn(1, 6)
        realDelayProbeTimeoutMs = realDelayProbeTimeoutMs.coerceIn(1_500, 10_000)
        realDelayStartGraceMs = realDelayStartGraceMs.coerceIn(0, 1_000)
        realDelayTargetCount = realDelayTargetCount.coerceIn(1, 4)
        realDelayRequiredProofs = realDelayRequiredProofs.coerceIn(1, realDelayTargetCount)
        livePingIntervalSeconds = livePingIntervalSeconds.coerceIn(3, 300)
        tcpKeepAliveIdleSeconds = tcpKeepAliveIdleSeconds.coerceIn(0, 3600)
        tcpKeepAliveIntervalSeconds = tcpKeepAliveIntervalSeconds.coerceIn(0, 600)
        tcpUserTimeoutMs = tcpUserTimeoutMs.coerceIn(0, 120_000)
        networkBufferSizeKb = networkBufferSizeKb.coerceIn(8, 256)
        wireGuardMtu = wireGuardMtu.coerceIn(576, 9000)
        wireGuardWorkers = wireGuardWorkers.coerceIn(1, 8)
        wireGuardKeepAliveSeconds = wireGuardKeepAliveSeconds.coerceIn(0, 300)
        wireGuardHandshakeTimeoutMs = wireGuardHandshakeTimeoutMs.coerceIn(5_000, 60_000)
        wireGuardDomainStrategy = wireGuardDomainStrategy.takeIf { it in WIREGUARD_DOMAIN_STRATEGIES } ?: DEFAULT_WIREGUARD_DOMAIN_STRATEGY
        if (testUrl.isBlank() || testUrl == LEGACY_TEST_URL || testUrl == LEGACY_GOOGLE_TEST_URL) testUrl = DEFAULT_TEST_URL
        darkMode = themeMode == ThemeMode.DARK
        return this
    }

    fun toJson(): JSONObject = JSONObject()
        .put("mode", mode.name)
        .put("socksPort", socksPort)
        .put("httpPort", socksPort)
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
        .put("pingMethod", pingMethod.name)
        .put("livePingMethod", livePingMethod.name)
        .put("livePingEnabled", livePingEnabled)
        .put("livePingIntervalSeconds", livePingIntervalSeconds)
        .put("hideIpOnMain", hideIpOnMain)
        .put("sortMode", sortMode.name)
        .put("darkMode", darkMode)
        .put("themeMode", themeMode.name)
        .put("localProxyInVpn", localProxyInVpn)
        .put("autoUpdateCheck", autoUpdateCheck)
        .put("testUrl", testUrl)
        .put("testAttempts", testAttempts)
        .put("realDelayProfile", realDelayProfile.name)
        .put("realDelayGroupSize", realDelayGroupSize)
        .put("realDelayWorkers", realDelayWorkers)
        .put("realDelayProbeTimeoutMs", realDelayProbeTimeoutMs)
        .put("realDelayStartGraceMs", realDelayStartGraceMs)
        .put("realDelayTargetCount", realDelayTargetCount)
        .put("realDelayRequiredProofs", realDelayRequiredProofs)
        .put("adaptiveHandshake", adaptiveHandshake)
        .put("networkTuningEnabled", networkTuningEnabled)
        .put("tcpFastOpen", tcpFastOpen)
        .put("tcpKeepAliveIdleSeconds", tcpKeepAliveIdleSeconds)
        .put("tcpKeepAliveIntervalSeconds", tcpKeepAliveIntervalSeconds)
        .put("tcpUserTimeoutMs", tcpUserTimeoutMs)
        .put("networkBufferSizeKb", networkBufferSizeKb)
        .put("wireGuardMtu", wireGuardMtu)
        .put("wireGuardWorkers", wireGuardWorkers)
        .put("wireGuardKeepAliveSeconds", wireGuardKeepAliveSeconds)
        .put("wireGuardHandshakeTimeoutMs", wireGuardHandshakeTimeoutMs)
        .put("wireGuardDomainStrategy", wireGuardDomainStrategy)

    companion object {
        const val DEFAULT_MIXED_PORT = 10202
        const val DEFAULT_TEST_URL = "https://cp.cloudflare.com/generate_204"
        const val DEFAULT_WIREGUARD_DOMAIN_STRATEGY = "ForceIP"
        val WIREGUARD_DOMAIN_STRATEGIES = listOf("ForceIP", "ForceIPv4", "ForceIPv6", "ForceIPv4v6", "ForceIPv6v4", "AsIs")
        private const val LEGACY_SOCKS_PORT = 10808
        private const val LEGACY_TEST_URL = "https://cp.cloudflare.com/"
        private const val LEGACY_GOOGLE_TEST_URL = "http://www.google.com/gen_204"

        fun fromJson(json: JSONObject): AppSettings {
            fun strings(key: String): List<String> = json.optJSONArray(key)?.let { array ->
                (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
            } ?: emptyList()
            val legacyDark = json.optBoolean("darkMode", false)
            val legacyPingMethod = PingMethod.fromStored(json.optString("pingMethod"))
            return AppSettings(
                mode = runCatching { ConnectionMode.valueOf(json.optString("mode")) }.getOrDefault(ConnectionMode.VPN),
                socksPort = json.optInt("socksPort", DEFAULT_MIXED_PORT),
                httpPort = json.optInt("httpPort", DEFAULT_MIXED_PORT),
                mtu = json.optInt("mtu", 1500),
                ipv6 = json.optBoolean("ipv6", true),
                dnsMode = runCatching { DnsMode.valueOf(json.optString("dnsMode")) }.getOrDefault(DnsMode.TRIVOX_DEFAULT),
                customDns = strings("customDns"),
                appRoutingMode = runCatching { AppRoutingMode.valueOf(json.optString("appRoutingMode")) }.getOrDefault(AppRoutingMode.ALL),
                routedPackages = strings("routedPackages").toSet(),
                showSystemApps = json.optBoolean("showSystemApps"),
                reconnectOnNetworkChange = json.optBoolean("reconnectOnNetworkChange", true),
                reconnectOnBoot = json.optBoolean("reconnectOnBoot"),
                blocking = json.optBoolean("blocking", true),
                gridMode = json.optBoolean("gridMode"),
                pingMethod = legacyPingMethod,
                livePingMethod = PingMethod.fromStored(json.optString("livePingMethod"), legacyPingMethod),
                livePingEnabled = json.optBoolean("livePingEnabled", true),
                livePingIntervalSeconds = json.optInt("livePingIntervalSeconds", 8),
                hideIpOnMain = json.optBoolean("hideIpOnMain", false),
                sortMode = ProfileSortMode.fromStored(json.optString("sortMode")),
                darkMode = legacyDark,
                themeMode = ThemeMode.fromStored(json.optString("themeMode"), legacyDark),
                localProxyInVpn = json.optBoolean("localProxyInVpn", true),
                autoUpdateCheck = json.optBoolean("autoUpdateCheck", true),
                testUrl = json.optString("testUrl", DEFAULT_TEST_URL),
                testAttempts = json.optInt("testAttempts", 3),
                realDelayProfile = RealDelayProfile.fromStored(json.optString("realDelayProfile")),
                realDelayGroupSize = json.optInt("realDelayGroupSize", 8),
                realDelayWorkers = json.optInt("realDelayWorkers", 3),
                realDelayProbeTimeoutMs = json.optInt("realDelayProbeTimeoutMs", 3_600),
                realDelayStartGraceMs = json.optInt("realDelayStartGraceMs", 80),
                realDelayTargetCount = json.optInt("realDelayTargetCount", 2),
                realDelayRequiredProofs = json.optInt("realDelayRequiredProofs", 2),
                adaptiveHandshake = json.optBoolean("adaptiveHandshake", true),
                networkTuningEnabled = json.optBoolean("networkTuningEnabled", true),
                tcpFastOpen = json.optBoolean("tcpFastOpen", false),
                tcpKeepAliveIdleSeconds = json.optInt("tcpKeepAliveIdleSeconds", 60),
                tcpKeepAliveIntervalSeconds = json.optInt("tcpKeepAliveIntervalSeconds", 15),
                tcpUserTimeoutMs = json.optInt("tcpUserTimeoutMs", 15_000),
                networkBufferSizeKb = json.optInt("networkBufferSizeKb", 64),
                wireGuardMtu = json.optInt("wireGuardMtu", 1360),
                wireGuardWorkers = json.optInt("wireGuardWorkers", 2),
                wireGuardKeepAliveSeconds = json.optInt("wireGuardKeepAliveSeconds", 25),
                wireGuardHandshakeTimeoutMs = json.optInt("wireGuardHandshakeTimeoutMs", 18_000),
                wireGuardDomainStrategy = json.optString("wireGuardDomainStrategy", DEFAULT_WIREGUARD_DOMAIN_STRATEGY)
            ).normalize()
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
