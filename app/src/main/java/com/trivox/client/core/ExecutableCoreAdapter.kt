package com.trivox.client.core

import android.content.Context
import android.os.Build
import com.trivox.client.util.Diagnostics
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

abstract class ExecutableCoreAdapter(
    private val context: Context,
    private val binaryName: String,
    private val libraryFileName: String,
    private val displayName: String
) : CoreAdapter {
    private val activeProcess = AtomicReference<Process?>(null)

    override fun isAvailable(): Boolean = runCatching {
        executableBinaryOrNull()?.isFile == true
    }.getOrDefault(false)

    override fun version(): String = runCatching {
        val binary = executableBinaryOrNull() ?: return@runCatching "missing"
        val process = processBuilder(listOf(binary.absolutePath, "version")).start()
        process.waitFor(1600, TimeUnit.MILLISECONDS)
        process.inputStream.bufferedReader().readText()
            .lineSequence()
            .firstOrNull { it.isNotBlank() }
            ?.trim()
            ?: "unknown"
    }.getOrDefault("unknown")

    override fun validate(configJson: String): CoreResult {
        val binary = executableBinaryOrNull() ?: return missing()
        val config = writeConfig(configJson, "validate")
        val args = validationArgs(binary, config)
        return runCatching {
            val process = processBuilder(args).start()
            val finished = process.waitFor(6, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                CoreResult(false, "$displayName validation timed out")
            } else {
                val out = process.inputStream.bufferedReader().readText().trim()
                if (process.exitValue() == 0) CoreResult(true)
                else CoreResult(false, out.ifBlank { "$displayName rejected config" })
            }
        }.getOrElse {
            Diagnostics.recordThrowable("$displayName validation", it)
            CoreResult(false, "$displayName validation failed: ${it.message}")
        }
    }

    override fun start(
        configJson: String,
        protectSocket: ((Int) -> Boolean)?
    ): CoreResult {
        if (protectSocket != null) {
            return CoreResult(false, "$displayName standalone binary mode supports local proxy mode only in this patch. Use Xray for Android VPN TUN mode.")
        }
        val binary = executableBinaryOrNull() ?: return missing()
        stop()
        val config = writeConfig(configJson, "run")
        val args = runArgs(binary, config)
        return runCatching {
            val process = processBuilder(args).start()
            Thread.sleep(320)
            if (!process.isAlive && process.exitValue() != 0) {
                val out = process.inputStream.bufferedReader().readText().trim()
                CoreResult(false, out.ifBlank { "$displayName exited during startup" })
            } else {
                activeProcess.set(process)
                CoreResult(true, data = JSONObject().put("data", JSONObject().put("running", true)))
            }
        }.getOrElse {
            Diagnostics.recordThrowable("$displayName start", it)
            CoreResult(false, "$displayName start failed: ${it.message}")
        }
    }

    override fun stop(): CoreResult = runCatching {
        activeProcess.getAndSet(null)?.let { process ->
            process.destroy()
            if (!process.waitFor(1200, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
            }
        }
        CoreResult(true)
    }.getOrElse {
        CoreResult(false, "$displayName stop failed: ${it.message}")
    }

    override fun state(): CoreResult {
        val running = activeProcess.get()?.isAlive == true
        return CoreResult(true, data = JSONObject().put("data", JSONObject().put("running", running)))
    }

    override fun realDelay(configPath: String, timeoutSeconds: Int, url: String): CoreResult =
        CoreResult(false, "$displayName does not expose libXray real-delay API; TCP and live health checks are used for smart selection.")

    protected abstract fun validationArgs(binary: File, config: File): List<String>
    protected abstract fun runArgs(binary: File, config: File): List<String>

    protected fun coreWorkDir(): File =
        File(context.filesDir, "trivox-cores/$binaryName").apply { mkdirs() }

    protected fun configHomeDir(): File =
        File(coreWorkDir(), "home").apply { mkdirs() }

    private fun processBuilder(args: List<String>): ProcessBuilder {
        val home = configHomeDir()
        val builder = ProcessBuilder(args)
            .directory(coreWorkDir())
            .redirectErrorStream(true)

        builder.environment()["HOME"] = home.absolutePath
        builder.environment()["XDG_CONFIG_HOME"] = home.absolutePath
        builder.environment()["MIHOMO_HOME"] = home.absolutePath
        builder.environment()["CLASH_HOME"] = home.absolutePath
        builder.environment()["TMPDIR"] = File(coreWorkDir(), "tmp").apply { mkdirs() }.absolutePath

        return builder
    }

    private fun executableBinaryOrNull(): File? {
        nativeBinary()?.let { return it }
        return legacyAssetBinaryForOldAndroid()
    }

    private fun nativeBinary(): File? {
        val dir = context.applicationInfo.nativeLibraryDir ?: return null
        val file = File(dir, libraryFileName)
        return file.takeIf { it.isFile && it.canExecute() && isLikelyElf(it) }
    }

    private fun legacyAssetBinaryForOldAndroid(): File? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return null
        return runCatching {
            val abi = preferredAbi()
            val out = File(coreWorkDir(), binaryName)
            if (!out.isFile || !isLikelyElf(out)) {
                val asset = "cores/$abi/$binaryName"
                context.assets.open(asset).use { input ->
                    out.outputStream().use { output -> input.copyTo(output) }
                }
            }
            out.setExecutable(true, true)
            out.takeIf { it.isFile && it.canExecute() && isLikelyElf(it) }
        }.getOrNull()
    }

    private fun isLikelyElf(file: File): Boolean = runCatching {
        file.inputStream().use { input ->
            val b = ByteArray(4)
            input.read(b) == 4 &&
                b[0] == 0x7f.toByte() &&
                b[1] == 'E'.code.toByte() &&
                b[2] == 'L'.code.toByte() &&
                b[3] == 'F'.code.toByte()
        }
    }.getOrDefault(false)

    private fun preferredAbi(): String = Build.SUPPORTED_ABIS.firstOrNull { abi ->
        abi == "arm64-v8a" || abi == "armeabi-v7a"
    } ?: Build.SUPPORTED_ABIS.firstOrNull().orEmpty()

    private fun writeConfig(configJson: String, name: String): File =
        File(coreWorkDir(), "$name-${System.nanoTime()}.json").apply { writeText(configJson) }

    private fun missing(): CoreResult =
        CoreResult(false, "$displayName binary is missing, compressed, or not executable. Rebuild APK with native ELF jniLibs.")
}

class SingBoxCoreAdapter(context: Context) : ExecutableCoreAdapter(
    context,
    "sing-box",
    "libtrivox_sing_box.so",
    "sing-box"
) {
    override val id = "sing-box"
    override val capabilities = CoreCapabilities(
        protocols = setOf("vless", "vmess", "trojan", "shadowsocks", "socks", "http", "wireguard", "hysteria2", "tuic"),
        transports = setOf("tcp", "ws", "grpc", "httpupgrade", "quic"),
        androidTun = false,
        configValidation = true,
        realDelayTest = false
    )
    override fun validationArgs(binary: File, config: File) =
        listOf(binary.absolutePath, "check", "-c", config.absolutePath)

    override fun runArgs(binary: File, config: File) =
        listOf(binary.absolutePath, "run", "-c", config.absolutePath)
}

class MihomoCoreAdapter(context: Context) : ExecutableCoreAdapter(
    context,
    "mihomo",
    "libtrivox_mihomo.so",
    "mihomo"
) {
    override val id = "mihomo"
    override val capabilities = CoreCapabilities(
        protocols = setOf("vless", "vmess", "trojan", "shadowsocks", "socks", "http", "wireguard", "hysteria2", "tuic"),
        transports = setOf("tcp", "ws", "grpc", "httpupgrade", "quic"),
        androidTun = false,
        configValidation = true,
        realDelayTest = false
    )
    override fun validationArgs(binary: File, config: File) =
        listOf(binary.absolutePath, "-d", coreWorkDir().absolutePath, "-t", "-f", config.absolutePath)

    override fun runArgs(binary: File, config: File) =
        listOf(binary.absolutePath, "-d", coreWorkDir().absolutePath, "-f", config.absolutePath)
}
