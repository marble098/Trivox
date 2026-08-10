package com.trivox.client.network

// TRIVOX_P5_FAST_XRAY_RUNTIME

import java.net.Inet6Address
import java.net.InetAddress
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Resolves only the selected proxy endpoint before Xray depends on its own DNS.
 * Results are process-memory hints only and are never persisted.
 *
 * A short negative cache avoids repeatedly paying a full system-DNS timeout
 * when Android's resolver is temporarily broken. A fresh daemon worker is still
 * used on cache misses because InetAddress may ignore Thread.interrupt on some
 * devices; this prevents one poisoned resolver from blocking later connects.
 */
internal object EndpointBootstrapResolver {
    private data class CacheEntry(
        val addresses: List<String>,
        val storedAt: Long
    )

    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private val negativeCache = ConcurrentHashMap<String, Long>()

    fun resolve(host: String, timeoutMs: Long = 950L): List<String> {
        val clean = host.trim().removePrefix("[").removeSuffix("]")
        if (clean.isBlank()) return emptyList()
        if (isIpLiteral(clean)) return listOf(clean.substringBefore('%').lowercase(Locale.ROOT))

        val key = clean.lowercase(Locale.ROOT)
        val now = System.currentTimeMillis()

        cache[key]
            ?.takeIf { now - it.storedAt <= CACHE_TTL_MS }
            ?.let { return it.addresses }

        negativeCache[key]
            ?.takeIf { now - it <= NEGATIVE_CACHE_TTL_MS }
            ?.let { return emptyList() }

        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "trivox-bootstrap-dns").apply { isDaemon = true }
        }
        val future = executor.submit<List<String>> {
            InetAddress.getAllByName(clean)
                .asSequence()
                .mapNotNull { it.hostAddress }
                .map { it.substringBefore('%').lowercase(Locale.ROOT) }
                .filter(String::isNotBlank)
                .distinct()
                .take(MAX_ADDRESSES)
                .toList()
        }

        return try {
            future.get(timeoutMs.coerceIn(250L, 3_000L), TimeUnit.MILLISECONDS)
                .also { addresses ->
                    if (addresses.isNotEmpty()) {
                        cache[key] = CacheEntry(addresses, now)
                        negativeCache.remove(key)
                    } else {
                        negativeCache[key] = now
                    }
                    trimCaches(now)
                }
        } catch (_: Throwable) {
            future.cancel(true)
            negativeCache[key] = now
            trimCaches(now)
            emptyList()
        } finally {
            executor.shutdownNow()
        }
    }

    internal fun isIpLiteral(value: String): Boolean {
        val clean = value.trim()
            .removePrefix("[")
            .removeSuffix("]")
            .substringBefore('%')

        val ipv4 = clean.split('.')
            .takeIf { it.size == 4 }
            ?.all { part ->
                part.isNotEmpty() &&
                    part.length <= 3 &&
                    part.all(Char::isDigit) &&
                    (part.toIntOrNull() ?: -1) in 0..255
            } == true
        if (ipv4) return true
        if (':' !in clean || !clean.matches(Regex("[0-9a-fA-F:.]+"))) return false

        return runCatching {
            InetAddress.getByName(clean) is Inet6Address
        }.getOrDefault(false)
    }

    private fun trimCaches(now: Long) {
        if (cache.size > MAX_CACHE_ENTRIES) {
            cache.entries.forEach { entry ->
                if (now - entry.value.storedAt > CACHE_TTL_MS) {
                    cache.remove(entry.key, entry.value)
                }
            }
            trimArbitrary(cache)
        }
        if (negativeCache.size > MAX_CACHE_ENTRIES) {
            negativeCache.entries.forEach { entry ->
                if (now - entry.value > NEGATIVE_CACHE_TTL_MS) {
                    negativeCache.remove(entry.key, entry.value)
                }
            }
            trimArbitrary(negativeCache)
        }
    }

    private fun <T> trimArbitrary(map: ConcurrentHashMap<String, T>) {
        if (map.size <= MAX_CACHE_ENTRIES) return
        val iterator = map.keys.iterator()
        while (map.size > MAX_CACHE_ENTRIES && iterator.hasNext()) {
            map.remove(iterator.next())
        }
    }

    private const val MAX_ADDRESSES = 4
    private const val MAX_CACHE_ENTRIES = 256
    private const val CACHE_TTL_MS = 5 * 60_000L
    private const val NEGATIVE_CACHE_TTL_MS = 8_000L
}
