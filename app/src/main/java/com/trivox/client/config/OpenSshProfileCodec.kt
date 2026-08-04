package com.trivox.client.config

import android.content.Context
import com.trivox.client.data.ConfigProfile
import com.trivox.client.util.SecretStore
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID

object OpenSshProfileCodec {
    data class Request(
        val host: String,
        val port: Int,
        val username: String,
        val password: String?,
        val secretAlias: String,
        val connectTimeoutSeconds: Int
    )

    fun isOpenSsh(profile: ConfigProfile): Boolean =
        profile.protocol.equals("openssh", true) ||
            profile.protocol.equals("ssh", true)

    fun parse(raw: String): ConfigProfile {
        val uri = runCatching { URI(raw.trim()) }.getOrElse {
            throw ConfigParseException("Invalid OpenSSH URI: ${it.message}")
        }
        require(uri.scheme.equals("ssh", true) || uri.scheme.equals("openssh", true)) {
            "OpenSSH URI must start with ssh:// or openssh://"
        }
        val host = uri.host?.trim().orEmpty()
        val port = if (uri.port == -1) 22 else uri.port
        if (host.isBlank() || port !in 1..65535) {
            throw ConfigParseException("OpenSSH host or port is invalid")
        }
        val userInfo = uri.rawUserInfo.orEmpty()
        val username = decode(userInfo.substringBefore(':')).trim()
        val password = userInfo.substringAfter(':', "")
            .takeIf(String::isNotBlank)
            ?.let(::decode)
        if (username.isBlank()) {
            throw ConfigParseException("OpenSSH username is missing")
        }
        if (password.isNullOrBlank()) {
            throw ConfigParseException(
                "OpenSSH password is missing. Use ssh://user:password@host:port"
            )
        }
        val id = UUID.randomUUID().toString()
        val alias = "trivox_ssh_$id"
        val timeout = parseQuery(uri.rawQuery)["timeout"]
            ?.toIntOrNull()?.coerceIn(3, 60) ?: 10
        val name = decode(uri.rawFragment.orEmpty()).ifBlank { "$username@$host:$port" }
        val metadata = JSONObject()
            .put("tag", "proxy")
            .put("protocol", "openssh")
            .put(
                "settings",
                JSONObject()
                    .put("host", host)
                    .put("port", port)
                    .put("username", username)
                    .put("bootstrapPassword", password)
                    .put("secretAlias", alias)
                    .put("connectTimeoutSeconds", timeout)
            )
        val safeRaw = buildString {
            append("openssh://")
            append(encode(username))
            append('@')
            if (':' in host) append('[').append(host).append(']') else append(host)
            append(':').append(port)
            append("?timeout=").append(timeout)
            append('#').append(encode(name))
        }
        return ConfigProfile(
            id = id,
            name = name,
            protocol = "openssh",
            server = host,
            port = port,
            raw = safeRaw,
            outboundJson = metadata.toString(),
            probeServer = host,
            probePort = port
        )
    }

    fun decode(profile: ConfigProfile): Request {
        if (!isOpenSsh(profile)) error("Profile is not OpenSSH")
        val root = runCatching { JSONObject(profile.outboundJson) }.getOrElse {
            throw IllegalArgumentException("Invalid OpenSSH profile metadata", it)
        }
        val settings = root.optJSONObject("settings")
            ?: throw IllegalArgumentException("OpenSSH settings are missing")
        val host = settings.optString("host", profile.server).trim()
        val port = settings.optInt("port", profile.port)
        val username = settings.optString("username").trim()
        val alias = settings.optString("secretAlias", "trivox_ssh_${profile.id}")
        require(host.isNotBlank() && username.isNotBlank() && port in 1..65535)
        return Request(
            host = host,
            port = port,
            username = username,
            password = settings.optString("bootstrapPassword").ifBlank { null },
            secretAlias = alias,
            connectTimeoutSeconds = settings.optInt("connectTimeoutSeconds", 10)
                .coerceIn(3, 60)
        )
    }

    fun withoutBootstrapPassword(profile: ConfigProfile): ConfigProfile {
        val root = JSONObject(profile.outboundJson)
        root.optJSONObject("settings")?.remove("bootstrapPassword")
        return profile.copy(outboundJson = root.toString())
    }

    fun secureForStorage(context: Context, profile: ConfigProfile): ConfigProfile {
        if (!isOpenSsh(profile)) return profile
        val request = decode(profile)
        request.password?.takeIf(String::isNotBlank)?.let {
            SecretStore.put(context.applicationContext, request.secretAlias, it)
        }
        return withoutBootstrapPassword(profile)
    }

    fun localSocksProfile(source: ConfigProfile, localPort: Int): ConfigProfile {
        val server = JSONObject()
            .put("address", "127.0.0.1")
            .put("port", localPort)
        val outbound = JSONObject()
            .put("tag", "proxy")
            .put("protocol", "socks")
            .put("settings", JSONObject().put("servers", JSONArray().put(server)))
        return source.copy(
            protocol = "socks",
            server = "127.0.0.1",
            port = localPort,
            raw = "openssh-runtime://127.0.0.1:$localPort",
            outboundJson = outbound.toString(),
            probeServer = source.server,
            probePort = source.port
        )
    }

    private fun parseQuery(raw: String?): Map<String, String> = raw
        ?.split('&')
        ?.mapNotNull { item ->
            val key = item.substringBefore('=').takeIf(String::isNotBlank) ?: return@mapNotNull null
            decode(key) to decode(item.substringAfter('=', ""))
        }
        ?.toMap()
        ?: emptyMap()

    private fun decode(value: String): String =
        URLDecoder.decode(value, StandardCharsets.UTF_8.name())

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")
}
