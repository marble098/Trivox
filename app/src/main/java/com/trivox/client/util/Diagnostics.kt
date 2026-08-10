package com.trivox.client.util

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock
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
import java.io.InterruptedIOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

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
    private const val MAX_THROWABLE_STACK_CHARS =
        96 * 1024
    private const val THROWABLE_DEDUP_WINDOW_MS =
        60_000L
    private const val LOG_FLUSH_TIMEOUT_MS =
        750L
    private const val EXIT_REASON_USER_REQUESTED = 10
    private const val EXIT_REASON_USER_STOPPED = 11
    private const val EXIT_REASON_OTHER = 13
    private const val EXIT_REASON_PACKAGE_STATE_CHANGE = 15
    private const val EXIT_REASON_PACKAGE_UPDATED = 16

    private val initialized =
        AtomicBoolean(false)
    private val handlingCrash =
        AtomicBoolean(false)
    private val sessionErrors = AtomicInteger(0)
    private val sessionWarnings = AtomicInteger(0)
    @Volatile private var sessionStartedAt = 0L
    @Volatile private var sessionStartedElapsed = 0L
    private val lock = Any()
    private val throwableFingerprints =
        ConcurrentHashMap<String, Long>()
    private val writer =
        ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(128),
            { runnable ->
                Thread(
                    runnable,
                    "trivox-diagnostics-writer"
                ).apply {
                    isDaemon = true
                }
            },
            ThreadPoolExecutor
                .DiscardOldestPolicy()
        )

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
        sessionStartedAt = System.currentTimeMillis()
        sessionStartedElapsed = SystemClock.elapsedRealtime()
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

        when (level) {
            Level.ERROR -> sessionErrors.incrementAndGet()
            Level.WARNING -> sessionWarnings.incrementAndGet()
            else -> Unit
        }

        val line =
            "${timestamp()} " +
                "${level.name} " +
                "${sanitize(message)}\n"

        submitRuntimeWrite {
            synchronized(lock) {
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

        if (isCancellation(throwable)) {
            preserveInterruptedStatus(throwable)
            debug(
                "$category cancelled"
            )
            return
        }

        val fingerprint =
            throwableFingerprint(
                category,
                throwable
            )

        if (
            shouldSuppressThrowable(
                fingerprint
            )
        ) {
            return
        }

        val stack = StringWriter().also {
            throwable.printStackTrace(
                PrintWriter(it)
            )
        }.toString()
            .take(
                MAX_THROWABLE_STACK_CHARS
            )

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

        val checkpointMessage =
            "Native checkpoint: " +
                "$operation/$phase " +
                detail

        if (
            phase.equals(
                "failed",
                ignoreCase = true
            )
        ) {
            warning(checkpointMessage)
        } else {
            debug(checkpointMessage)
        }
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

        debug(
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

        flushRuntimeWrites()

        sessionWarnings.set(0)
        sessionErrors.set(0)
        sessionStartedAt = System.currentTimeMillis()
        sessionStartedElapsed = SystemClock.elapsedRealtime()

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
    ): String {
        flushRuntimeWrites()

        return buildString {
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
            appendLine("--- Current build/session health ---")
            appendLine("Session started: " + formatTimestamp(sessionStartedAt))
            appendLine(
                "Session uptime: " + formatDurationMs(
                    (SystemClock.elapsedRealtime() - sessionStartedElapsed).coerceAtLeast(0L)
                )
            )
            appendLine("Session warnings/errors: ${sessionWarnings.get()}/${sessionErrors.get()}")
            appendLine("Threads: ${Thread.activeCount()}")
            val runtime = Runtime.getRuntime()
            val usedMiB = (runtime.totalMemory() - runtime.freeMemory()) / (1024L * 1024L)
            val maxMiB = runtime.maxMemory() / (1024L * 1024L)
            appendLine("Java heap: ${usedMiB}MiB/${maxMiB}MiB")
            appendLine(xrayFailureSummary())
            appendLine()
            appendLine("--- Process exits and crashes ---")
            appendLine(
                "Note: historical exits below can belong to earlier installed builds. " +
                    "Use Git/version above and current-session breadcrumbs to distinguish old tombstones."
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
    }

    private fun xrayFailureSummary(): String {
        val value = xrayHistory()
        if (value.isBlank()) return "Xray summary: no recorded warning/error log"
        fun count(token: String): Int =
            Regex(Regex.escape(token), RegexOption.IGNORE_CASE).findAll(value).count()
        return "Xray summary: dns_timeout=${count("context deadline exceeded")}, " +
            "closed_pipe=${count("closed pipe")}, " +
            "tun_refused=${count("proxy/tun: connection was refused")}"
    }

    private fun formatTimestamp(value: Long): String =
        if (value <= 0L) "unknown" else
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(value))

    private fun formatDurationMs(value: Long): String {
        val seconds = value.coerceAtLeast(0L) / 1000L
        val hours = seconds / 3600L
        val minutes = (seconds % 3600L) / 60L
        val remain = seconds % 60L
        return if (hours > 0L) {
            String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, remain)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, remain)
        }
    }

    private val SENSITIVE_KEY_NAMES = listOf(
        "uuid", "password", "pass", "pwd", "passphrase",
        "token", "accessToken", "access_token",
        "refreshToken", "refresh_token",
        "sessionToken", "session_token",
        "privateKey", "private_key",
        "publicKey", "public_key",
        "shortId", "short_id",
        "authorization", "auth",
        "psk", "presharedKey", "preshared_key",
        "secret", "secretKey", "secret_key",
        "apiKey", "api_key",
        "clientSecret", "client_secret",
        "cookie", "bearer", "seed", "mnemonic"
    )

    private val URI_REDACT_REGEX = Regex(
        "(?i)(vless|vmess|" +
            "trojan|ss|socks|ssh|" +
            "wireguard|https?)://[^\\s\"'<>]+"
    )

    private val PEM_PRIVATE_KEY_REGEX = Regex(
        "-----BEGIN [^-]*PRIVATE KEY-----" +
            "[\\s\\S]*?" +
            "-----END [^-]*PRIVATE KEY-----"
    )

    private val WIREGUARD_INI_KEY_REGEX = Regex(
        "(?im)^(\\s*(?:PrivateKey|PresharedKey)\\s*=\\s*).+$"
    )

    private val SENSITIVE_JSON_KV_REGEX = Regex(
        "(?i)\"?\\b(" +
            SENSITIVE_KEY_NAMES.joinToString("|") { Regex.escape(it) } +
            ")\\b\"?\\s*[:=]\\s*\"?([^,}\"\\n]+?)\"?(?=[,}\"\\n]|$)"
    )

    private val BARE_UUID_REGEX = Regex(
        "[0-9a-fA-F]{8}-" +
            "[0-9a-fA-F-]{27,}"
    )

    fun sanitize(input: String): String =
        input
            .replace(PEM_PRIVATE_KEY_REGEX, "<redacted-private-key>")
            .replace(URI_REDACT_REGEX, "<redacted-uri>")
            .replace(WIREGUARD_INI_KEY_REGEX, "\$1<redacted>")
            .replace(SENSITIVE_JSON_KV_REGEX, "\$1=<redacted>")
            .replace(BARE_UUID_REGEX, "<redacted-uuid>")

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
                                "Thread: ${thread.name}"
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

        collectHistoricalExitReasonsApi30()
    }

    @android.annotation.TargetApi(
        Build.VERSION_CODES.R
    )
    private fun collectHistoricalExitReasonsApi30() {
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

            val allExits:
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
            val exits =
                allExits.filter { info ->
                    isActionableExit(
                        reason = info.reason,
                        descriptionValue =
                            info.description
                                .orEmpty()
                    )
                }
            var newest =
                allExits
                    .maxOfOrNull {
                        it.timestamp
                    }
                    ?: lastSeen

            exits.forEach { info ->
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
                            "Observed by build: ${BuildConfig.VERSION_NAME} " +
                                "(${BuildConfig.VERSION_CODE}) git=${BuildConfig.GIT_SHA}"
                        )
                        appendLine("This exit may have occurred in an earlier installed build.")
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

    private fun submitRuntimeWrite(
        action: () -> Unit
    ) {
        if (!initialized.get()) {
            return
        }

        runCatching {
            writer.execute {
                runCatching(action)
            }
        }
    }

    private fun flushRuntimeWrites() {
        if (!initialized.get()) {
            return
        }

        val latch =
            CountDownLatch(1)

        runCatching {
            writer.execute {
                latch.countDown()
            }
            latch.await(
                LOG_FLUSH_TIMEOUT_MS,
                TimeUnit.MILLISECONDS
            )
        }.onFailure {
            if (
                it is InterruptedException
            ) {
                Thread.currentThread()
                    .interrupt()
            }
        }
    }

    private fun isCancellation(
        throwable: Throwable
    ): Boolean {
        var current: Throwable? =
            throwable
        val visited =
            HashSet<Throwable>()

        while (
            current != null &&
            visited.add(current)
        ) {
            if (
                current is InterruptedException ||
                current is InterruptedIOException ||
                current is CancellationException ||
                current.javaClass.simpleName ==
                "SubscriptionCancelledException"
            ) {
                return true
            }

            current = current.cause
        }

        return false
    }

    private fun preserveInterruptedStatus(
        throwable: Throwable
    ) {
        var current: Throwable? =
            throwable
        val visited =
            HashSet<Throwable>()

        while (
            current != null &&
            visited.add(current)
        ) {
            if (
                current is InterruptedException ||
                current is InterruptedIOException
            ) {
                Thread.currentThread()
                    .interrupt()
                return
            }

            current = current.cause
        }
    }

    private fun throwableFingerprint(
        category: String,
        throwable: Throwable
    ): String {
        var root: Throwable =
            throwable
        val visited =
            HashSet<Throwable>()

        while (
            root.cause != null &&
            visited.add(root)
        ) {
            root = root.cause!!
        }

        return sanitize(category) +
            "|" +
            root.javaClass.name +
            "|" +
            sanitize(
                root.message.orEmpty()
            ).take(180)
    }

    private fun shouldSuppressThrowable(
        fingerprint: String
    ): Boolean {
        val now =
            System.currentTimeMillis()
        val previous =
            throwableFingerprints.put(
                fingerprint,
                now
            )

        if (
            throwableFingerprints.size >
            128
        ) {
            throwableFingerprints
                .entries
                .removeIf {
                    now - it.value >
                        THROWABLE_DEDUP_WINDOW_MS
                }
        }

        return previous != null &&
            now - previous <
            THROWABLE_DEDUP_WINDOW_MS
    }

    private fun isActionableExit(
        reason: Int,
        descriptionValue: String
    ): Boolean {
        if (
            reason in
            setOf(
                EXIT_REASON_USER_REQUESTED,
                EXIT_REASON_USER_STOPPED,
                EXIT_REASON_PACKAGE_STATE_CHANGE,
                EXIT_REASON_PACKAGE_UPDATED
            )
        ) {
            return false
        }

        if (
            reason ==
            EXIT_REASON_OTHER
        ) {
            val description =
                descriptionValue.lowercase(
                    Locale.ROOT
                )

            if (
                listOf(
                    "onekeyclean",
                    "swipeupclean",
                    "lockscreen",
                    "optimizationclean",
                    "securitycenter"
                ).any {
                    it in description
                }
            ) {
                return false
            }
        }

        return true
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
