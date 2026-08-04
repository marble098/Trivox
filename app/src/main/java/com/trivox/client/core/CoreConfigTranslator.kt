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
        CoreId.SING_BOX -> buildSingBox(request.profile, request.settings.socksPort)
        CoreId.MIHOMO -> buildMihomo(request.profile, request.settings.socksPort)
    }

    private fun outbound(profile: ConfigProfile): JSONObject =
        runCatching { JSONObject(profile.outboundJson) }.getOrElse { JSONObject() }

    private fun firstUser(settings: JSONObject): JSONObject? =
        settings.optJSONArray("vnext")
            ?.optJSONObject(0)
            ?.optJSONArray("users")
            ?.optJSONObject(0)

    private fun firstServer(settings: JSONObject): JSONObject? =
        settings.optJSONArray("servers")?.optJSONObject(0)

    private fun streamSecurity(stream: JSONObject): String =
        stream.optString("security", "").lowercase()

    private fun tlsLikeEnabled(stream: JSONObject): Boolean {
        val security = streamSecurity(stream)
        return security == "tls" ||
            security == "reality" ||
            stream.has("tlsSettings") ||
            stream.has("realitySettings")
    }

    private fun pickTlsSource(stream: JSONObject): JSONObject {
        return stream.optJSONObject("tlsSettings")
            ?: stream.optJSONObject("realitySettings")
            ?: JSONObject()
    }

    private fun firstNonBlank(vararg values: String?): String =
        values.firstOrNull { !it.isNullOrBlank() } ?: ""

    private fun singBoxTls(stream: JSONObject, server: String): JSONObject? {
        if (!tlsLikeEnabled(stream)) return null

        val security = streamSecurity(stream)
        val tlsSource = pickTlsSource(stream)
        val reality = stream.optJSONObject("realitySettings")

        val serverName = firstNonBlank(
            tlsSource.optString("serverName", ""),
            tlsSource.optString("server_name", ""),
            tlsSource.optString("sni", ""),
            server
        )

        val tls = JSONObject()
            .put("enabled", true)
            .put("server_name", serverName)

        if (tlsSource.has("allowInsecure")) {
            tls.put("insecure", tlsSource.optBoolean("allowInsecure", false))
        }
        if (tlsSource.has("insecure")) {
            tls.put("insecure", tlsSource.optBoolean("insecure", false))
        }

        val fingerprint = firstNonBlank(
            tlsSource.optString("fingerprint", ""),
            tlsSource.optString("clientFingerprint", "")
        )
        if (fingerprint.isNotBlank()) {
            tls.put(
                "utls",
                JSONObject()
                    .put("enabled", true)
                    .put("fingerprint", fingerprint)
            )
        }

        if (security == "reality" || reality != null) {
            val src = reality ?: tlsSource
            val publicKey = firstNonBlank(
                src.optString("publicKey", ""),
                src.optString("public_key", "")
            )
            val shortId = firstNonBlank(
                src.optString("shortId", ""),
                src.optString("short_id", ""),
                src.optJSONArray("shortIds")?.optString(0),
                src.optJSONArray("short_ids")?.optString(0)
            )

            val realityOptions = JSONObject().put("enabled", true)
            if (publicKey.isNotBlank()) realityOptions.put("public_key", publicKey)
            if (shortId.isNotBlank()) realityOptions.put("short_id", shortId)
            tls.put("reality", realityOptions)
        }

        return tls
    }

    private fun singBoxTransport(stream: JSONObject): JSONObject? {
        val network = stream.optString("network", "tcp").lowercase()
        if (network.isBlank() || network == "tcp") return null

        val transport = JSONObject().put("type", network)

        when (network) {
            "ws" -> {
                val ws = stream.optJSONObject("wsSettings") ?: JSONObject()
                val path = ws.optString("path", "")
                if (path.isNotBlank()) transport.put("path", path)
                val headers = ws.optJSONObject("headers")
                val host = headers?.optString("Host", "") ?: ""
                if (host.isNotBlank()) {
                    transport.put("headers", JSONObject().put("Host", host))
                }
            }
            "grpc" -> {
                val grpc = stream.optJSONObject("grpcSettings") ?: JSONObject()
                val serviceName = grpc.optString("serviceName", "")
                if (serviceName.isNotBlank()) transport.put("service_name", serviceName)
            }
            "httpupgrade" -> {
                val http = stream.optJSONObject("httpupgradeSettings") ?: JSONObject()
                val path = http.optString("path", "")
                if (path.isNotBlank()) transport.put("path", path)
                val host = http.optString("host", "")
                if (host.isNotBlank()) transport.put("host", host)
            }
        }

        return transport
    }

    private fun buildSingBox(profile: ConfigProfile, mixedPort: Int): String {
        val ob = outbound(profile)
        val type = ob.optString("protocol", profile.protocol).lowercase()
        val settings = ob.optJSONObject("settings") ?: JSONObject()
        val stream = ob.optJSONObject("streamSettings") ?: JSONObject()
        val server = profile.probeServer.ifBlank { profile.server }
        val port = profile.probePort.takeIf { it > 0 } ?: profile.port

        val out = JSONObject()
            .put("type", if (type == "shadowsocks") "shadowsocks" else type)
            .put("tag", "proxy")
            .put("server", server)
            .put("server_port", port)

        when (type) {
            "vless", "vmess" -> {
                val user = firstUser(settings)
                out.put("uuid", user?.optString("id", "") ?: "")
                if (type == "vmess") {
                    out.put("security", user?.optString("security", "auto") ?: "auto")
                    out.put("alter_id", user?.optInt("alterId", 0) ?: 0)
                }
                val flow = user?.optString("flow", "") ?: ""
                if (flow.isNotBlank()) out.put("flow", flow)
                singBoxTls(stream, server)?.let { out.put("tls", it) }
            }
            "trojan" -> {
                val user = firstServer(settings)
                out.put("password", user?.optString("password", "") ?: "")
                out.put("tls", singBoxTls(stream, server) ?: JSONObject().put("enabled", true).put("server_name", server))
            }
            "shadowsocks" -> {
                val s = firstServer(settings)
                out.put("method", s?.optString("method", "") ?: "")
                out.put("password", s?.optString("password", "") ?: "")
            }
        }

        singBoxTransport(stream)?.let { out.put("transport", it) }

        return JSONObject()
            .put("log", JSONObject().put("level", "warn"))
            .put(
                "inbounds",
                JSONArray().put(
                    JSONObject()
                        .put("type", "mixed")
                        .put("tag", "mixed-in")
                        .put("listen", "127.0.0.1")
                        .put("listen_port", mixedPort)
                )
            )
            .put(
                "outbounds",
                JSONArray()
                    .put(out)
                    .put(JSONObject().put("type", "direct").put("tag", "direct"))
            )
            .put("route", JSONObject().put("final", "proxy"))
            .toString()
    }

    private fun buildMihomo(profile: ConfigProfile, mixedPort: Int): String {
        val ob = outbound(profile)
        val type = ob.optString("protocol", profile.protocol).lowercase()
        val settings = ob.optJSONObject("settings") ?: JSONObject()
        val stream = ob.optJSONObject("streamSettings") ?: JSONObject()
        val server = profile.probeServer.ifBlank { profile.server }
        val port = profile.probePort.takeIf { it > 0 } ?: profile.port

        val proxy = JSONObject()
            .put("name", "proxy")
            .put("type", if (type == "shadowsocks") "ss" else type)
            .put("server", server)
            .put("port", port)
            .put("udp", true)

        when (type) {
            "vless", "vmess" -> {
                val user = firstUser(settings)
                proxy.put("uuid", user?.optString("id", "") ?: "")
                proxy.put("tls", tlsLikeEnabled(stream))
                proxy.put("network", stream.optString("network", "tcp"))

                val flow = user?.optString("flow", "") ?: ""
                if (flow.isNotBlank()) proxy.put("flow", flow)

                val tlsSource = pickTlsSource(stream)
                val serverName = firstNonBlank(
                    tlsSource.optString("serverName", ""),
                    tlsSource.optString("server_name", ""),
                    tlsSource.optString("sni", "")
                )
                if (serverName.isNotBlank()) proxy.put("servername", serverName)

                val fingerprint = firstNonBlank(
                    tlsSource.optString("fingerprint", ""),
                    tlsSource.optString("clientFingerprint", "")
                )
                if (fingerprint.isNotBlank()) proxy.put("client-fingerprint", fingerprint)

                val reality = stream.optJSONObject("realitySettings")
                if (streamSecurity(stream) == "reality" || reality != null) {
                    val src = reality ?: tlsSource
                    proxy.put("reality-opts", JSONObject()
                        .put("public-key", firstNonBlank(src.optString("publicKey", ""), src.optString("public_key", "")))
                        .put("short-id", firstNonBlank(src.optString("shortId", ""), src.optString("short_id", ""), src.optJSONArray("shortIds")?.optString(0)))
                    )
                }
            }
            "trojan" -> {
                proxy.put("password", firstServer(settings)?.optString("password", "") ?: "")
                proxy.put("tls", true)
            }
            "shadowsocks" -> {
                val s = firstServer(settings)
                proxy.put("cipher", s?.optString("method", "") ?: "")
                proxy.put("password", s?.optString("password", "") ?: "")
            }
        }

        return JSONObject()
            .put("mixed-port", mixedPort)
            .put("allow-lan", false)
            .put("mode", "rule")
            .put("log-level", "warning")
            .put("proxies", JSONArray().put(proxy))
            .put(
                "proxy-groups",
                JSONArray().put(
                    JSONObject()
                        .put("name", "Proxy")
                        .put("type", "select")
                        .put("proxies", JSONArray().put("proxy"))
                )
            )
            .put("rules", JSONArray().put("MATCH,Proxy"))
            .toString()
    }
}
