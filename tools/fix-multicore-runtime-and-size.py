#!/usr/bin/env python3
from pathlib import Path
import os
import re
import shutil
import stat

root = Path(".").resolve()

gradle = root / "app/build.gradle.kts"
s = gradle.read_text(encoding="utf-8")

s = re.sub(
    r'val supportedAbis = listOf\(\s*"arm64-v8a",\s*"armeabi-v7a",\s*"x86_64"\s*\)',
    'val supportedAbis = listOf(\n    "arm64-v8a",\n    "armeabi-v7a"\n)',
    s,
    flags=re.S
)

s = s.replace('isUniversalApk = true', 'isUniversalApk = false')

if "TRIVOX_NO_UNIVERSAL" not in s:
    s = s.replace(
        "    splits {\n        abi {\n",
        "    splits {\n        abi {\n"
    )

gradle.write_text(s, encoding="utf-8")

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
""", encoding="utf-8")

prepare = root / "tools/prepare-multicore-binaries.py"
ps = prepare.read_text(encoding="utf-8")

if "def is_elf" not in ps:
    ps = ps.replace(
        "def extract_binary(archive, binary, dst):\n",
        """def is_elf(path):
    try:
        with open(path, 'rb') as f:
            return f.read(4) == b'\\x7fELF'
    except Exception:
        return False

def extract_nested_if_needed(path, binary):
    name = path.name.lower()
    if is_elf(path):
        return path
    tmp = path.parent / (path.name + '.unpacked')
    tmp.mkdir(exist_ok=True)
    if name.endswith('.gz'):
        out = tmp / binary
        with gzip.open(path, 'rb') as i, open(out, 'wb') as o:
            shutil.copyfileobj(i, o)
        if is_elf(out):
            return out
    if name.endswith('.xz'):
        out = tmp / binary
        with lzma.open(path, 'rb') as i, open(out, 'wb') as o:
            shutil.copyfileobj(i, o)
        if is_elf(out):
            return out
    return path

def extract_binary(archive, binary, dst):
"""
    )

ps = re.sub(
    r"candidates=\[p for p in tmp\.rglob\('\*'\) if p\.is_file\(\) and p\.name in \(binary, binary\+'\.exe'\)\]\n"
    r"\s*if not candidates: candidates=\[p for p in tmp\.rglob\('\*'\) if p\.is_file\(\) and binary in p\.name\.lower\(\)\]\n"
    r"\s*if not candidates: raise RuntimeError\('binary not found in '\+archive\.name\)\n"
    r"\s*dst\.parent\.mkdir\(parents=True, exist_ok=True\)\n"
    r"\s*shutil\.copy2\(candidates\[0\], dst\)",
    """candidates=[p for p in tmp.rglob('*') if p.is_file() and p.name in (binary, binary+'.exe')]
        candidates += [p for p in tmp.rglob('*') if p.is_file() and binary in p.name.lower()]
        candidates = [extract_nested_if_needed(p, binary) for p in candidates]
        candidates = [p for p in candidates if p.is_file() and is_elf(p)]
        if not candidates:
            raise RuntimeError('ELF binary not found in '+archive.name)
        dst.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(candidates[0], dst)""",
    ps,
    flags=re.S
)

prepare.write_text(ps, encoding="utf-8")
