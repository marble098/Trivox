package com.trivox.client.config

import com.trivox.client.data.AppSettings
import com.trivox.client.data.ConfigProfile
import org.json.JSONObject
import java.util.Locale

/**
 * Produces a bounded Xray Freedom fragment plan. Fragment is deliberately not
 * injected into WireGuard, Hysteria/mKCP, or proxy-chain hops.
 */
internal object XrayFragmentPolicy {
    data class Plan(
        val packets: String,
        val length: String,
        val interval: String,
        val reason: String
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("packets", packets)
            .put("length", length)
            .put("interval", interval)
    }

    fun resolve(profile: ConfigProfile, settings: AppSettings): Plan? {
        if (!settings.fragmentEnabled) return null
        val protocol = profile.protocol.lowercase(Locale.ROOT)
        if (protocol in setOf("wireguard", "hysteria", "hysteria2", "hy2", "chain")) {
            return null
        }

        val outbound = runCatching { JSONObject(profile.outboundJson) }.getOrNull()
            ?: return null
        if (outbound.has("proxySettings")) return null
        val stream = outbound.optJSONObject("streamSettings")
        val transport = stream?.optString("network", "tcp")
            ?.lowercase(Locale.ROOT)
            ?.let { if (it == "websocket") "ws" else if (it == "splithttp") "xhttp" else it }
            ?: "tcp"
        if (transport in setOf("hysteria", "mkcp", "kcp")) return null
        val security = stream?.optString("security", "none")
            ?.lowercase(Locale.ROOT)
            ?: "none"

        if (settings.fragmentAuto) {
            if (security !in setOf("tls", "reality")) return null
            val length = when (transport) {
                "ws", "xhttp", "httpupgrade", "grpc" -> "80-160"
                else -> "100-200"
            }
            return Plan(
                packets = "tlshello",
                length = length,
                interval = "2-5",
                reason = "auto:$transport:$security"
            )
        }

        val packets = normalizePackets(settings.fragmentPackets) ?: return null
        if (packets == "tlshello" && security !in setOf("tls", "reality")) return null
        return Plan(
            packets = packets,
            length = normalizeRange(settings.fragmentLength, 1, 1440, "100-200"),
            interval = normalizeRange(settings.fragmentInterval, 0, 1000, "2-5"),
            reason = "manual:$transport:$security"
        )
    }

    internal fun normalizePackets(value: String): String? {
        val text = value.trim().lowercase(Locale.ROOT)
        if (text == "tlshello") return text
        return normalizeRangeOrNull(text, 1, 20)
    }

    private fun normalizeRange(
        value: String,
        minimum: Int,
        maximum: Int,
        fallback: String
    ): String = normalizeRangeOrNull(value.trim(), minimum, maximum) ?: fallback

    private fun normalizeRangeOrNull(value: String, minimum: Int, maximum: Int): String? {
        val match = Regex("^(\\d{1,4})(?:-(\\d{1,4}))?$").matchEntire(value) ?: return null
        val first = match.groupValues[1].toIntOrNull() ?: return null
        val second = match.groupValues[2].takeIf(String::isNotBlank)?.toIntOrNull() ?: first
        if (first !in minimum..maximum || second !in minimum..maximum || first > second) return null
        return if (first == second) first.toString() else "$first-$second"
    }
}
