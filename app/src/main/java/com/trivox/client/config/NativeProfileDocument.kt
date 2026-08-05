package com.trivox.client.config

import com.trivox.client.data.ConfigProfile
import com.trivox.client.data.CoreId
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.UUID

/** Keeps complete native documents intact instead of pretending they are Xray links. */
object NativeProfileDocument {
    const val MIHOMO_PROTOCOL = "mihomo-native"
    const val SING_BOX_PROTOCOL = "sing-box-native"

    fun remoteProfileUrl(input: String): String? {
        val value = input.trim().removePrefix("\uFEFF")
        val lower = value.lowercase(Locale.ROOT)
        val accepted = lower.startsWith("sing-box://import-remote-profile") ||
            lower.startsWith("sing-box://install-config") ||
            lower.startsWith("clash://") ||
            lower.startsWith("clashmeta://") ||
            lower.startsWith("mihomo://")
        if (!accepted) return null

        val rawQuery = value.substringAfter('?', "").substringBefore('#')
        if (rawQuery.isBlank()) return null
        val values = rawQuery.split('&').mapNotNull { part ->
            val index = part.indexOf('=')
            if (index <= 0) null
            else urlDecode(part.substring(0, index)).lowercase(Locale.ROOT) to
                urlDecode(part.substring(index + 1))
        }.toMap()
        return listOf("url", "link", "subscription", "sub", "config_url", "profile")
            .firstNotNullOfOrNull { values[it] }
            ?.trim()
            ?.takeIf(::isHttpUrl)
    }

    fun embeddedContent(input: String): String? {
        val value = input.trim().removePrefix("\uFEFF")
        val lower = value.lowercase(Locale.ROOT)
        if (!(lower.startsWith("sing-box://") || lower.startsWith("clash://") ||
                lower.startsWith("clashmeta://") || lower.startsWith("mihomo://"))) {
            return null
        }
        val rawQuery = value.substringAfter('?', "").substringBefore('#')
        val values = rawQuery.split('&').mapNotNull { part ->
            val index = part.indexOf('=')
            if (index <= 0) null
            else urlDecode(part.substring(0, index)).lowercase(Locale.ROOT) to
                urlDecode(part.substring(index + 1))
        }.toMap()
        return listOf("config", "content", "data")
            .firstNotNullOfOrNull { values[it] }
            ?.takeIf { it.trimStart().startsWith("{") || looksLikeYaml(it) }
    }

    fun affinity(profile: ConfigProfile): CoreId? = when (profile.protocol.lowercase(Locale.ROOT)) {
        MIHOMO_PROTOCOL -> CoreId.MIHOMO
        SING_BOX_PROTOCOL -> CoreId.SING_BOX
        else -> null
    }

    fun exactRuntimeConfig(profile: ConfigProfile, coreId: CoreId, mixedPort: Int): String? =
        when (affinity(profile)) {
            CoreId.MIHOMO -> {
                require(coreId == CoreId.MIHOMO) {
                    "Complete Mihomo YAML must run with mihomo. Expand its providers before converting it to ${coreId.label}."
                }
                prepareMihomo(profile.raw, mixedPort)
            }
            CoreId.SING_BOX -> {
                require(coreId == CoreId.SING_BOX) {
                    "Complete sing-box JSON must run with sing-box. Convert individual outbounds before using ${coreId.label}."
                }
                prepareSingBox(profile.raw, mixedPort)
            }
            else -> null
        }

    fun parseExactDocumentOrNull(input: String): ConfigProfile? {
        val text = input.trim().removePrefix("\uFEFF")
        if (text.isBlank()) return null
        embeddedContent(text)?.let { return parseExactDocumentOrNull(it) }
        if (text.startsWith("{")) {
            val root = runCatching { JSONObject(text) }.getOrNull() ?: return null
            if (isSingBoxDocument(root)) return singBoxProfile(text, root)
            return null
        }
        if (!looksLikeYaml(text)) return null
        val root = runCatching { MiniYaml.parseMap(text) }.getOrNull() ?: return null
        return if (isMihomoRuntimeDocument(root)) mihomoProfile(text, root) else null
    }

    fun providerUrls(yaml: String, baseUrl: String? = null): List<String> {
        val root = runCatching { MiniYaml.parseMap(yaml) }.getOrNull() ?: return emptyList()
        val providers = MiniYaml.stringMap(root["proxy-providers"])
        return providers.values.mapNotNull { rawProvider ->
            val provider = MiniYaml.stringMap(rawProvider)
            val rawUrl = MiniYaml.string(provider["url"]).trim()
            if (rawUrl.isBlank()) return@mapNotNull null
            resolveHttpUrl(rawUrl, baseUrl)
        }.distinct()
    }

    fun directProxyItems(yaml: String): List<Map<String, Any?>> {
        val root = runCatching { MiniYaml.parseMap(yaml) }.getOrNull() ?: return emptyList()
        return MiniYaml.list(root["proxies"]).mapNotNull { item ->
            val map = MiniYaml.stringMap(item)
            map.takeIf { it.isNotEmpty() }
        }
    }

    fun singBoxOutboundItems(json: String): List<JSONObject> {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
        val outbounds = root.optJSONArray("outbounds") ?: return emptyList()
        return (0 until outbounds.length()).mapNotNull(outbounds::optJSONObject).filter { out ->
            out.optString("type").lowercase(Locale.ROOT) !in NON_PROXY_SING_BOX_TYPES
        }
    }

    private fun mihomoProfile(text: String, root: Map<String, Any?>): ConfigProfile {
        val providers = MiniYaml.stringMap(root["proxy-providers"]).size
        val direct = MiniYaml.list(root["proxies"]).size
        return ConfigProfile(
            id = UUID.randomUUID().toString(),
            name = "Mihomo native (${direct} proxies, ${providers} providers)",
            protocol = MIHOMO_PROTOCOL,
            server = "127.0.0.1",
            port = 0,
            raw = text,
            outboundJson = PLACEHOLDER_OUTBOUND,
            probeServer = "127.0.0.1",
            probePort = 0,
            group = "Mihomo native"
        )
    }

    private fun singBoxProfile(text: String, root: JSONObject): ConfigProfile {
        val outbounds = root.optJSONArray("outbounds") ?: JSONArray()
        val usable = (0 until outbounds.length()).mapNotNull(outbounds::optJSONObject).count { out ->
            out.optString("type").lowercase(Locale.ROOT) !in NON_PROXY_SING_BOX_TYPES
        }
        return ConfigProfile(
            id = UUID.randomUUID().toString(),
            name = "sing-box native ($usable outbounds)",
            protocol = SING_BOX_PROTOCOL,
            server = "127.0.0.1",
            port = 0,
            raw = text,
            outboundJson = PLACEHOLDER_OUTBOUND,
            probeServer = "127.0.0.1",
            probePort = 0,
            group = "sing-box native"
        )
    }

    private fun isSingBoxDocument(root: JSONObject): Boolean {
        val outbounds = root.optJSONArray("outbounds") ?: return false
        if (outbounds.length() == 0) return false
        return (0 until outbounds.length()).mapNotNull(outbounds::optJSONObject).any {
            it.has("type") && !it.has("protocol")
        }
    }

    private fun isMihomoRuntimeDocument(root: Map<String, Any?>): Boolean {
        val strongKeys = setOf(
            "proxy-providers", "proxy-groups", "rules", "rule-providers", "tun", "dns",
            "mixed-port", "socks-port", "redir-port", "tproxy-port", "mode", "sniffer"
        )
        return root.keys.any { it.lowercase(Locale.ROOT) in strongKeys }
    }

    private fun prepareSingBox(raw: String, mixedPort: Int): String {
        val root = JSONObject(raw)
        val old = root.optJSONArray("inbounds") ?: JSONArray()
        val retained = JSONArray()
        for (index in 0 until old.length()) {
            val inbound = old.optJSONObject(index) ?: continue
            val type = inbound.optString("type").lowercase(Locale.ROOT)
            if (type !in LOCAL_INBOUND_TYPES && type != "tun") retained.put(inbound)
        }
        retained.put(
            JSONObject()
                .put("type", "mixed")
                .put("tag", "trivox-mixed-in")
                .put("listen", "127.0.0.1")
                .put("listen_port", mixedPort)
        )
        root.put("inbounds", retained)
        return root.toString(2)
    }

    private fun prepareMihomo(raw: String, mixedPort: Int): String {
        val lines = raw.removePrefix("\uFEFF").lines().toMutableList()
        val filtered = mutableListOf<String>()
        var inTun = false
        var tunIndent = -1
        var tunEnableWritten = false
        var inDns = false
        var dnsIndent = -1

        lines.forEach { original ->
            val trimmed = original.trim()
            val indent = original.indexOfFirst { !it.isWhitespace() }.let { if (it < 0) 0 else it }
            if (indent == 0 && trimmed.isNotBlank() && !trimmed.startsWith("#")) {
                inTun = trimmed.startsWith("tun:")
                tunIndent = if (inTun) indent else -1
                tunEnableWritten = false
                inDns = trimmed.startsWith("dns:")
                dnsIndent = if (inDns) indent else -1
                val key = trimmed.substringBefore(':').lowercase(Locale.ROOT)
                if (key in TOP_LEVEL_LISTENER_KEYS || key == "allow-lan" || key == "bind-address") {
                    return@forEach
                }
            } else {
                if (inTun && indent <= tunIndent) inTun = false
                if (inDns && indent <= dnsIndent) inDns = false
            }

            if (inTun && trimmed.startsWith("enable:")) {
                if (!tunEnableWritten) {
                    filtered += "  enable: false"
                    tunEnableWritten = true
                }
                return@forEach
            }
            if (inDns && trimmed.startsWith("listen:")) return@forEach
            filtered += original
        }

        val output = mutableListOf(
            "mixed-port: $mixedPort",
            "allow-lan: false",
            "bind-address: '127.0.0.1'"
        )
        output += filtered
        return output.joinToString("\n").trim() + "\n"
    }

    private fun looksLikeYaml(text: String): Boolean =
        Regex("(?m)^\\s*(proxies|proxy-providers|proxy-groups|rules|dns|tun|mixed-port|socks-port)\\s*:")
            .containsMatchIn(text)

    private fun resolveHttpUrl(value: String, baseUrl: String?): String? {
        if (isHttpUrl(value)) return value
        val base = baseUrl?.takeIf(::isHttpUrl) ?: return null
        return runCatching { URI(base).resolve(value).toString() }.getOrNull()?.takeIf(::isHttpUrl)
    }

    private fun isHttpUrl(value: String): Boolean =
        value.startsWith("https://", ignoreCase = true) ||
            value.startsWith("http://", ignoreCase = true)

    private fun urlDecode(value: String): String =
        URLDecoder.decode(value, StandardCharsets.UTF_8.name())

    private val TOP_LEVEL_LISTENER_KEYS = setOf(
        "port", "socks-port", "mixed-port", "redir-port", "tproxy-port"
    )
    private val LOCAL_INBOUND_TYPES = setOf("mixed", "socks", "http", "direct")
    private val NON_PROXY_SING_BOX_TYPES = setOf(
        "direct", "block", "dns", "selector", "urltest", "url-test", "fallback"
    )
    private const val PLACEHOLDER_OUTBOUND =
        "{\"tag\":\"proxy\",\"protocol\":\"blackhole\",\"settings\":{}}"
}
