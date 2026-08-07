package com.trivox.client.ssh

import android.content.Context
import com.trivox.client.config.OpenSshProfileCodec
import com.trivox.client.data.ConfigProfile
import com.trivox.client.data.ConfigRepository
import com.trivox.client.util.Diagnostics
import com.trivox.client.util.SecretStore
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class OpenSshTunnelBridge(context: Context) {
    private val appContext = context.applicationContext

    class Handle internal constructor(
        val proxyProfile: ConfigProfile,
        private val process: Process,
        private val command: OpenSshCommand,
        private val output: StringBuilder
    ) : AutoCloseable {
        private val closed = AtomicBoolean(false)

        fun isAlive(): Boolean = !closed.get() && process.isAlive

        fun failureText(): String = synchronized(output) {
            output.toString().takeLast(2_000)
        }

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            runCatching { command.stop(process) }
        }
    }

    fun start(profile: ConfigProfile): Handle {
        val request = OpenSshProfileCodec.decode(profile)
        val stored = SecretStore.get(appContext, request.secretAlias)
        val password = stored ?: request.password
            ?: error("OpenSSH password is unavailable; re-import the profile")

        if (stored == null) {
            SecretStore.put(appContext, request.secretAlias, password)
            runCatching {
                ConfigRepository(appContext).save(
                    OpenSshProfileCodec.withoutBootstrapPassword(profile)
                )
            }.onFailure {
                Diagnostics.warning("OpenSSH credential migration failed: ${it.message}")
            }
        }

        val toolset = OpenSshBinaryManager(appContext).prepare()
        val runtime = File(appContext.filesDir, "openssh-runtime/${profile.id}")
        runtime.mkdirs()
        val localPort = reservePort()
        val command = OpenSshCommand(toolset, runtime)
        val output = StringBuilder()
        val process = command.startDynamicForward(
            OpenSshCommand.DynamicForward(
                host = request.host,
                port = request.port,
                username = request.username,
                localPort = localPort,
                password = password,
                knownHosts = File(runtime, "known_hosts"),
                connectTimeoutSeconds = request.connectTimeoutSeconds
            )
        )

        Thread({
            runCatching {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        synchronized(output) {
                            if (output.length > 8_192) output.delete(0, output.length - 4_096)
                            output.appendLine(line)
                        }
                    }
                }
            }
        }, "trivox-openssh-log").apply {
            isDaemon = true
            start()
        }

        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(
            (request.connectTimeoutSeconds + 3).toLong()
        )
        while (System.nanoTime() < deadline) {
            if (!process.isAlive) {
                val text = synchronized(output) { output.toString().trim().takeLast(2_000) }
                error("OpenSSH exited before the SOCKS listener was ready${text.takeIf(String::isNotBlank)?.let { ": $it" }.orEmpty()}")
            }
            if (listenerReady(localPort)) {
                return Handle(
                    proxyProfile = OpenSshProfileCodec.localSocksProfile(profile, localPort),
                    process = process,
                    command = command,
                    output = output
                )
            }
            try {
                Thread.sleep(80)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                command.stop(process)
                error("OpenSSH start cancelled")
            }
        }
        command.stop(process)
        val text = synchronized(output) { output.toString().trim().takeLast(2_000) }
        error("OpenSSH SOCKS listener timed out${text.takeIf(String::isNotBlank)?.let { ": $it" }.orEmpty()}")
    }

    private fun reservePort(): Int = ServerSocket(0, 1).use { it.localPort }

    private fun listenerReady(port: Int): Boolean = runCatching {
        Socket().use {
            it.connect(InetSocketAddress("127.0.0.1", port), 250)
        }
        true
    }.getOrDefault(false)
}
