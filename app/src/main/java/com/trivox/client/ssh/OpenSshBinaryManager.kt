package com.trivox.client.ssh

import android.content.Context
import android.os.Build
import android.system.Os
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Installs an ABI-matched OpenSSH client tree into Trivox's own Termux-style
 * prefix: /data/data/com.trivox.client/files/usr. Binaries and shared libraries
 * are checksum-verified before atomically replacing an existing file.
 */
class OpenSshBinaryManager(context: Context) {
    private val appContext = context.applicationContext
    private val prefix = File(appContext.filesDir, "usr")

    data class Toolset(
        val version: String,
        val prefix: File,
        val binDir: File,
        val libDir: File,
        val etcDir: File,
        val ssh: File,
        val scp: File,
        val sftp: File,
        val sshKeygen: File,
        val sshKeyscan: File,
        val sshAgent: File,
        val sshAdd: File
    )

    @Synchronized
    fun prepare(): Toolset {
        val manifest = appContext.assets
            .open(MANIFEST_ASSET)
            .bufferedReader()
            .use { JSONObject(it.readText()) }
        val version = manifest.getString("version")
        val packageName = manifest.optString("packageName", appContext.packageName)
        check(packageName == appContext.packageName) {
            "OpenSSH assets target $packageName, not ${appContext.packageName}"
        }
        check(version != "BUILD_REQUIRED") {
            "OpenSSH assets have not been built. Run the OpenSSH asset workflow first."
        }
        val abis = manifest.getJSONObject("abis")
        val abi = selectAbi(abis)
        val files = abis.getJSONObject(abi).getJSONObject("files")
        cleanPreviousInstall(version, abi, files)

        for (relative in files.keys()) {
            installFile(
                abi = abi,
                relative = relative,
                expectedSha256 = files.getString(relative).lowercase()
            )
        }

        File(prefix, VERSION_MARKER).apply {
            parentFile?.mkdirs()
            writeText("$version\n$abi\n", Charsets.UTF_8)
        }
        File(prefix, INVENTORY_MARKER).writeText(
            files.keys().asSequence().sorted().joinToString("\n", postfix = "\n"),
            Charsets.UTF_8
        )

        val bin = File(prefix, "bin")
        val lib = File(prefix, "lib")
        val etc = File(prefix, "etc/ssh")
        return Toolset(
            version = version,
            prefix = prefix,
            binDir = bin,
            libDir = lib,
            etcDir = etc,
            ssh = requiredTool(bin, "ssh"),
            scp = requiredTool(bin, "scp"),
            sftp = requiredTool(bin, "sftp"),
            sshKeygen = requiredTool(bin, "ssh-keygen"),
            sshKeyscan = requiredTool(bin, "ssh-keyscan"),
            sshAgent = requiredTool(bin, "ssh-agent"),
            sshAdd = requiredTool(bin, "ssh-add")
        )
    }

    private fun cleanPreviousInstall(
        version: String,
        abi: String,
        incoming: JSONObject
    ) {
        val marker = File(prefix, VERSION_MARKER)
        val current = marker.takeIf(File::isFile)
            ?.readLines(Charsets.UTF_8)
            .orEmpty()
        if (current.getOrNull(0) == version && current.getOrNull(1) == abi) return

        val inventory = File(prefix, INVENTORY_MARKER)
        inventory.takeIf(File::isFile)
            ?.readLines(Charsets.UTF_8)
            .orEmpty()
            .filter(::isSafeRelativePath)
            .filterNot(incoming::has)
            .forEach { relative ->
                runCatching { File(prefix, relative).delete() }
            }
    }

    private fun installFile(
        abi: String,
        relative: String,
        expectedSha256: String
    ) {
        require(isSafeRelativePath(relative)) {
            "Unsafe OpenSSH asset path: $relative"
        }
        val destination = File(prefix, relative)
        if (destination.isFile && sha256(destination) == expectedSha256) {
            applyMode(destination, relative)
            return
        }

        destination.parentFile?.mkdirs()
        val temporary = File(destination.parentFile, ".${destination.name}.tmp")
        appContext.assets
            .open("openssh/$abi/$relative")
            .use { input ->
                temporary.outputStream().use(input::copyTo)
            }
        check(sha256(temporary) == expectedSha256) {
            "OpenSSH asset checksum mismatch: $relative"
        }
        if (destination.exists()) check(destination.delete()) {
            "Unable to replace OpenSSH file: $relative"
        }
        check(temporary.renameTo(destination)) {
            "Unable to install OpenSSH file: $relative"
        }
        applyMode(destination, relative)
    }

    private fun applyMode(file: File, relative: String) {
        val executable = relative.startsWith("bin/") ||
            relative.startsWith("libexec/")
        Os.chmod(
            file.absolutePath,
            if (executable) EXECUTABLE_MODE else READABLE_MODE
        )
    }

    private fun requiredTool(binDir: File, name: String): File =
        File(binDir, name).also {
            check(it.isFile) { "Bundled OpenSSH tool is missing: $name" }
        }

    private fun selectAbi(abis: JSONObject): String =
        Build.SUPPORTED_ABIS.firstOrNull(abis::has)
            ?: error(
                "No bundled OpenSSH binary for ${Build.SUPPORTED_ABIS.joinToString()}"
            )

    private fun isSafeRelativePath(value: String): Boolean {
        if (value.isBlank() || value.startsWith('/') || '\\' in value) return false
        return value.split('/').none { it.isBlank() || it == "." || it == ".." }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val MANIFEST_ASSET = "openssh/manifest.json"
        private const val VERSION_MARKER = ".trivox-openssh-version"
        private const val INVENTORY_MARKER = ".trivox-openssh-files"
        private const val EXECUTABLE_MODE = 448 // 0700
        private const val READABLE_MODE = 384 // 0600
    }
}
