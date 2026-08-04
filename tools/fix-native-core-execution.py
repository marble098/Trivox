#!/usr/bin/env python3
from pathlib import Path
import shutil
import os

root = Path(".").resolve()

adapter = root / "app/src/main/java/com/trivox/client/core/ExecutableCoreAdapter.kt"
adapter.write_text("""package com.trivox.client.core

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
        val process = ProcessBuilder(binary.absolutePath, "version")
            .directory(coreWorkDir())
            .redirectErrorStream(true)
            .start()
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
            val process = ProcessBuilder(args)
                .directory(coreWorkDir())
                .redirectErrorStream(true)
                .start()
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
            val process = ProcessBuilder(args)
                .directory(coreWorkDir())
                .redirectErrorStream(true)
                .start()
            Thread.sleep(260)
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

    private fun executableBinaryOrNull(): File? {
        nativeBinary()?.let { return it }
        return legacyAssetBinaryForOldAndroid()
    }

    private fun nativeBinary(): File? {
        val dir = context.applicationInfo.nativeLibraryDir ?: return null
        val file = File(dir, libraryFileName)
        return file.takeIf { it.isFile && it.canExecute() }
    }

    private fun legacyAssetBinaryForOldAndroid(): File? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return null
        return runCatching {
            val abi = preferredAbi()
            val out = File(coreWorkDir(), binaryName)
            if (!out.isFile) {
                val asset = "cores/$abi/$binaryName"
                context.assets.open(asset).use { input ->
                    out.outputStream().use { output -> input.copyTo(output) }
                }
            }
            out.setExecutable(true, true)
            out.takeIf { it.isFile && it.canExecute() }
        }.getOrNull()
    }

    private fun preferredAbi(): String = Build.SUPPORTED_ABIS.firstOrNull { abi ->
        abi == "arm64-v8a" || abi == "armeabi-v7a"
    } ?: Build.SUPPORTED_ABIS.firstOrNull().orEmpty()

    private fun coreWorkDir(): File = File(context.filesDir, "trivox-cores/$binaryName").apply { mkdirs() }

    private fun writeConfig(configJson: String, name: String): File =
        File(coreWorkDir(), "$name-${System.nanoTime()}.json").apply { writeText(configJson) }

    private fun missing(): CoreResult = CoreResult(false, "$displayName binary is missing or not executable. Rebuild APK with native jniLibs packaging.")
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
    override fun validationArgs(binary: File, config: File) = listOf(binary.absolutePath, "check", "-c", config.absolutePath)
    override fun runArgs(binary: File, config: File) = listOf(binary.absolutePath, "run", "-c", config.absolutePath)
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
    override fun validationArgs(binary: File, config: File) = listOf(binary.absolutePath, "-t", "-f", config.absolutePath)
    override fun runArgs(binary: File, config: File) = listOf(binary.absolutePath, "-f", config.absolutePath, "-d", config.parentFile!!.absolutePath)
}
""", encoding="utf-8")

build = root / "app/build.gradle.kts"
s = build.read_text(encoding="utf-8")
if "useLegacyPackaging = true" not in s:
    marker = "    defaultConfig {"
    if marker not in s:
        raise SystemExit("defaultConfig marker not found in app/build.gradle.kts")
    s = s.replace(
        marker,
        """    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

""" + marker,
        1
    )
    build.write_text(s, encoding="utf-8")

packager = root / "tools/fix-native-core-packaging.py"
packager.write_text("""#!/usr/bin/env python3
from pathlib import Path
import shutil
import os

root = Path(".").resolve()
assets = root / "app/src/main/assets/cores"
jni = root / "app/src/main/jniLibs"

abis = ["arm64-v8a", "armeabi-v7a"]
names = {
    "sing-box": "libtrivox_sing_box.so",
    "mihomo": "libtrivox_mihomo.so",
}

for abi in abis:
    for src_name, dst_name in names.items():
        src = assets / abi / src_name
        if not src.is_file():
            continue
        dst_dir = jni / abi
        dst_dir.mkdir(parents=True, exist_ok=True)
        dst = dst_dir / dst_name
        shutil.copy2(src, dst)
        os.chmod(dst, 0o755)
        print(f"native core: {src} -> {dst}")

x86 = jni / "x86_64"
if x86.exists():
    shutil.rmtree(x86)
""", encoding="utf-8")
os.chmod(packager, 0o755)

subprocess_marker = root / ".github/workflows/trivox-multicore-binaries.yml"
w = subprocess_marker.read_text(encoding="utf-8")

if "Fix native core packaging" not in w:
    w = w.replace(
        "      - name: Download latest prerelease sing-box and mihomo assets\n        env:\n          GITHUB_TOKEN: ${{ github.token }}\n        run: python3 tools/prepare-multicore-binaries.py\n",
        "      - name: Download latest prerelease sing-box and mihomo assets\n        env:\n          GITHUB_TOKEN: ${{ github.token }}\n        run: python3 tools/prepare-multicore-binaries.py\n\n"
        "      - name: Fix native core packaging\n        run: python3 tools/fix-native-core-packaging.py\n"
    )

if "Verify native core libs" not in w:
    w = w.replace(
        "      - name: Commit prepared multicore files\n",
        "      - name: Verify native core libs\n"
        "        run: |\n"
        "          test -f app/src/main/jniLibs/arm64-v8a/libtrivox_sing_box.so\n"
        "          test -f app/src/main/jniLibs/arm64-v8a/libtrivox_mihomo.so\n"
        "          test -f app/src/main/jniLibs/armeabi-v7a/libtrivox_sing_box.so\n"
        "          test -f app/src/main/jniLibs/armeabi-v7a/libtrivox_mihomo.so\n"
        "          ls -lh app/src/main/jniLibs/arm64-v8a app/src/main/jniLibs/armeabi-v7a\n\n"
        "      - name: Commit prepared multicore files\n"
    )

w = w.replace(
    "git add app/src/main tools docs .github/workflows",
    "git add app/src/main tools docs .github/workflows app/build.gradle.kts"
)

subprocess_marker.write_text(w, encoding="utf-8")
