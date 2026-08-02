package com.trivox.client.util

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Process
import com.trivox.client.BuildConfig
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

object Diagnostics {
    enum class Level {
        ERROR,
        WARNING,
        INFO,
        DEBUG
    }

    private const val PREFS =
        "trivox_diagnostics"
    private const val MAX_RUNTIME_BYTES =
        1024 * 1024
    private const val MAX_CRASH_BYTES =
        2 * 1024 * 1024
    private const val MAX_XRAY_BYTES =
        1024 * 1024
    private const val MAX_ARCHIVES = 3
    private const val REPORT_TAIL_BYTES =
        512 * 1024
    private const val TRACE_LIMIT_BYTES =
        256 * 1024
    private const val TRACE_SAMPLE_BYTES =
        64 * 1024
    private const val MAX_EXIT_RECORDS = 8
    private const val RECENT_NATIVE_CRASH_MS =
        30 * 60 * 1000L

    private val initialized =
        AtomicBoolean(false)
    private val handlingCrash =
        AtomicBoolean(false)
    private val lock = Any()

    private lateinit var appContext:
        Context
    private lateinit var diagnosticsDir:
        File
    private lateinit var runtimeFile:
        File
    private lateinit var crashFile:
        File
    private lateinit var xrayFile:
        File

    private var previousHandler:
        Thread.UncaughtExceptionHandler? =
        null

    @Volatile
    var debugEnabled = false

    fun initialize(context: Context) {
        if (
            !initialized.compareAndSet(
                false,
                true
            )
        ) {
            return
        }

        appContext =
            context.applicationContext
        diagnosticsDir =
            File(
                appContext.filesDir,
                "diagnostics"
            ).apply {
                mkdirs()
            }
        runtimeFile =
            File(
                diagnosticsDir,
                "runtime.log"
            )
        crashFile =
            File(
                diagnosticsDir,
                "crashes.log"
            )
        xrayFile =
            File(
                diagnosticsDir,
                "xray-error.log"
            )

        repairLegacyCrashLog()
        installExceptionHandler()
        inspectPreviousNativeCheckpoint()
        collectHistoricalExitReasons()

        info(
            "Application session started; " +
                "pid=${Process.myPid()}, " +
                "version=${BuildConfig.VERSION_NAME}, " +
                "code=${BuildConfig.VERSION_CODE}, " +
                "git=${BuildConfig.GIT_SHA}"
        )
    }

    fun log(
        level: Level,
        message: String
    ) {
        if (
            level == Level.DEBUG &&
            !debugEnabled
        ) {
            return
        }

        if (!initialized.get()) {
            return
        }

        runCatching {
            synchronized(lock) {
                val line =
                    "${timestamp()} " +
                        "${level.name} " +
                        "${sanitize(message)}\n"

                rotateIfNeeded(
                    runtimeFile,
                    MAX_RUNTIME_BYTES,
                    line.length
                )
                runtimeFile.appendText(
                    line,
                    Charsets.UTF_8
                )
            }
        }
    }

    fun error(message: String) =
        log(Level.ERROR, message)

    fun warning(message: String) =
        log(Level.WARNING, message)

    fun info(message: String) =
        log(Level.INFO, message)

    fun debug(message: String) =
        log(Level.DEBUG, message)

    fun activity(
        event: String,
        activityName: String
    ) {
        debug(
            "Activity $event: $activityName"
        )
    }

    fun recordThrowable(
        category: String,
        throwable: Throwable
    ) {
        if (!initialized.get()) {
            return
        }

        val stack = StringWriter().also {
            throwable.printStackTrace(
                PrintWriter(it)
            )
        }.toString()

        appendCrash(
            buildString {
                appendLine(
                    "JVM throwable: " +
                        sanitize(category)
                )
                appendLine(
                    "Thread: " +
                        Thread.currentThread().name
                )
                append(
                    sanitize(stack)
                )
            }
        )

        error(
            "$category: " +
                (
                    throwable.cause?.message
                        ?: throwable.message
                        ?: throwable
                            .javaClass
                            .simpleName
                    )
        )
    }

    fun nativeCheckpoint(
        operation: String,
        phase: String,
        detail: String = ""
    ) {
        if (!initialized.get()) {
            return
        }

        appContext
            .getSharedPreferences(
                PREFS,
                Context.MODE_PRIVATE
            )
            .edit()
            .putString(
                "native_operation",
                sanitize(operation)
            )
            .putString(
                "native_phase",
                sanitize(phase)
            )
            .putString(
                "native_detail",
                sanitize(detail)
            )
            .putString(
                "native_thread",
                Thread.currentThread().name
            )
            .putLong(
                "native_timestamp",
                System.currentTimeMillis()
            )
            .commit()

        info(
            "Native checkpoint: " +
                "$operation/$phase " +
                detail
        )
    }

    fun xrayErrorLogPath(): String {
        check(initialized.get()) {
            "Diagnostics is not initialized"
        }

        synchronized(lock) {
            rotateIfNeeded(
                xrayFile,
                MAX_XRAY_BYTES,
                0
            )
        }

        return xrayFile.absolutePath
    }

    fun hasRecentNativeCrash(): Boolean {
        if (!initialized.get()) {
            return false
        }

        val at: Long =
            appContext
                .getSharedPreferences(
                    PREFS,
                    Context.MODE_PRIVATE
                )
                .getLong(
                    "last_native_crash_at",
                    0L
                )

        return at > 0 &&
            System.currentTimeMillis() - at <
            RECENT_NATIVE_CRASH_MS
    }

    fun markNativeSessionStable() {
        if (!initialized.get()) {
            return
        }

        appContext
            .getSharedPreferences(
                PREFS,
                Context.MODE_PRIVATE
            )
            .edit()
            .remove(
                "last_native_crash_at"
            )
            .apply()

        info(
            "Native session passed the " +
                "stability window"
        )
    }

    fun recent(): String {
        if (!initialized.get()) {
            return ""
        }

        return synchronized(lock) {
            readTail(
                runtimeFile,
                REPORT_TAIL_BYTES
            )
        }
    }

    fun crashHistory(): String {
        if (!initialized.get()) {
            return ""
        }

        return synchronized(lock) {
            readTail(
                crashFile,
                REPORT_TAIL_BYTES
            )
        }
    }

    fun xrayHistory(): String {
        if (!initialized.get()) {
            return ""
        }

        return synchronized(lock) {
            readTail(
                xrayFile,
                REPORT_TAIL_BYTES
            )
        }
    }

    fun clear() {
        if (!initialized.get()) {
            return
        }

        synchronized(lock) {
            diagnosticsDir
                .listFiles()
                ?.filter {
                    it.name.endsWith(".log") ||
                        it.name.contains(
                            ".log."
                        ) ||
                        it.name.startsWith(
                            "legacy-native-trace-"
                        )
                }
                ?.forEach {
                    runCatching {
                        it.delete()
                    }
                }

            runtimeFile.createNewFile()
            crashFile.createNewFile()
            xrayFile.createNewFile()
        }
    }

    fun report(
        coreVersion: String,
        mode: String,
        state: String,
        profile: String,
        lastError: String
    ): String =
        buildString {
            appendLine(
                "Trivox diagnostics"
            )
            appendLine(
                "App: " +
                    "${BuildConfig.VERSION_NAME} " +
                    "(${BuildConfig.VERSION_CODE})"
            )
            appendLine(
                "Git: ${BuildConfig.GIT_SHA}"
            )
            appendLine(
                "Android: " +
                    "${Build.VERSION.RELEASE} " +
                    "API ${Build.VERSION.SDK_INT}"
            )
            appendLine(
                "Device: " +
                    "${Build.MANUFACTURER} " +
                    Build.MODEL
            )
            appendLine(
                "ABI: " +
                    Build.SUPPORTED_ABIS
                        .joinToString()
            )
            appendLine(
                "Xray: ${sanitize(coreVersion)}"
            )
            appendLine(
                "Mode: ${sanitize(mode)}"
            )
            appendLine(
                "State: ${sanitize(state)}"
            )
            appendLine(
                "Profile: ${sanitize(profile)}"
            )
            appendLine(
                "Last error: " +
                    sanitize(lastError)
            )
            appendLine(
                "Last native checkpoint: " +
                    lastNativeCheckpoint()
            )
            appendLine()
            appendLine(
                "--- Process exits and crashes ---"
            )
            appendLine(
                crashHistory()
                    .ifBlank {
                        "No recorded crash history."
                    }
            )
            appendLine()
            appendLine(
                "--- Xray warning/error log ---"
            )
            appendLine(
                sanitize(
                    xrayHistory()
                ).ifBlank {
                    "No Xray warning/error log."
                }
            )
            appendLine()
            appendLine(
                "--- Runtime breadcrumbs ---"
            )
            append(
                recent()
                    .ifBlank {
                        "No runtime breadcrumbs."
                    }
            )
        }

    fun sanitize(input: String): String =
        input
            .replace(
                Regex(
                    "(?i)(vless|vmess|" +
                        "trojan|ss|socks|" +
                        "https?)://[^\\s]+"
                ),
                "<redacted-uri>"
            )
            .replace(
                Regex(
                    "(?i)\\b(uuid|password|" +
                        "pass|token|privateKey|" +
                        "publicKey|shortId)\\b" +
                        "\\s*[:=]\\s*" +
                        "[^,}\\s]+"
                ),
                "\$1=<redacted>"
            )
            .replace(
                Regex(
                    "[0-9a-fA-F]{8}-" +
                        "[0-9a-fA-F-]{27,}"
                ),
                "<redacted-uuid>"
            )

    private fun installExceptionHandler() {
        previousHandler =
            Thread
                .getDefaultUncaughtExceptionHandler()

        Thread
            .setDefaultUncaughtExceptionHandler {
                    thread,
                    throwable ->

                if (
                    handlingCrash.compareAndSet(
                        false,
                        true
                    )
                ) {
                    val writer =
                        StringWriter()

                    throwable.printStackTrace(
                        PrintWriter(writer)
                    )

                    appendCrash(
                        buildString {
                            appendLine(
                                "Uncaught JVM crash"
                            )
                            appendLine(
                                "Thread: " +
                                    "${thread.name} " +
                                    "(${thread.id})"
                            )
                            appendLine(
                                "Native checkpoint: " +
                                    lastNativeCheckpoint()
                            )
                            append(
                                sanitize(
                                    writer.toString()
                                )
                            )
                        }
                    )
                }

                previousHandler
                    ?.uncaughtException(
                        thread,
                        throwable
                    )
                    ?: run {
                        Process.killProcess(
                            Process.myPid()
                        )
                    }
            }
    }

    private fun inspectPreviousNativeCheckpoint() {
        val prefs =
            appContext
                .getSharedPreferences(
                    PREFS,
                    Context.MODE_PRIVATE
                )

        val phase =
            prefs.getString(
                "native_phase",
                ""
            ).orEmpty()

        if (phase != "begin") {
            return
        }

        appendCrash(
            buildString {
                appendLine(
                    "Previous process ended while " +
                        "a native operation was active"
                )
                appendLine(
                    "Operation: " +
                        prefs.getString(
                            "native_operation",
                            "unknown"
                        )
                )
                appendLine(
                    "Thread: " +
                        prefs.getString(
                            "native_thread",
                            "unknown"
                        )
                )
                appendLine(
                    "Detail: " +
                        prefs.getString(
                            "native_detail",
                            ""
                        )
                )
                appendLine(
                    "Started at: " +
                        prefs.getLong(
                            "native_timestamp",
                            0L
                        )
                )
            }
        )
    }

    private fun collectHistoricalExitReasons() {
        if (
            Build.VERSION.SDK_INT <
            Build.VERSION_CODES.R
        ) {
            return
        }

        runCatching {
            val manager =
                appContext
                    .getSystemService(
                        ActivityManager::class.java
                    )

            val prefs =
                appContext
                    .getSharedPreferences(
                        PREFS,
                        Context.MODE_PRIVATE
                    )

            val lastSeen =
                prefs.getLong(
                    "last_exit_timestamp",
                    0L
                )

            val exits:
                List<ApplicationExitInfo> =
                manager
                    .getHistoricalProcessExitReasons(
                        appContext.packageName,
                        0,
                        MAX_EXIT_RECORDS
                    )
                    .filter {
                        it.timestamp > lastSeen
                    }
                    .sortedBy {
                        it.timestamp
                    }

            var newest = lastSeen

            exits.forEach {
                    info: ApplicationExitInfo ->
                newest =
                    maxOf(
                        newest,
                        info.timestamp
                    )

                val traceSummary: String =
                    runCatching {
                        info.traceInputStream
                            ?.use {
                                input: InputStream ->
                                val (
                                    bytes,
                                    truncated
                                ) = readLimitedBytes(
                                    input,
                                    TRACE_LIMIT_BYTES
                                )

                                summarizeExitTrace(
                                    bytes,
                                    truncated
                                )
                            }
                    }.getOrNull()
                        .orEmpty()

                appendCrash(
                    buildString {
                        appendLine(
                            "Historical process exit"
                        )
                        appendLine(
                            "Timestamp: " +
                                info.timestamp
                        )
                        appendLine(
                            "Reason: " +
                                reasonLabel(
                                    info.reason
                                ) +
                                " (${info.reason})"
                        )
                        appendLine(
                            "Status: ${info.status}"
                        )
                        appendLine(
                            "Importance: " +
                                info.importance
                        )
                        appendLine(
                            "Process: " +
                                info.processName
                        )
                        appendLine(
                            "Description: " +
                                info.description
                                    .orEmpty()
                        )
                        appendLine(
                            "PSS/RSS: " +
                                "${info.pss}/" +
                                info.rss
                        )

                        if (
                            traceSummary.isNotBlank()
                        ) {
                            appendLine(
                                "--- Exit trace summary ---"
                            )
                            append(
                                traceSummary
                            )
                        }
                    }
                )

                if (
                    info.reason ==
                    ApplicationExitInfo
                        .REASON_CRASH_NATIVE
                ) {
                    prefs
                        .edit()
                        .putLong(
                            "last_native_crash_at",
                            info.timestamp
                        )
                        .apply()
                }
            }

            if (newest > lastSeen) {
                prefs
                    .edit()
                    .putLong(
                        "last_exit_timestamp",
                        newest
                    )
                    .apply()
            }
        }.onFailure {
            warning(
                "Unable to read historical " +
                    "process exits: " +
                    it.message
            )
        }
    }

    private fun lastNativeCheckpoint():
        String {
        if (!initialized.get()) {
            return "unavailable"
        }

        val prefs =
            appContext
                .getSharedPreferences(
                    PREFS,
                    Context.MODE_PRIVATE
                )

        val operation =
            prefs.getString(
                "native_operation",
                "none"
            )
        val phase =
            prefs.getString(
                "native_phase",
                "none"
            )
        val thread =
            prefs.getString(
                "native_thread",
                "unknown"
            )
        val at =
            prefs.getLong(
                "native_timestamp",
                0L
            )

        return "$operation/$phase " +
            "thread=$thread at=$at"
    }

    private fun appendCrash(text: String) {
        if (!initialized.get()) {
            return
        }

        runCatching {
            synchronized(lock) {
                val value =
                    buildString {
                        appendLine()
                        appendLine(
                            "===== ${timestamp()} ====="
                        )
                        appendLine(text.trim())
                    }

                rotateIfNeeded(
                    crashFile,
                    MAX_CRASH_BYTES,
                    value.length
                )
                crashFile.appendText(
                    value,
                    Charsets.UTF_8
                )
            }
        }
    }

    private fun rotateIfNeeded(
        file: File,
        maxBytes: Int,
        incomingChars: Int
    ) {
        if (
            file.length() +
            incomingChars <
            maxBytes
        ) {
            return
        }

        for (
            index in MAX_ARCHIVES downTo 1
        ) {
            val current =
                if (index == 1) {
                    file
                } else {
                    File(
                        file.parentFile,
                        "${file.name}." +
                            "${index - 1}"
                    )
                }

            val next =
                File(
                    file.parentFile,
                    "${file.name}.$index"
                )

            if (current.exists()) {
                if (next.exists()) {
                    next.delete()
                }
                current.renameTo(next)
            }
        }

        file.createNewFile()
    }

    private fun readTail(
        file: File,
        maxChars: Int
    ): String =
        runCatching {
            if (!file.isFile) {
                ""
            } else {
                file.readText(
                    Charsets.UTF_8
                ).takeLast(maxChars)
            }
        }.getOrDefault("")

    private fun repairLegacyCrashLog() {
        if (
            !crashFile.isFile ||
            crashFile.length() == 0L
        ) {
            return
        }

        val sample =
            runCatching {
                crashFile
                    .inputStream()
                    .use {
                        readLimitedBytes(
                            it,
                            TRACE_SAMPLE_BYTES
                        ).first
                    }
            }.getOrNull()
                ?: return

        if (!looksBinary(sample)) {
            return
        }

        val archive =
            File(
                diagnosticsDir,
                "legacy-native-trace-" +
                    System.currentTimeMillis() +
                    ".bin"
            )

        val archived =
            runCatching {
                if (
                    !crashFile.renameTo(
                        archive
                    )
                ) {
                    crashFile.copyTo(
                        archive,
                        overwrite = true
                    )
                    crashFile.writeBytes(
                        ByteArray(0)
                    )
                }

                true
            }.getOrDefault(false)

        crashFile.createNewFile()

        appendCrash(
            buildString {
                appendLine(
                    "Legacy diagnostic log migration"
                )
                appendLine(
                    "A previous Android native " +
                        "tombstone was stored as raw " +
                        "binary data and made the text " +
                        "report unreadable."
                )
                appendLine(
                    if (archived) {
                        "The raw trace was quarantined " +
                            "inside private app storage."
                    } else {
                        "The unreadable raw trace could " +
                            "not be preserved."
                    }
                )
                appendLine(
                    "Future native exits are recorded " +
                        "as compact text summaries."
                )
            }
        )
    }

    private fun readLimitedBytes(
        input: InputStream,
        limit: Int
    ): Pair<ByteArray, Boolean> {
        val output =
            ByteArrayOutputStream()
        val buffer =
            ByteArray(8192)
        var remaining = limit + 1

        while (remaining > 0) {
            val read =
                input.read(
                    buffer,
                    0,
                    minOf(
                        buffer.size,
                        remaining
                    )
                )

            if (read <= 0) {
                break
            }

            output.write(
                buffer,
                0,
                read
            )
            remaining -= read
        }

        val captured =
            output.toByteArray()
        val truncated =
            captured.size > limit
        val result =
            if (truncated) {
                captured.copyOf(limit)
            } else {
                captured
            }

        return result to truncated
    }

    private fun summarizeExitTrace(
        bytes: ByteArray,
        truncated: Boolean
    ): String {
        if (bytes.isEmpty()) {
            return ""
        }

        if (!looksBinary(bytes)) {
            return sanitize(
                bytes.toString(
                    Charsets.UTF_8
                )
            ).let {
                if (truncated) {
                    "$it\n[Trace truncated]"
                } else {
                    it
                }
            }
        }

        val symbols =
            extractReadableSymbols(bytes)
        val digest =
            MessageDigest
                .getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") {
                    "%02x".format(
                        it.toInt() and 0xff
                    )
                }

        return buildString {
            appendLine(
                "[Binary Android tombstone omitted " +
                    "from the text report]"
            )
            appendLine(
                "Captured bytes: ${bytes.size}" +
                    if (truncated) {
                        " (truncated)"
                    } else {
                        ""
                    }
            )
            appendLine(
                "Captured SHA-256: $digest"
            )

            if (symbols.isNotEmpty()) {
                appendLine(
                    "Readable crash markers:"
                )
                symbols.forEach {
                    appendLine(
                        "- ${sanitize(it)}"
                    )
                }
            } else {
                appendLine(
                    "No reliable readable symbol was " +
                        "found in the captured trace."
                )
            }
        }.trimEnd()
    }

    private fun looksBinary(
        bytes: ByteArray
    ): Boolean {
        if (bytes.isEmpty()) {
            return false
        }

        var controls = 0

        bytes.forEach {
            val value =
                it.toInt() and 0xff

            if (value == 0) {
                return true
            }

            if (
                value < 32 &&
                value != 9 &&
                value != 10 &&
                value != 13
            ) {
                controls += 1
            }
        }

        return controls * 20 >
            bytes.size
    }

    private fun extractReadableSymbols(
        bytes: ByteArray
    ): List<String> {
        val candidates =
            ArrayList<String>()
        val current =
            StringBuilder()

        fun flush() {
            if (current.length >= 4) {
                candidates +=
                    current.toString()
                        .trim()
            }
            current.setLength(0)
        }

        bytes.forEach {
            val value =
                it.toInt() and 0xff

            if (value in 32..126) {
                current.append(
                    value.toChar()
                )
            } else {
                flush()
            }
        }
        flush()

        val important =
            Regex(
                "(?i)(SIG[A-Z]+|" +
                    "libgojni|runtime\\.|" +
                    "panic|fatal|abort|" +
                    "backtrace|com\\.trivox|" +
                    "Thread-|pool-|xray)"
            )

        return candidates
            .asSequence()
            .filter {
                important.containsMatchIn(it)
            }
            .map {
                it.take(240)
            }
            .distinct()
            .take(32)
            .toList()
    }

    private fun reasonLabel(
        reason: Int
    ): String =
        when (reason) {
            0 -> "UNKNOWN"
            1 -> "EXIT_SELF"
            2 -> "SIGNALED"
            3 -> "LOW_MEMORY"
            4 -> "CRASH"
            5 -> "CRASH_NATIVE"
            6 -> "ANR"
            7 -> "INITIALIZATION_FAILURE"
            8 -> "PERMISSION_CHANGE"
            9 -> "EXCESSIVE_RESOURCE_USAGE"
            10 -> "USER_REQUESTED"
            11 -> "USER_STOPPED"
            12 -> "DEPENDENCY_DIED"
            13 -> "OTHER"
            14 -> "FREEZER"
            15 -> "PACKAGE_STATE_CHANGE"
            16 -> "PACKAGE_UPDATED"
            else -> "REASON_$reason"
        }

    private fun timestamp(): String =
        SimpleDateFormat(
            "yyyy-MM-dd HH:mm:ss.SSS",
            Locale.US
        ).format(Date())
}
