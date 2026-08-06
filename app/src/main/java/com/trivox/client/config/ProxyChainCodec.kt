package com.trivox.client.config

import com.trivox.client.data.ConfigProfile
import org.json.JSONObject

object ProxyChainCodec {
    private const val MARKER = "trivox_proxy_chain_v1"

    data class Chain(
        val exit: JSONObject,
        val bridge: JSONObject
    )

    fun encode(exit: ConfigProfile, bridge: ConfigProfile): String {
        return JSONObject()
            .put("type", MARKER)
            .put("exit", JSONObject(exit.outboundJson))
            .put("bridge", JSONObject(bridge.outboundJson))
            .toString()
    }

    fun decode(raw: String?): Chain? {
        val text = raw?.trim().orEmpty()
        if (text.isBlank() || !text.startsWith("{")) return null
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return null
        val exit = root.optJSONObject("exit")
            ?: root.optJSONObject("proxyExit")
            ?: root.optJSONObject("chainExit")
            ?: return null
        val bridge = root.optJSONObject("bridge")
            ?: root.optJSONObject("proxyBridge")
            ?: root.optJSONObject("chainBridge")
            ?: return null
        return Chain(exit = exit, bridge = bridge)
    }

    fun parse(raw: String?): Chain? = decode(raw)

    fun isChain(raw: String?): Boolean = decode(raw) != null
}
