package com.trivox.client.util

import android.content.Context
import android.os.Build
import com.trivox.client.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Diagnostics {
    enum class Level { ERROR, WARNING, INFO, DEBUG }
    private lateinit var file: File
    private const val maxBytes = 256 * 1024
    @Volatile var debugEnabled = false

    fun initialize(context: Context) { file = File(context.filesDir, "diagnostics.log") }
    @Synchronized fun log(level: Level, message: String) {
        if (level == Level.DEBUG && !debugEnabled) return
        if (!::file.isInitialized) return
        if (file.length() > maxBytes) {
            val tail = file.readText().takeLast(maxBytes / 2)
            file.writeText(tail.substringAfter('\n', tail))
        }
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        file.appendText("$stamp ${level.name} ${sanitize(message)}\n")
    }
    fun error(message: String) = log(Level.ERROR, message)
    fun warning(message: String) = log(Level.WARNING, message)
    fun info(message: String) = log(Level.INFO, message)
    fun debug(message: String) = log(Level.DEBUG, message)
    fun recent(): String = if (::file.isInitialized && file.isFile) file.readText().takeLast(maxBytes) else ""
    fun report(coreVersion: String, mode: String, state: String, profile: String, lastError: String): String = buildString {
        appendLine("Trivox sanitized diagnostics")
        appendLine("App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("Android: ${Build.VERSION.RELEASE} API ${Build.VERSION.SDK_INT}")
        appendLine("Device ABI: ${Build.SUPPORTED_ABIS.joinToString()}")
        appendLine("Xray: ${sanitize(coreVersion)}")
        appendLine("Mode: $mode")
        appendLine("State: $state")
        appendLine("Profile: ${sanitize(profile)}")
        appendLine("Last error: ${sanitize(lastError)}")
        appendLine("--- Recent logs ---")
        append(recent())
    }

    fun sanitize(input: String): String = input
        .replace(Regex("(?i)(vless|vmess|trojan|ss|socks|https?)://[^\\s]+"), "<redacted-uri>")
        .replace(Regex("(?i)\\b(uuid|password|pass|token|privateKey|publicKey|shortId)\\b\\s*[:=]\\s*[^,}\\s]+"), "$1=<redacted>")
        .replace(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F-]{27,}"), "<redacted-uuid>")
}
