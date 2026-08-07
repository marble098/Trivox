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
        val privateKey: File? = null,
        val password: String? = null,
        val knownHosts: File,
        val connectTimeoutSeconds: Int = 10,
        val extraOptions: Map<String, String> = emptyMap()
    )

    fun startDynamicForward(request: DynamicForward): Process {
        require(request.host.isNotBlank())
        require(request.username.isNotBlank())
        require(request.port in 1..65535)
        require(request.localPort in 1..65535)
        require(request.privateKey?.isFile == true || !request.password.isNullOrBlank()) {
            "OpenSSH requires a private key or password"
        }

        runtimeDir.mkdirs()
        request.privateKey?.let {
            Os.chmod(it.absolutePath, OWNER_READ_WRITE_MODE)
        }
        request.knownHosts.parentFile?.mkdirs()
        if (!request.knownHosts.exists()) request.knownHosts.createNewFile()
        Os.chmod(request.knownHosts.absolutePath, OWNER_READ_WRITE_MODE)

        val arguments = mutableListOf(
            toolset.ssh.absolutePath,
            "-N",
            "-T",
            "-F", "/dev/null",
            "-p", request.port.toString(),
            "-D", "127.0.0.1:${request.localPort}",
            "-o", "ExitOnForwardFailure=yes",
            "-o", "ServerAliveInterval=20",
            "-o", "ServerAliveCountMax=3",
            "-o", "TCPKeepAlive=yes",
            "-o", "RequestTTY=no",
            "-o", "LogLevel=ERROR",
            "-o", "ConnectTimeout=${request.connectTimeoutSeconds.coerceIn(3, 60)}",
            "-o", "StrictHostKeyChecking=accept-new",
            "-o", "UserKnownHostsFile=${request.knownHosts.absolutePath}",
            "-o", "GlobalKnownHostsFile=/dev/null"
        )

        if (request.privateKey != null) {
            arguments += listOf(
                "-i", request.privateKey.absolutePath,
                "-o", "BatchMode=yes",
                "-o", "IdentitiesOnly=yes",
                "-o", "PasswordAuthentication=no",
                "-o", "KbdInteractiveAuthentication=no"
            )
        } else {
            arguments += listOf(
                "-o", "BatchMode=no",
                "-o", "NumberOfPasswordPrompts=1",
                "-o", "PubkeyAuthentication=no",
                "-o", "PasswordAuthentication=yes",
                "-o", "KbdInteractiveAuthentication=yes",
                "-o", "PreferredAuthentications=keyboard-interactive,password"
            )
        }

        request.extraOptions.toSortedMap().forEach { (key, value) ->
            require(SAFE_OPTION.matches(key)) { "Unsafe SSH option name: $key" }
            require('\n' !in value && '\r' !in value) { "Unsafe SSH option value" }
            arguments += listOf("-o", "$key=$value")
        }
        arguments += "${request.username}@${request.host}"

        val builder = process(arguments)
        if (!request.password.isNullOrBlank()) {
            val askPass = File(runtimeDir, "ssh-askpass.sh")
            askPass.writeText(
                "#!/system/bin/sh\nprintf '%s\\n' \"\$TRIVOX_SSH_PASSWORD\"\n",
                Charsets.UTF_8
            )
            Os.chmod(askPass.absolutePath, OWNER_EXECUTABLE_MODE)
            builder.environment()["SSH_ASKPASS"] = askPass.absolutePath
            builder.environment()["SSH_ASKPASS_REQUIRE"] = "force"
            builder.environment()["DISPLAY"] = ":0"
            builder.environment()["TRIVOX_SSH_PASSWORD"] = request.password
            builder.redirectInput(File("/dev/null"))
        }
        return builder.start()
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
        private const val OWNER_EXECUTABLE_MODE = 448 // 0700
        private val SAFE_OPTION = Regex("[A-Za-z][A-Za-z0-9]*")
    }
}
