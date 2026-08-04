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

    private fun streamNetwork(stream: JSONObject): String =
        stream.optString("network", "tcp").lowercase()

    private fun firstNonBlank(vararg values: String?): String =
        values.firstOrNull { !it.isNullOrBlank() }.orEmpty()

    private fun tlsLikeEnabled(stream: JSONObject): Boolean {
        val security = streamSecurity(stream)
        return security == "tls" ||
            security == "reality" ||
            stream.has("tlsSettings") ||
            stream.has("realitySettings")
    }

    private fun tlsSource(stream: JSONObject): JSONObject =
        stream.optJSONObject("tlsSettings")
            ?: stream.optJSONObject("realitySettings")
            ?: JSONObject()

    private fun realitySource(stream: JSONObject): JSONObject? =
        stream.optJSONObject("realitySettings")
            ?: if (streamSecurity(stream) == "reality") tlsSource(stream) else null

    private fun singBoxTls(stream: JSONObject, server: String): JSONObject? {
        if (!tlsLikeEnabled(stream)) return null

        val tlsSource = tlsSource(stream)
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

        val fp = firstNonBlank(
            tlsSource.optString("fingerprint", ""),
            tlsSource.optString("clientFingerprint", "")
        )
        if (fp.isNotBlank()) {
            tls.put(
                "utls",
                JSONObject()
                    .put("enabled", true)
                    .put("fingerprint", fp)
            )
        }

        val reality = realitySource(stream)
        if (reality != null) {
            val publicKey = firstNonBlank(
                reality.optString("publicKey", ""),
                reality.optString("public_key", "")
            )
            val shortId = firstNonBlank(
                reality.optString("shortId", ""),
                reality.optString("short_id", ""),
                reality.optJSONArray("shortIds")?.optString(0),
                reality.optJSONArray("short_ids")?.optString(0)
            )

            val realityOptions = JSONObject().put("enabled", true)
            if (publicKey.isNotBlank()) realityOptions.put("public_key", publicKey)
            if (shortId.isNotBlank()) realityOptions.put("short_id", shortId)
            tls.put("reality", realityOptions)
        }

        return tls
    }

    private fun singBoxTransport(stream: JSONObject, protocol: String): JSONObject? {
        val network = streamNetwork(stream)
        if (network.isBlank() || network == "tcp" || network == "raw") return null

        if (network == "xhttp") {
            throw IllegalArgumentException(
                "sing-box translator cannot safely map Xray xhttp transport yet; use mihomo or Xray for this VLESS xhttp config"
            )
        }

        val transport = JSONObject()

        when (network) {
            "ws", "websocket" -> {
                val ws = stream.optJSONObject("wsSettings") ?: JSONObject()
                transport.put("type", "ws")
                val path = ws.optString("path", "")
                if (path.isNotBlank()) transport.put("path", path)
                val headers = ws.optJSONObject("headers")
                if (headers != null && headers.length() > 0) {
                    transport.put("headers", headers)
                }
            }
            "grpc" -> {
                val grpc = stream.optJSONObject("grpcSettings") ?: JSONObject()
                transport.put("type", "grpc")
                val service = grpc.optString("serviceName", "")
                if (service.isNotBlank()) transport.put("service_name", service)
            }
            "httpupgrade" -> {
                val http = stream.optJSONObject("httpupgradeSettings") ?: JSONObject()
                transport.put("type", "httpupgrade")
                val path = http.optString("path", "")
                if (path.isNotBlank()) transport.put("path", path)
                val host = http.optString("host", "")
                if (host.isNotBlank()) {
                    transport.put("headers", JSONObject().put("Host", host))
                }
            }
            "http", "h2" -> {
                transport.put("type", "http")
                val http = stream.optJSONObject("httpSettings") ?: JSONObject()
                val path = http.optJSONArray("path")?.optString(0)
                    ?: http.optString("path", "")
                if (path.isNotBlank()) transport.put("path", path)
                val host = http.optJSONArray("host")?.optString(0)
                    ?: http.optString("host", "")
                if (host.isNotBlank()) {
                    transport.put("headers", JSONObject().put("Host", host))
                }
            }
            else -> {
                throw IllegalArgumentException(
                    "Unsupported $protocol transport for sing-box: $network"
                )
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
                } else {
                    out.put("packet_encoding", "xudp")
                }

                val flow = user?.optString("flow", "") ?: ""
                if (flow.isNotBlank()) out.put("flow", flow)

                singBoxTls(stream, server)?.let { out.put("tls", it) }
            }
            "trojan" -> {
                val user = firstServer(settings)
                out.put("password", user?.optString("password", "") ?: "")
                out.put(
                    "tls",
                    singBoxTls(stream, server)
                        ?: JSONObject().put("enabled", true).put("server_name", server)
                )
            }
            "shadowsocks" -> {
                val s = firstServer(settings)
                out.put("method", s?.optString("method", "") ?: "")
                out.put("password", s?.optString("password", "") ?: "")
            }
            else -> throw IllegalArgumentException("Unsupported protocol for sing-box: $type")
        }

        singBoxTransport(stream, type)?.let { out.put("transport", it) }

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

    private fun mihomoTlsFields(proxy: JSONObject, stream: JSONObject) {
        proxy.put("tls", tlsLikeEnabled(stream))

        val source = tlsSource(stream)
        val serverName = firstNonBlank(
            source.optString("serverName", ""),
            source.optString("server_name", ""),
            source.optString("sni", "")
        )
        if (serverName.isNotBlank()) proxy.put("servername", serverName)

        val fp = firstNonBlank(
            source.optString("fingerprint", ""),
            source.optString("clientFingerprint", "")
        )
        if (fp.isNotBlank()) {
            proxy.put("client-fingerprint", fp)
            proxy.put("fingerprint", fp)
        }

        if (source.has("allowInsecure")) {
            proxy.put("skip-cert-verify", source.optBoolean("allowInsecure", false))
        }

        val reality = realitySource(stream)
        if (reality != null) {
            val opts = JSONObject()
            val publicKey = firstNonBlank(
                reality.optString("publicKey", ""),
                reality.optString("public_key", "")
            )
            val shortId = firstNonBlank(
                reality.optString("shortId", ""),
                reality.optString("short_id", ""),
                reality.optJSONArray("shortIds")?.optString(0),
                reality.optJSONArray("short_ids")?.optString(0)
            )
            if (publicKey.isNotBlank()) opts.put("public-key", publicKey)
            if (shortId.isNotBlank()) opts.put("short-id", shortId)
            proxy.put("reality-opts", opts)
        }
    }

    private fun mihomoTransport(proxy: JSONObject, stream: JSONObject, protocol: String) {
        val network = streamNetwork(stream)
        if (network.isBlank() || network == "tcp" || network == "raw") {
            proxy.put("network", "tcp")
            return
        }

        when (network) {
            "ws", "websocket" -> {
                proxy.put("network", "ws")
                val ws = stream.optJSONObject("wsSettings") ?: JSONObject()
                val opts = JSONObject()
                val path = ws.optString("path", "")
                if (path.isNotBlank()) opts.put("path", path)
                val headers = ws.optJSONObject("headers")
                if (headers != null && headers.length() > 0) opts.put("headers", headers)
                if (opts.length() > 0) proxy.put("ws-opts", opts)
            }
            "grpc" -> {
                proxy.put("network", "grpc")
                val grpc = stream.optJSONObject("grpcSettings") ?: JSONObject()
                val opts = JSONObject()
                val service = grpc.optString("serviceName", "")
                if (service.isNotBlank()) opts.put("grpc-service-name", service)
                if (opts.length() > 0) proxy.put("grpc-opts", opts)
            }
            "http", "h2" -> {
                proxy.put("network", if (network == "h2") "h2" else "http")
                val http = stream.optJSONObject("httpSettings") ?: JSONObject()
                val opts = JSONObject()
                val path = http.optJSONArray("path")?.optString(0)
                    ?: http.optString("path", "")
                if (path.isNotBlank()) opts.put("path", path)
                val host = http.optJSONArray("host")?.optString(0)
                    ?: http.optString("host", "")
                if (host.isNotBlank()) opts.put("host", JSONArray().put(host))
                proxy.put(if (network == "h2") "h2-opts" else "http-opts", opts)
            }
            "httpupgrade" -> {
                proxy.put("network", "ws")
                val http = stream.optJSONObject("httpupgradeSettings") ?: JSONObject()
                val opts = JSONObject().put("v2ray-http-upgrade", true)
                val path = http.optString("path", "")
                if (path.isNotBlank()) opts.put("path", path)
                val host = http.optString("host", "")
                if (host.isNotBlank()) {
                    opts.put("headers", JSONObject().put("Host", host))
                }
                proxy.put("ws-opts", opts)
            }
            "xhttp" -> {
                if (protocol != "vless") {
                    throw IllegalArgumentException("mihomo xhttp transport is only valid for VLESS")
                }
                proxy.put("network", "xhttp")
                val xhttp = stream.optJSONObject("xhttpSettings")
                    ?: stream.optJSONObject("splithttpSettings")
                    ?: JSONObject()
                val opts = JSONObject()
                val path = xhttp.optString("path", "")
                if (path.isNotBlank()) opts.put("path", path)
                val host = firstNonBlank(
                    xhttp.optString("host", ""),
                    xhttp.optJSONObject("headers")?.optString("Host", "")
                )
                if (host.isNotBlank()) opts.put("host", host)
                val mode = xhttp.optString("mode", "")
                if (mode.isNotBlank()) opts.put("mode", mode)
                val headers = xhttp.optJSONObject("headers")
                if (headers != null && headers.length() > 0) opts.put("headers", headers)
                if (opts.length() > 0) proxy.put("xhttp-opts", opts)
            }
            else -> throw IllegalArgumentException("Unsupported transport for mihomo: $network")
        }
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
                if (type == "vless") {
                    proxy.put("encryption", "")
                    proxy.put("packet-encoding", "xudp")
                } else {
                    proxy.put("alterId", user?.optInt("alterId", 0) ?: 0)
                    proxy.put("cipher", user?.optString("security", "auto") ?: "auto")
                }

                val flow = user?.optString("flow", "") ?: ""
                if (flow.isNotBlank()) proxy.put("flow", flow)

                mihomoTlsFields(proxy, stream)
                mihomoTransport(proxy, stream, type)
            }
            "trojan" -> {
                proxy.put("password", firstServer(settings)?.optString("password", "") ?: "")
                mihomoTlsFields(proxy, stream)
                mihomoTransport(proxy, stream, type)
            }
            "shadowsocks" -> {
                val s = firstServer(settings)
                proxy.put("cipher", s?.optString("method", "") ?: "")
                proxy.put("password", s?.optString("password", "") ?: "")
            }
            else -> throw IllegalArgumentException("Unsupported protocol for mihomo: $type")
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
