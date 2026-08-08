package com.trivox.client.core

// TRIVOX_V22_NATIVE_CRASH_GUARD
// TRIVOX_V20_SAFE_NATIVE_LIFECYCLE

import android.content.Context
import com.trivox.client.util.Diagnostics
import org.json.JSONObject
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
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
                "http",
                "wireguard"
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

            validateWireGuardLocally(configJson)
                ?.let {
                    return@synchronized it
                }

            if (!awaitNativeReadyUnsafe()) {
                return@synchronized CoreResult(
                    false,
                    "Xray native settle was interrupted"
                )
            }

            val file =
                context.cacheDir.resolve(
                    "trivox-validate-" +
                        "${System.nanoTime()}.json"
                )

            try {
                file.writeText(configJson)
                Diagnostics.nativeCheckpoint(
                    "testXray",
                    "begin",
                    "config=${file.name}"
                )
                val result =
                    invoke(
                        "testXray",
                        JSONObject().put(
                            "configPath",
                            file.absolutePath
                        )
                    )
                Diagnostics.nativeCheckpoint(
                    "testXray",
                    if (result.success) {
                        "completed"
                    } else {
                        "failed"
                    },
                    result.error
                )
                result
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

            if (NATIVE_RUNNING.get()) {
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
            }

            if (!awaitNativeReadyUnsafe()) {
                return@synchronized CoreResult(
                    false,
                    "Xray native settle was interrupted"
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

            if (result.success) {
                NATIVE_RUNNING.set(true)
            } else {
                NATIVE_RUNNING.set(false)
            }

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
            } else if (!NATIVE_RUNNING.get()) {
                CoreResult(
                    success = true,
                    data = JSONObject()
                        .put(
                            "data",
                            JSONObject().put("running", false)
                        )
                )
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
                method = "ping",
                payload = JSONObject()
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
                    ),
                logFailure = false
            )
        }

    private fun stopNativeUnsafe(
        reason: String
    ): CoreResult {
        /*
         * libXray StopXray crosses JNI into Go. Calling it when this process
         * does not own a successfully-started Xray instance is unnecessary and,
         * on affected Android/libgojni builds, can terminate the whole process.
         *
         * NATIVE_RUNNING is process-global because libXray itself is process-
         * global. Every entry is still serialized by NATIVE_LOCK.
         */
        if (!NATIVE_RUNNING.compareAndSet(true, false)) {
            ACTIVE_PROTECT.set(null)
            Diagnostics.nativeCheckpoint(
                "stopXray",
                "skipped",
                "$reason; no owned native Xray instance"
            )
            return CoreResult(true)
        }

        Diagnostics.nativeCheckpoint(
            "stopXray",
            "begin",
            reason
        )

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

        /*
         * Never immediately retry a failed native stop from another cleanup
         * path. Re-entering JNI after a partial Go shutdown is more dangerous
         * than treating the instance as unavailable for the rest of the
         * session. A new process starts with a clean flag.
         */
        releaseSessionUnsafe()

        if (result.success) {
            scheduleNativeSettleUnsafe()
        }

        return result
    }

    private fun validateWireGuardLocally(
        configJson: String
    ): CoreResult? {
        val root = runCatching {
            JSONObject(configJson)
        }.getOrElse {
            return CoreResult(
                false,
                "Invalid Xray JSON: " +
                    rootMessage(it)
            )
        }
        val outbounds =
            root.optJSONArray("outbounds")
                ?: return null
        var foundWireGuard = false

        for (index in 0 until outbounds.length()) {
            val outbound =
                outbounds.optJSONObject(index)
                    ?: continue
            if (
                !outbound
                    .optString("protocol")
                    .equals(
                        "wireguard",
                        ignoreCase = true
                    )
            ) {
                continue
            }

            foundWireGuard = true
            val settings =
                outbound.optJSONObject("settings")
                    ?: return CoreResult(
                        false,
                        "WireGuard outbound has no settings"
                    )
            if (
                settings
                    .optString("secretKey")
                    .isBlank()
            ) {
                return CoreResult(
                    false,
                    "WireGuard secretKey is missing"
                )
            }
            if (!settings.has("address")) {
                return CoreResult(
                    false,
                    "WireGuard address is missing"
                )
            }
            val peers =
                settings.optJSONArray("peers")
            if (
                peers == null ||
                peers.length() == 0
            ) {
                return CoreResult(
                    false,
                    "WireGuard peer is missing"
                )
            }
        }

        if (!foundWireGuard) return null

        Diagnostics.debug(
            "WireGuard config passed local preflight; " +
                "native testXray was intentionally skipped"
        )
        return CoreResult(true)
    }

    private fun scheduleNativeSettleUnsafe() {
        val readyAt =
            System.nanoTime() +
                CLEANUP_COOLDOWN_MS *
                    1_000_000L
        while (true) {
            val current =
                NATIVE_READY_AT_NANOS.get()
            if (current >= readyAt) return
            if (
                NATIVE_READY_AT_NANOS
                    .compareAndSet(
                        current,
                        readyAt
                    )
            ) {
                return
            }
        }
    }

    private fun awaitNativeReadyUnsafe(): Boolean {
        while (true) {
            val remaining =
                NATIVE_READY_AT_NANOS.get() -
                    System.nanoTime()
            if (remaining <= 0L) {
                return true
            }

            val sleepMs =
                (
                    (remaining + 999_999L) /
                        1_000_000L
                    ).coerceIn(
                        1L,
                        40L
                    )
            try {
                Thread.sleep(sleepMs)
            } catch (_: InterruptedException) {
                Thread.currentThread()
                    .interrupt()
                return false
            }
        }
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
        payload: JSONObject? = null,
        logFailure: Boolean = true
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
                if (
                    !it.success &&
                    logFailure
                ) {
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

        private val NATIVE_RUNNING =
            AtomicBoolean(false)

        private val NATIVE_READY_AT_NANOS =
            AtomicLong(0L)

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
