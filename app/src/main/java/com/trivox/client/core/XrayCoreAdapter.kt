package com.trivox.client.core

import android.content.Context
import com.trivox.client.util.Diagnostics
import org.json.JSONObject
import java.lang.reflect.Proxy

class XrayCoreAdapter(private val context: Context) : CoreAdapter {
    override val id = "xray"
    override val capabilities = CoreCapabilities(
        protocols = setOf("vless", "vmess", "trojan", "shadowsocks", "socks", "http"),
        transports = setOf("tcp", "ws", "grpc", "httpupgrade", "xhttp", "kcp", "quic"),
        androidTun = true, configValidation = true, realDelayTest = true
    )

    private val className = "libXray.LibXray"
    private val lock = Any()

    override fun isAvailable(): Boolean = runCatching { Class.forName(className); true }.getOrDefault(false)

    override fun version(): String = if (!isAvailable()) "missing" else invoke("xrayVersion").data
        ?.optJSONObject("data")?.optString("version") ?: "unknown"

    override fun validate(configJson: String): CoreResult = synchronized(lock) {
        if (!isAvailable()) return unavailable()
        val file = context.cacheDir.resolve("trivox-validate-${System.nanoTime()}.json")
        try {
            file.writeText(configJson)
            invoke("testXray", JSONObject().put("configPath", file.absolutePath))
        } finally {
            file.delete()
        }
    }

    override fun start(configJson: String, protectSocket: ((Int) -> Boolean)?): CoreResult = synchronized(lock) {
        if (!isAvailable()) return unavailable()
        runCatching { registerController(protectSocket) }.onFailure {
            return CoreResult(false, "Failed to register Android socket protection: ${it.message}")
        }
        invoke("runXrayFromJson", JSONObject().put("configJSON", configJson))
    }

    override fun stop(): CoreResult = synchronized(lock) {
        if (!isAvailable()) return unavailable()
        val result = invoke("stopXray")
        runCatching { registerController(null) }
        runCatching { Class.forName(className).getMethod("resetDNS").invoke(null) }
        result
    }

    override fun state(): CoreResult = if (!isAvailable()) unavailable() else invoke("getXrayState")

    override fun realDelay(configPath: String, timeoutSeconds: Int, url: String): CoreResult = synchronized(lock) {
        if (!isAvailable()) return unavailable()
        invoke("ping", JSONObject().put("configPath", configPath).put("timeout", timeoutSeconds).put("url", url))
    }

    private fun invoke(method: String, payload: JSONObject? = null): CoreResult {
        return runCatching {
            val request = JSONObject().put("apiVersion", 1).put("method", method)
            if (payload != null) request.put("payload", payload)
            val clazz = Class.forName(className)
            val raw = clazz.getMethod("invoke", String::class.java).invoke(null, request.toString()) as String
            val response = JSONObject(raw)
            val result = CoreResult(response.optBoolean("success"), response.optString("error"), response)
            if (!result.success) Diagnostics.error("Xray $method failed: ${Diagnostics.sanitize(result.error)}")
            result
        }.getOrElse { CoreResult(false, "Xray invocation failed: ${it.cause?.message ?: it.message}") }
    }

    private fun registerController(callback: ((Int) -> Boolean)?) {
        val clazz = Class.forName(className)
        val controllerClass = Class.forName("libXray.DialerController")
        val controller = callback?.let {
            Proxy.newProxyInstance(controllerClass.classLoader, arrayOf(controllerClass)) { proxy, method, args ->
                when (method.name) {
                    "protectFd" -> callback((args?.get(0) as Number).toInt())
                    "toString" -> "TrivoxDialerController"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.get(0)
                    else -> false
                }
            }
        }
        clazz.getMethod("registerDialerController", controllerClass).invoke(null, controller)
        clazz.getMethod("registerListenerController", controllerClass).invoke(null, controller)
        if (controller != null) clazz.getMethod("setDNS", controllerClass, String::class.java)
            .invoke(null, controller, "1.1.1.1:53")
    }

    private fun unavailable() = CoreResult(false, "Xray Android core is missing. Run tools/trivox-wizard.sh --prepare.")
}
