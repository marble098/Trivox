package com.trivox.client.core

import com.trivox.client.config.XrayConfigBuilder
import com.trivox.client.data.ConfigProfile
import com.trivox.client.data.CoreId
import org.json.JSONArray
import org.json.JSONObject

object CoreConfigTranslator {
    fun build(request: CoreStartRequest, coreId: CoreId): String = when (coreId) {
        CoreId.XRAY -> XrayConfigBuilder.build(
            profile = request.profile,
            settings = request.settings,
            mode = request.mode,
            tunFd = request.tunFd,
            errorLogPath = com.trivox.client.util.Diagnostics.xrayErrorLogPath()
        )
        CoreId.SING_BOX -> buildSingBox(request.profile, request.settings.socksPort).toString()
        CoreId.MIHOMO -> buildMihomoYaml(request.profile, request.settings.socksPort)
    }

    fun buildNativeProfile(profile: ConfigProfile, coreId: CoreId, mixedPort: Int): String = when (coreId) {
        CoreId.XRAY -> profile.outboundJson
        CoreId.SING_BOX -> buildSingBox(profile, mixedPort).toString(2)
        CoreId.MIHOMO -> buildMihomoYaml(profile, mixedPort)
    }

    private fun outbound(profile: ConfigProfile): JSONObject =
        runCatching { JSONObject(profile.outboundJson) }.getOrElse { JSONObject() }

    private fun firstUser(settings: JSONObject): JSONObject? =
        settings.optJSONArray("vnext")?.optJSONObject(0)?.optJSONArray("users")?.optJSONObject(0)

    private fun firstServer(settings: JSONObject): JSONObject? =
        settings.optJSONArray("servers")?.optJSONObject(0)

    private fun endpointAddress(settings: JSONObject, fallback: String): String =
        settings.optJSONArray("vnext")?.optJSONObject(0)?.optString("address")?.takeIf(String::isNotBlank)
            ?: settings.optJSONArray("servers")?.optJSONObject(0)?.optString("address")?.takeIf(String::isNotBlank)
            ?: settings.optString("address").takeIf(String::isNotBlank)
            ?: fallback

    private fun endpointPort(settings: JSONObject, fallback: Int): Int =
        settings.optJSONArray("vnext")?.optJSONObject(0)?.optInt("port")?.takeIf { it > 0 }
            ?: settings.optJSONArray("servers")?.optJSONObject(0)?.optInt("port")?.takeIf { it > 0 }
            ?: settings.optInt("port").takeIf { it > 0 }
            ?: fallback

    private fun streamSecurity(stream: JSONObject): String =
        stream.optString("security", "").lowercase()

    private fun streamNetwork(stream: JSONObject): String =
        stream.optString("network", "tcp").lowercase()

    private fun firstNonBlank(vararg values: String?): String =
        values.firstOrNull { !it.isNullOrBlank() }.orEmpty()

    private fun tlsLikeEnabled(stream: JSONObject): Boolean {
        val security = streamSecurity(stream)
        return security == "tls" || security == "reality" || stream.has("tlsSettings") || stream.has("realitySettings")
    }

    private fun tlsSource(stream: JSONObject): JSONObject =
        stream.optJSONObject("tlsSettings") ?: stream.optJSONObject("realitySettings") ?: JSONObject()

    private fun realitySource(stream: JSONObject): JSONObject? =
        stream.optJSONObject("realitySettings") ?: if (streamSecurity(stream) == "reality") tlsSource(stream) else null

    private fun singBoxTls(stream: JSONObject, server: String): JSONObject? {
        if (!tlsLikeEnabled(stream)) return null
        val source = tlsSource(stream)
        val tls = JSONObject()
            .put("enabled", true)
            .put("server_name", firstNonBlank(source.optString("serverName", ""), source.optString("server_name", ""), source.optString("sni", ""), server))
        if (source.has("allowInsecure")) tls.put("insecure", source.optBoolean("allowInsecure", false))
        if (source.has("insecure")) tls.put("insecure", source.optBoolean("insecure", false))
        val fp = firstNonBlank(source.optString("fingerprint", ""), source.optString("clientFingerprint", ""))
        if (fp.isNotBlank()) tls.put("utls", JSONObject().put("enabled", true).put("fingerprint", fp))
        realitySource(stream)?.let { reality ->
            val opts = JSONObject().put("enabled", true)
            val pbk = firstNonBlank(reality.optString("publicKey", ""), reality.optString("public_key", ""))
            val sid = firstNonBlank(reality.optString("shortId", ""), reality.optString("short_id", ""), reality.optJSONArray("shortIds")?.optString(0), reality.optJSONArray("short_ids")?.optString(0))
            if (pbk.isNotBlank()) opts.put("public_key", pbk)
            if (sid.isNotBlank()) opts.put("short_id", sid)
            tls.put("reality", opts)
        }
        return tls
    }

    private fun singBoxTransport(stream: JSONObject): JSONObject? {
        val network = streamNetwork(stream)
        if (network.isBlank() || network == "tcp" || network == "raw") return null
        return when (network) {
            "ws", "websocket" -> {
                val ws = stream.optJSONObject("wsSettings") ?: JSONObject()
                JSONObject().put("type", "ws").apply {
                    ws.optString("path", "").takeIf(String::isNotBlank)?.let { put("path", it) }
                    ws.optJSONObject("headers")?.takeIf { it.length() > 0 }?.let { put("headers", it) }
                }
            }
            "grpc" -> {
                val grpc = stream.optJSONObject("grpcSettings") ?: JSONObject()
                JSONObject().put("type", "grpc").apply {
                    grpc.optString("serviceName", "").takeIf(String::isNotBlank)?.let { put("service_name", it) }
                }
            }
            "httpupgrade" -> {
                val http = stream.optJSONObject("httpupgradeSettings") ?: JSONObject()
                JSONObject().put("type", "httpupgrade").apply {
                    http.optString("path", "").takeIf(String::isNotBlank)?.let { put("path", it) }
                    http.optString("host", "").takeIf(String::isNotBlank)?.let { put("headers", JSONObject().put("Host", it)) }
                }
            }
            "http", "h2", "xhttp", "splithttp" -> {
                val xhttp = stream.optJSONObject("xhttpSettings") ?: stream.optJSONObject("splithttpSettings") ?: stream.optJSONObject("httpSettings") ?: JSONObject()
                JSONObject().put("type", "http").apply {
                    val path = xhttp.optJSONArray("path")?.optString(0) ?: xhttp.optString("path", "")
                    if (path.isNotBlank()) put("path", path)
                    val host = firstNonBlank(xhttp.optJSONArray("host")?.optString(0), xhttp.optString("host", ""), xhttp.optJSONObject("headers")?.optString("Host", ""))
                    if (host.isNotBlank()) put("headers", JSONObject().put("Host", host))
                }
            }
            else -> throw IllegalArgumentException("Unsupported sing-box transport: $network")
        }
    }

    private fun buildSingBox(profile: ConfigProfile, mixedPort: Int): JSONObject {
        val ob = outbound(profile)
        val type = ob.optString("protocol", profile.protocol).lowercase()
        val settings = ob.optJSONObject("settings") ?: JSONObject()
        val stream = ob.optJSONObject("streamSettings") ?: JSONObject()
        val server = endpointAddress(settings, profile.probeServer.ifBlank { profile.server })
        val port = endpointPort(settings, profile.probePort.takeIf { it > 0 } ?: profile.port)
        val out = JSONObject().put("type", if (type == "ss") "shadowsocks" else type).put("tag", "proxy").put("server", server).put("server_port", port)
        when (type) {
            "vless", "vmess" -> {
                val user = firstUser(settings)
                out.put("uuid", user?.optString("id", "") ?: "")
                if (type == "vmess") {
                    out.put("security", user?.optString("security", "auto") ?: "auto")
                    out.put("alter_id", user?.optInt("alterId", 0) ?: 0)
                } else {
                    out.put("packet_encoding", "xudp")
                    user?.optString("flow", "")?.takeIf(String::isNotBlank)?.let { out.put("flow", it) }
                }
                singBoxTls(stream, server)?.let { out.put("tls", it) }
            }
            "trojan" -> {
                out.put("password", firstServer(settings)?.optString("password", "") ?: "")
                out.put("tls", singBoxTls(stream, server) ?: JSONObject().put("enabled", true).put("server_name", server))
            }
            "shadowsocks", "ss" -> {
                val s = firstServer(settings)
                out.put("method", s?.optString("method", "") ?: "")
                out.put("password", s?.optString("password", "") ?: "")
            }
            "socks" -> out.put("version", "5")
            "http" -> Unit
            else -> throw IllegalArgumentException("Unsupported protocol for sing-box: $type")
        }
        singBoxTransport(stream)?.let { out.put("transport", it) }
        return JSONObject()
            .put("log", JSONObject().put("level", "warn"))
            .put("inbounds", JSONArray().put(JSONObject().put("type", "mixed").put("tag", "mixed-in").put("listen", "127.0.0.1").put("listen_port", mixedPort)))
            .put("outbounds", JSONArray().put(out).put(JSONObject().put("type", "direct").put("tag", "direct")))
            .put("route", JSONObject().put("final", "proxy"))
    }

    private fun mihomoProxy(profile: ConfigProfile): LinkedHashMap<String, Any?> {
        val ob = outbound(profile)
        val type = ob.optString("protocol", profile.protocol).lowercase()
        val settings = ob.optJSONObject("settings") ?: JSONObject()
        val stream = ob.optJSONObject("streamSettings") ?: JSONObject()
        val server = endpointAddress(settings, profile.probeServer.ifBlank { profile.server })
        val port = endpointPort(settings, profile.probePort.takeIf { it > 0 } ?: profile.port)
        val proxy = linkedMapOf<String, Any?>("name" to "proxy", "type" to if (type == "shadowsocks") "ss" else type, "server" to server, "port" to port, "udp" to true)
        when (type) {
            "vless", "vmess" -> {
                val user = firstUser(settings)
                proxy["uuid"] = user?.optString("id", "") ?: ""
                if (type == "vless") {
                    proxy["encryption"] = "none"
                    proxy["packet-encoding"] = "xudp"
                    user?.optString("flow", "")?.takeIf(String::isNotBlank)?.let { proxy["flow"] = it }
                } else {
                    proxy["alterId"] = user?.optInt("alterId", 0) ?: 0
                    proxy["cipher"] = user?.optString("security", "auto") ?: "auto"
                }
                mihomoTls(proxy, stream)
                mihomoTransport(proxy, stream, type)
            }
            "trojan" -> {
                proxy["password"] = firstServer(settings)?.optString("password", "") ?: ""
                mihomoTls(proxy, stream)
                mihomoTransport(proxy, stream, type)
            }
            "shadowsocks", "ss" -> {
                val s = firstServer(settings)
                proxy["cipher"] = s?.optString("method", "") ?: ""
                proxy["password"] = s?.optString("password", "") ?: ""
            }
            else -> throw IllegalArgumentException("Unsupported protocol for mihomo: $type")
        }
        return proxy
    }

    private fun mihomoTls(proxy: LinkedHashMap<String, Any?>, stream: JSONObject) {
        proxy["tls"] = tlsLikeEnabled(stream)
        val source = tlsSource(stream)
        firstNonBlank(source.optString("serverName", ""), source.optString("server_name", ""), source.optString("sni", "")).takeIf(String::isNotBlank)?.let { proxy["servername"] = it }
        firstNonBlank(source.optString("fingerprint", ""), source.optString("clientFingerprint", "")).takeIf(String::isNotBlank)?.let {
            proxy["client-fingerprint"] = it
            proxy["fingerprint"] = it
        }
        if (source.has("allowInsecure")) proxy["skip-cert-verify"] = source.optBoolean("allowInsecure", false)
        realitySource(stream)?.let { reality ->
            val opts = linkedMapOf<String, Any?>()
            firstNonBlank(reality.optString("publicKey", ""), reality.optString("public_key", "")).takeIf(String::isNotBlank)?.let { opts["public-key"] = it }
            firstNonBlank(reality.optString("shortId", ""), reality.optString("short_id", ""), reality.optJSONArray("shortIds")?.optString(0), reality.optJSONArray("short_ids")?.optString(0)).takeIf(String::isNotBlank)?.let { opts["short-id"] = it }
            proxy["reality-opts"] = opts
        }
    }

    private fun mihomoTransport(proxy: LinkedHashMap<String, Any?>, stream: JSONObject, protocol: String) {
        val network = streamNetwork(stream)
        if (network.isBlank() || network == "tcp" || network == "raw") { proxy["network"] = "tcp"; return }
        when (network) {
            "ws", "websocket" -> {
                val ws = stream.optJSONObject("wsSettings") ?: JSONObject()
                proxy["network"] = "ws"
                val opts = linkedMapOf<String, Any?>()
                ws.optString("path", "").takeIf(String::isNotBlank)?.let { opts["path"] = it }
                ws.optJSONObject("headers")?.takeIf { it.length() > 0 }?.let { opts["headers"] = jsonToMap(it) }
                if (opts.isNotEmpty()) proxy["ws-opts"] = opts
            }
            "grpc" -> {
                val grpc = stream.optJSONObject("grpcSettings") ?: JSONObject()
                proxy["network"] = "grpc"
                val opts = linkedMapOf<String, Any?>()
                grpc.optString("serviceName", "").takeIf(String::isNotBlank)?.let { opts["grpc-service-name"] = it }
                if (opts.isNotEmpty()) proxy["grpc-opts"] = opts
            }
            "http", "h2" -> {
                val http = stream.optJSONObject("httpSettings") ?: JSONObject()
                proxy["network"] = if (network == "h2") "h2" else "http"
                val opts = linkedMapOf<String, Any?>()
                val path = http.optJSONArray("path")?.optString(0) ?: http.optString("path", "")
                if (path.isNotBlank()) opts["path"] = path
                val host = http.optJSONArray("host")?.optString(0) ?: http.optString("host", "")
                if (host.isNotBlank()) opts["host"] = listOf(host)
                if (opts.isNotEmpty()) proxy[if (network == "h2") "h2-opts" else "http-opts"] = opts
            }
            "httpupgrade" -> {
                val http = stream.optJSONObject("httpupgradeSettings") ?: JSONObject()
                proxy["network"] = "ws"
                val opts = linkedMapOf<String, Any?>("v2ray-http-upgrade" to true)
                http.optString("path", "").takeIf(String::isNotBlank)?.let { opts["path"] = it }
                http.optString("host", "").takeIf(String::isNotBlank)?.let { opts["headers"] = linkedMapOf("Host" to it) }
                proxy["ws-opts"] = opts
            }
            "xhttp", "splithttp" -> {
                if (protocol != "vless") throw IllegalArgumentException("mihomo xhttp transport is only valid for VLESS")
                val xhttp = stream.optJSONObject("xhttpSettings") ?: stream.optJSONObject("splithttpSettings") ?: JSONObject()
                proxy["network"] = "xhttp"
                val opts = linkedMapOf<String, Any?>()
                xhttp.optString("path", "").takeIf(String::isNotBlank)?.let { opts["path"] = it }
                firstNonBlank(xhttp.optString("host", ""), xhttp.optJSONObject("headers")?.optString("Host", "")).takeIf(String::isNotBlank)?.let { opts["host"] = it }
                xhttp.optString("mode", "").takeIf(String::isNotBlank)?.let { opts["mode"] = it }
                xhttp.optJSONObject("headers")?.takeIf { it.length() > 0 }?.let { opts["headers"] = jsonToMap(it) }
                if (opts.isNotEmpty()) proxy["xhttp-opts"] = opts
            }
            else -> throw IllegalArgumentException("Unsupported mihomo transport: $network")
        }
    }

    private fun buildMihomoYaml(profile: ConfigProfile, mixedPort: Int): String {
        val proxy = mihomoProxy(profile)
        val root = linkedMapOf<String, Any?>(
            "mixed-port" to mixedPort,
            "allow-lan" to false,
            "mode" to "rule",
            "log-level" to "warning",
            "proxies" to listOf(proxy),
            "proxy-groups" to listOf(linkedMapOf("name" to "Proxy", "type" to "select", "proxies" to listOf("proxy"))),
            "rules" to listOf("MATCH,Proxy")
        )
        return yaml(root)
    }

    private fun jsonToMap(json: JSONObject): LinkedHashMap<String, Any?> {
        val map = linkedMapOf<String, Any?>()
        json.keys().forEach { key -> map[key] = json.opt(key).toYamlValue() }
        return map
    }

    private fun Any?.toYamlValue(): Any? = when (this) {
        null, JSONObject.NULL -> null
        is JSONObject -> jsonToMap(this)
        is JSONArray -> (0 until length()).map { opt(it).toYamlValue() }
        else -> this
    }

    private fun yaml(value: Any?, indent: Int = 0): String {
        val pad = " ".repeat(indent)
        return when (value) {
            is Map<*, *> -> value.entries.joinToString("") { (k, v) ->
                if (v is Map<*, *> || v is List<*>) "$pad$k:\n${yaml(v, indent + 2)}" else "$pad$k: ${scalar(v)}\n"
            }
            is List<*> -> value.joinToString("") { item ->
                if (item is Map<*, *>) "$pad-\n${yaml(item, indent + 2)}" else "$pad- ${scalar(item)}\n"
            }
            else -> "$pad${scalar(value)}\n"
        }
    }

    private fun scalar(value: Any?): String = when (value) {
        null -> "null"
        is Boolean, is Number -> value.toString()
        else -> "'" + value.toString().replace("'", "''") + "'"
    }
}
