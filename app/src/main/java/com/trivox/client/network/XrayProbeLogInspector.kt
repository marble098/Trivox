package com.trivox.client.network

import java.io.File
import java.io.RandomAccessFile
import java.util.Locale

/** Extracts the most actionable failure from the bounded tail of one Xray run. */
internal object XrayProbeLogInspector {
    data class Mark(
        val path: String,
        val offset: Long
    )

    fun mark(file: File): Mark = Mark(
        path = file.absolutePath,
        offset = file.takeIf(File::isFile)?.length() ?: 0L
    )

    fun classifySince(mark: Mark): String? {
        val file = File(mark.path)
        if (!file.isFile) return null
        val start = mark.offset.coerceIn(0L, file.length())
        val available = (file.length() - start).coerceAtMost(MAX_READ_BYTES)
        if (available <= 0L) return null

        val text = runCatching {
            RandomAccessFile(file, "r").use { input ->
                input.seek(file.length() - available)
                val bytes = ByteArray(available.toInt())
                input.readFully(bytes)
                bytes.toString(Charsets.UTF_8)
            }
        }.getOrNull().orEmpty().lowercase(Locale.ROOT)

        return classify(text)
    }

    internal fun classify(text: String): String? = when {
        text.isBlank() -> null
        "received real certificate" in text -> "reality_mismatch"
        "failed to dial" in text && "websocket" in text -> "websocket_handshake"
        "connection reset by peer" in text -> "connection_reset"
        "invalid authentication" in text || "authentication failed" in text ->
            "authentication_failed"
        "failed to verify certificate" in text || "certificate" in text &&
            "unknown authority" in text -> "tls_certificate"
        "failed to retrieve response" in text &&
            "deadline exceeded" in text -> "dns_timeout"
        "no such host" in text || "failed to lookup" in text -> "dns_failure"
        "context deadline exceeded" in text || "i/o timeout" in text -> "timeout"
        "connection refused" in text -> "connection_refused"
        "failed to build outbound" in text ||
            "failed to build stream settings" in text ||
            "failed to unmarshal" in text -> "invalid_xray_config"
        else -> null
    }

    private const val MAX_READ_BYTES = 96L * 1024L
}
