package com.trivox.client.core

import android.content.Context
import com.trivox.client.util.Diagnostics
import org.json.JSONObject
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicReference

class XrayCoreAdapter(
    private val context: Context
) : CoreAdapter {
    override val id = "xray"

    override val capabilities =
        CoreCapabilities(
            protocols = setOf(
                "vless",
                "vmess",
                "trojan",
                "shadowsocks",
                "socks",
                "http"
            ),
            transports = setOf(
                "tcp",
                "ws",
                "grpc",
                "httpupgrade",
                "xhttp",
                "kcp",
                "quic"
            ),
            androidTun = true,
            configValidation = true,
            realDelayTest = true
        )

    private val className =
        "libXray.LibXray"

    override fun isAvailable(): Boolean =
        runCatching {
            Class.forName(className)
            true
        }.getOrDefault(false)

    override fun version(): String =
        synchronized(NATIVE_LOCK) {
            if (!isAvailable()) {
                "missing"
            } else {
                invoke("xrayVersion")
                    .data
                    ?.optJSONObject("data")
                    ?.optString("version")
                    ?: "unknown"
            }
        }

    override fun validate(
        configJson: String
    ): CoreResult =
        synchronized(NATIVE_LOCK) {
            if (!isAvailable()) {
                return@synchronized unavailable()
            }

            val file =
                context.cacheDir.resolve(
                    "trivox-validate-" +
                        "${System.nanoTime()}.json"
                )

            try {
                file.writeText(configJson)
                invoke(
                    "testXray",
                    JSONObject().put(
                        "configPath",
                        file.absolutePath
                    )
                )
            } finally {
                file.delete()
            }
        }

    override fun start(
        configJson: String,
        protectSocket: ((Int) -> Boolean)?
    ): CoreResult =
        synchronized(NATIVE_LOCK) {
            if (!isAvailable()) {
                return@synchronized unavailable()
            }

            val staleStop =
                stopNativeUnsafe(
                    "pre-start"
                )

            if (!staleStop.success) {
                return@synchronized CoreResult(
                    false,
                    "Previous Xray session " +
                        "could not be stopped: " +
                        staleStop.error
                )
            }

            val registered =
                runCatching {
                    registerStableControllerUnsafe(
                        protectSocket
                    )
                }

            if (registered.isFailure) {
                releaseSessionUnsafe()

                return@synchronized CoreResult(
                    false,
                    "Failed to register Android " +
                        "socket protection: " +
                        rootMessage(
                            registered
                                .exceptionOrNull()
                        )
                )
            }

            Diagnostics.nativeCheckpoint(
                "runXrayFromJson",
                "begin",
                if (protectSocket == null) {
                    "mode=proxy"
                } else {
                    "mode=vpn"
                }
            )

            val result =
                invoke(
                    "runXrayFromJson",
                    JSONObject().put(
                        "configJSON",
                        configJson
                    )
                )

            Diagnostics.nativeCheckpoint(
                "runXrayFromJson",
                if (result.success) {
                    "completed"
                } else {
                    "failed"
                },
                result.error
            )

            if (!result.success) {
                releaseSessionUnsafe()
            }

            result
        }

    override fun stop(): CoreResult =
        synchronized(NATIVE_LOCK) {
            if (!isAvailable()) {
                unavailable()
            } else {
                stopNativeUnsafe(
                    "requested"
                )
            }
        }

    override fun state(): CoreResult =
        synchronized(NATIVE_LOCK) {
            if (!isAvailable()) {
                unavailable()
            } else {
                invoke("getXrayState")
            }
        }

    override fun realDelay(
        configPath: String,
        timeoutSeconds: Int,
        url: String
    ): CoreResult =
        synchronized(NATIVE_LOCK) {
            if (!isAvailable()) {
                return@synchronized unavailable()
            }

            invoke(
                "ping",
                JSONObject()
                    .put(
                        "configPath",
                        configPath
                    )
                    .put(
                        "timeout",
                        timeoutSeconds
                    )
                    .put(
                        "url",
                        url
                    )
            )
        }

    private fun stopNativeUnsafe(
        reason: String
    ): CoreResult {
        Diagnostics.nativeCheckpoint(
            "stopXray",
            "begin",
            reason
        )

        /*
         * XTLS/libXray StopXray is synchronous and
         * idempotent: it closes the instance under
         * its own mutex and then clears coreServer.
         * Extra getXrayState polling is unnecessary.
         */
        val result =
            invoke("stopXray")

        Diagnostics.nativeCheckpoint(
            "stopXray",
            if (result.success) {
                "completed"
            } else {
                "failed"
            },
            result.error
        )

        if (result.success) {
            releaseSessionUnsafe()

            try {
                Thread.sleep(
                    CLEANUP_COOLDOWN_MS
                )
            } catch (
                _: InterruptedException
            ) {
                Thread
                    .currentThread()
                    .interrupt()
            }
        }

        return result
    }

    private fun registerStableControllerUnsafe(
        callback: ((Int) -> Boolean)?
    ) {
        ACTIVE_PROTECT.set(callback)

        val clazz =
            Class.forName(className)
        val controllerClass =
            Class.forName(
                "libXray.DialerController"
            )

        val controller =
            ACTIVE_CONTROLLER
                ?: Proxy.newProxyInstance(
                    controllerClass.classLoader,
                    arrayOf(controllerClass)
                ) {
                        proxy,
                        method,
                        args ->

                    when (method.name) {
                        "protectFd" -> {
                            val fd =
                                (
                                    args
                                        ?.firstOrNull()
                                        as? Number
                                    )
                                    ?.toInt()
                                    ?: return@newProxyInstance false

                            val active =
                                ACTIVE_PROTECT.get()

                            if (active == null) {
                                true
                            } else {
                                runCatching {
                                    active(fd)
                                }.getOrElse {
                                    Diagnostics
                                        .recordThrowable(
                                            "VPN protectFd",
                                            it
                                        )
                                    false
                                }
                            }
                        }

                        "toString" ->
                            "TrivoxStableDialerController"

                        "hashCode" ->
                            System.identityHashCode(
                                proxy
                            )

                        "equals" ->
                            proxy ===
                                args?.firstOrNull()

                        else -> false
                    }
                }.also {
                    ACTIVE_CONTROLLER = it
                }

        /*
         * Never pass null here. libXray v26.7.28
         * captures controller.ProtectFd without a
         * nil check. A null controller can therefore
         * panic inside Go and abort libgojni.so.
         */
        if (!CONTROLLER_REGISTERED) {
            clazz.getMethod(
                "registerDialerController",
                controllerClass
            ).invoke(
                null,
                controller
            )

            clazz.getMethod(
                "registerListenerController",
                controllerClass
            ).invoke(
                null,
                controller
            )

            CONTROLLER_REGISTERED = true
        }

        if (callback != null) {
            clazz.getMethod(
                "setDNS",
                controllerClass,
                String::class.java
            ).invoke(
                null,
                controller,
                "1.1.1.1:53"
            )
        } else {
            resetDnsUnsafe()
        }
    }

    private fun releaseSessionUnsafe() {
        resetDnsUnsafe()
        ACTIVE_PROTECT.set(null)

        /*
         * Keep ACTIVE_CONTROLLER strongly referenced
         * and registered for the process lifetime.
         * The callback becomes a safe allow-through
         * controller when no VPN session is active.
         */
    }

    private fun resetDnsUnsafe() {
        runCatching {
            Class.forName(className)
                .getMethod("resetDNS")
                .invoke(null)
        }.onFailure {
            Diagnostics.warning(
                "Unable to reset Xray DNS: " +
                    rootMessage(it)
            )
        }
    }

    private fun invoke(
        method: String,
        payload: JSONObject? = null
    ): CoreResult =
        runCatching {
            val request =
                JSONObject()
                    .put(
                        "apiVersion",
                        1
                    )
                    .put(
                        "method",
                        method
                    )

            if (payload != null) {
                request.put(
                    "payload",
                    payload
                )
            }

            val clazz =
                Class.forName(className)

            val raw =
                clazz.getMethod(
                    "invoke",
                    String::class.java
                ).invoke(
                    null,
                    request.toString()
                ) as? String
                    ?: error(
                        "libXray returned " +
                            "a non-string response"
                    )

            val response =
                JSONObject(raw)

            CoreResult(
                response.optBoolean(
                    "success"
                ),
                response.optString(
                    "error"
                ),
                response
            ).also {
                if (!it.success) {
                    Diagnostics.error(
                        "Xray $method failed: " +
                            Diagnostics.sanitize(
                                it.error
                            )
                    )
                }
            }
        }.getOrElse {
            Diagnostics.recordThrowable(
                "Xray invocation $method",
                it
            )

            CoreResult(
                false,
                "Xray invocation failed: " +
                    rootMessage(it)
            )
        }

    private fun rootMessage(
        throwable: Throwable?
    ): String {
        if (throwable == null) {
            return "unknown error"
        }

        var current: Throwable = throwable
        val visited =
            HashSet<Throwable>()

        while (
            current.cause != null &&
            visited.add(current)
        ) {
            current = current.cause!!
        }

        return current.message
            ?: current
                .javaClass
                .simpleName
    }

    private fun unavailable() =
        CoreResult(
            false,
            "Xray Android core is missing. " +
                "Run tools/trivox-wizard.sh " +
                "--prepare."
        )

    companion object {
        private val NATIVE_LOCK =
            Any()

        private val ACTIVE_PROTECT =
            AtomicReference<
                ((Int) -> Boolean)?
                >(null)

        @Volatile
        private var ACTIVE_CONTROLLER:
            Any? = null

        @Volatile
        private var CONTROLLER_REGISTERED =
            false

        private const val
            CLEANUP_COOLDOWN_MS = 180L
    }
}
