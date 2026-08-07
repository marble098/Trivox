package com.trivox.client.config

import com.trivox.client.data.ConfigProfile
import org.json.JSONObject

object ProxyChainCodec {
    private const val MARKER = "_trivoxProxyChain"
    private const val VERSION = 1

    data class Chain(
        val exit: JSONObject,
        val bridge: JSONObject,
        val exitName: String,
        val bridgeName: String
    )

    fun encode(
        exit: ConfigProfile,
        bridge: ConfigProfile
    ): String = JSONObject()
        .put(MARKER, VERSION)
        .put("exitName", exit.name)
        .put("bridgeName", bridge.name)
        .put("exit", JSONObject(exit.outboundJson))
        .put("bridge", JSONObject(bridge.outboundJson))
        .toString()

    fun decode(value: String): Chain? =
        runCatching {
            val root = JSONObject(value)
            if (root.optInt(MARKER) != VERSION) {
                return null
            }
            Chain(
                exit = root.getJSONObject("exit"),
                bridge = root.getJSONObject("bridge"),
                exitName = root.optString("exitName"),
                bridgeName = root.optString("bridgeName")
            )
        }.getOrNull()
}
