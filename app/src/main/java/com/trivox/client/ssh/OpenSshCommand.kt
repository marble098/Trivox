package com.trivox.client.ssh

import android.system.Os
import java.io.File
import java.util.concurrent.TimeUnit

/** Safe ProcessBuilder wrapper for the bundled OpenSSH client. */
class OpenSshCommand(
    private val toolset: OpenSshBinaryManager.Toolset,
    private val runtimeDir: File
) {
    data class DynamicForward(
        val host: String,
        val port: Int = 22,
        val username: String,
        val localPort: Int,
        val privateKey: File,
        val knownHosts: File,
        val connectTimeoutSeconds: Int = 10,
        val extraOptions: Map<String, String> = emptyMap()
    )

    fun startDynamicForward(request: DynamicForward): Process {
        require(request.host.isNotBlank())
        require(request.username.isNotBlank())
        require(request.port in 1..65535)
        require(request.localPort in 1..65535)
        require(request.privateKey.isFile)

        runtimeDir.mkdirs()
        Os.chmod(request.privateKey.absolutePath, OWNER_READ_WRITE_MODE)
        request.knownHosts.parentFile?.mkdirs()
        if (!request.knownHosts.exists()) request.knownHosts.createNewFile()
        Os.chmod(request.knownHosts.absolutePath, OWNER_READ_WRITE_MODE)

        val arguments = mutableListOf(
            toolset.ssh.absolutePath,
            "-N",
            "-T",
            "-F", "/dev/null",
            "-p", request.port.toString(),
            "-i", request.privateKey.absolutePath,
            "-D", "127.0.0.1:${request.localPort}",
            "-o", "BatchMode=yes",
            "-o", "ExitOnForwardFailure=yes",
            "-o", "IdentitiesOnly=yes",
            "-o", "PasswordAuthentication=no",
            "-o", "KbdInteractiveAuthentication=no",
            "-o", "ServerAliveInterval=20",
            "-o", "ServerAliveCountMax=3",
            "-o", "TCPKeepAlive=yes",
            "-o", "ConnectTimeout=${request.connectTimeoutSeconds.coerceIn(3, 60)}",
            "-o", "StrictHostKeyChecking=accept-new",
            "-o", "UserKnownHostsFile=${request.knownHosts.absolutePath}",
            "-o", "GlobalKnownHostsFile=/dev/null"
        )
        request.extraOptions.toSortedMap().forEach { (key, value) ->
            require(SAFE_OPTION.matches(key)) { "Unsafe SSH option name: $key" }
            require('\n' !in value && '\r' !in value) { "Unsafe SSH option value" }
            arguments += listOf("-o", "$key=$value")
        }
        arguments += "${request.username}@${request.host}"

        return process(arguments).start()
    }

    fun stop(process: Process, timeoutSeconds: Long = 2) {
        process.destroy()
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
        }
    }

    fun process(arguments: List<String>): ProcessBuilder {
        require(arguments.isNotEmpty())
        return ProcessBuilder(arguments)
            .directory(runtimeDir)
            .redirectErrorStream(true)
            .apply {
                environment()["HOME"] = runtimeDir.absolutePath
                environment()["TMPDIR"] = runtimeDir.absolutePath
                environment()["OPENSSH_HOME"] = toolset.prefix.absolutePath
                environment()["PATH"] = toolset.binDir.absolutePath
                environment()["LD_LIBRARY_PATH"] = toolset.libDir.absolutePath
                environment()["OPENSSL_CONF"] = File(
                    toolset.prefix,
                    "etc/tls/openssl.cnf"
                ).absolutePath
                environment().remove("SSH_AUTH_SOCK")
            }
    }

    companion object {
        private const val OWNER_READ_WRITE_MODE = 384 // 0600
        private val SAFE_OPTION = Regex("[A-Za-z][A-Za-z0-9]*")
    }
}
