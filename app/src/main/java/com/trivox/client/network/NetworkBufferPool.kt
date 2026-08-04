package com.trivox.client.network

import java.io.InputStream
import java.util.ArrayDeque
import java.util.Arrays

/**
 * Small bounded byte-array pool for short-lived app-side network reads.
 * Native proxy traffic stays inside libXray; this pool only reduces repeated
 * Kotlin/Java allocations and is cleared on Android memory pressure.
 */
object NetworkBufferPool {
    private const val MIN_BUFFER_KB = 8
    private const val MAX_BUFFER_KB = 256
    private const val MIN_CHUNK_BYTES = 8 * 1024
    private const val MAX_POOLED_BUFFER_BYTES = 256 * 1024
    private const val MAX_POOLED_BUFFERS = 4
    private const val MAX_READ_BYTES = 4 * 1024 * 1024

    private val buffers = ArrayDeque<ByteArray>(MAX_POOLED_BUFFERS)

    fun readUtf8Bounded(
        input: InputStream,
        maxBytes: Int,
        preferredBufferKb: Int
    ): String {
        require(maxBytes in 1..MAX_READ_BYTES) {
            "Invalid bounded network read size: $maxBytes"
        }

        val chunkSize = (
            preferredBufferKb.coerceIn(MIN_BUFFER_KB, MAX_BUFFER_KB) * 1024
        ).coerceAtMost(maxBytes).coerceAtLeast(MIN_CHUNK_BYTES)
        val chunk = acquire(chunkSize)
        val output = acquire(maxBytes)
        var total = 0

        return try {
            while (true) {
                val count = input.read(chunk, 0, chunkSize)
                if (count < 0) break
                if (count == 0) continue
                if (total + count > maxBytes) {
                    throw IllegalStateException(
                        "Network response exceeds $maxBytes bytes"
                    )
                }
                System.arraycopy(chunk, 0, output, total, count)
                total += count
            }
            String(output, 0, total, Charsets.UTF_8)
        } finally {
            release(chunk, chunkSize)
            release(output, total)
        }
    }

    @Synchronized
    fun clear() {
        buffers.forEach { Arrays.fill(it, 0.toByte()) }
        buffers.clear()
    }

    @Synchronized
    internal fun pooledCount(): Int = buffers.size

    @Synchronized
    private fun acquire(minimumSize: Int): ByteArray {
        var best: ByteArray? = null
        for (candidate in buffers) {
            val currentBest = best
            if (
                candidate.size >= minimumSize &&
                (currentBest == null || candidate.size < currentBest.size)
            ) {
                best = candidate
            }
        }
        val reusable = best
        if (reusable != null) {
            buffers.remove(reusable)
            return reusable
        }
        return ByteArray(normalizedCapacity(minimumSize))
    }

    @Synchronized
    private fun release(buffer: ByteArray, usedBytes: Int) {
        Arrays.fill(
            buffer,
            0,
            usedBytes.coerceIn(0, buffer.size),
            0.toByte()
        )
        if (
            buffer.size > MAX_POOLED_BUFFER_BYTES ||
            buffers.size >= MAX_POOLED_BUFFERS
        ) {
            return
        }
        buffers.addLast(buffer)
    }

    private fun normalizedCapacity(requested: Int): Int {
        var capacity = MIN_CHUNK_BYTES
        while (capacity < requested && capacity < MAX_POOLED_BUFFER_BYTES) {
            capacity = capacity shl 1
        }
        return capacity.coerceAtLeast(requested)
    }
}
