package com.trivox.client.network

import android.content.Context
import com.trivox.client.config.ConfigParser
import com.trivox.client.core.ConnectionRuntime
import com.trivox.client.data.ConfigProfile
import com.trivox.client.data.ConfigRepository
import com.trivox.client.util.Diagnostics
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Lightweight public-source importer for Telegram's public web preview.
 * It never authenticates as the user, never reads private Telegram data and
 * never blocks a connection because channel membership cannot be verified.
 */
class CommunityConfigManager(context: Context) {
    data class SyncResult(
        val success: Boolean,
        val imported: Int = 0,
        val source: String = "",
        val error: String = ""
    )

    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun needsSync(username: String, maxAgeMs: Long = DEFAULT_SYNC_AGE_MS): Boolean {
        val normalized = normalizeUsername(username) ?: return false
        val lastUser = prefs.getString(KEY_USERNAME, "").orEmpty()
        val lastAt = prefs.getLong(KEY_LAST_SUCCESS, 0L)
        return lastUser != normalized || lastAt <= 0L ||
            System.currentTimeMillis() - lastAt > maxAgeMs
    }

    fun lastSuccessAt(): Long = prefs.getLong(KEY_LAST_SUCCESS, 0L)

    fun sync(username: String): SyncResult {
        val normalized = normalizeUsername(username)
            ?: return SyncResult(false, source = username, error = "invalid_channel_username")
        val sourceLabel = "@$normalized"
        return runCatching {
            val html = runCatching {
                fetchUrl("https://t.me/s/$normalized")
            }.getOrElse { directError ->
                Diagnostics.warning(
                    "Direct Telegram public preview failed; trying bounded text mirror: " +
                        (directError.message ?: directError.javaClass.simpleName)
                )
                fetchUrl("https://r.jina.ai/https://t.me/s/$normalized")
            }
            val links = extractConfigLinks(html)
            if (links.isEmpty()) {
                error("No supported public proxy links were found in $sourceLabel")
            }

            val parsed = ArrayList<ConfigProfile>()
            val seen = HashSet<String>()
            links.asReversed().forEach { link ->
                if (parsed.size >= MAX_IMPORTED_PROFILES) return@forEach
                runCatching { ConfigParser.parseText(link) }
                    .getOrNull()
                    ?.forEach { profile ->
                        val identity = profile.raw.trim().ifBlank { profile.outboundJson.trim() }
                        if (seen.add(identity) && parsed.size < MAX_IMPORTED_PROFILES) {
                            parsed += profile.copy(
                                group = "$sourceLabel • Community",
                                subscriptionId = COMMUNITY_SUBSCRIPTION_ID
                            )
                        }
                    }
            }
            if (parsed.isEmpty()) {
                error("Public channel content did not contain a valid Trivox configuration")
            }

            val repository = ConfigRepository(app)
            val activeCommunity = repository.find(ConnectionRuntime.current().profileId)
                ?.takeIf { it.subscriptionId == COMMUNITY_SUBSCRIPTION_ID }
            val safeProfiles = parsed.toMutableList()
            if (
                activeCommunity != null &&
                safeProfiles.none { it.raw.trim() == activeCommunity.raw.trim() }
            ) {
                // Never delete the profile object backing a live session during
                // a background refresh. It is naturally removed on a later sync.
                safeProfiles += activeCommunity
            }
            repository.replaceSubscription(
                COMMUNITY_SUBSCRIPTION_ID,
                safeProfiles
            )
            prefs.edit()
                .putString(KEY_USERNAME, normalized)
                .putLong(KEY_LAST_SUCCESS, System.currentTimeMillis())
                .putInt(KEY_LAST_COUNT, parsed.size)
                .apply()

            Diagnostics.info(
                "Community source synchronized; source=$sourceLabel, profiles=${parsed.size}"
            )
            SyncResult(true, parsed.size, sourceLabel)
        }.getOrElse { error ->
            Diagnostics.recordThrowable("Community source sync", error)
            SyncResult(
                success = false,
                source = sourceLabel,
                error = error.message ?: error.javaClass.simpleName
            )
        }
    }

    fun managedProfiles(): List<ConfigProfile> =
        ConfigRepository(app).all().filter {
            it.subscriptionId == COMMUNITY_SUBSCRIPTION_ID
        }

    fun clearManagedProfiles(): Int =
        ConfigRepository(app).deleteSubscription(COMMUNITY_SUBSCRIPTION_ID).also {
            prefs.edit().remove(KEY_LAST_SUCCESS).remove(KEY_LAST_COUNT).apply()
        }

    private fun fetchUrl(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.instanceFollowRedirects = true
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.useCaches = false
            connection.setRequestProperty("Accept", "text/html,application/xhtml+xml")
            connection.setRequestProperty("Accept-Language", "en-US,en;q=0.8")
            connection.setRequestProperty("User-Agent", USER_AGENT)
            val status = connection.responseCode
            if (status !in 200..299) {
                error("Community source endpoint returned HTTP $status")
            }
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val out = StringBuilder()
                val buffer = CharArray(16 * 1024)
                while (true) {
                    val read = reader.read(buffer)
                    if (read < 0) break
                    if (out.length + read > MAX_HTML_CHARS) {
                        error("Telegram public preview exceeded the safety limit")
                    }
                    out.append(buffer, 0, read)
                }
                out.toString()
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun extractConfigLinks(html: String): List<String> {
        val decoded = decodeHtmlEntities(html)
        val result = LinkedHashSet<String>()

        fun collect(value: String) {
            CONFIG_LINK.findAll(value).forEach { match ->
                if (result.size >= MAX_DISCOVERED_LINKS) return@forEach
                val candidate = match.value
                    .trim()
                    .trimEnd('.', ',', ';', ')', ']', '}', '”', '"', '\'')
                if (candidate.length in 8..MAX_LINK_CHARS) {
                    result += candidate
                }
            }
        }

        // Telegram often keeps the full config in an href attribute; scan raw
        // decoded HTML first, then the visible text as a second chance.
        collect(decoded)
        collect(
            decoded
                .replace(Regex("(?i)<br\\s*/?>"), "\n")
                .replace(Regex("<[^>]{1,512}>"), " ")
        )
        return result.toList()
    }

    private fun decodeHtmlEntities(value: String): String {
        var text = value
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&nbsp;", " ")
        text = NUMERIC_ENTITY.replace(text) { match ->
            val raw = match.groupValues[1]
            val codePoint = if (raw.startsWith("x", true)) {
                raw.substring(1).toIntOrNull(16)
            } else {
                raw.toIntOrNull()
            }
            codePoint?.takeIf { it in 0..0x10FFFF }
                ?.let { String(Character.toChars(it)) }
                ?: match.value
        }
        return text
    }

    companion object {
        const val DEFAULT_CHANNEL = "farahvpn"
        const val COMMUNITY_SUBSCRIPTION_ID = "community:telegram:public"
        const val DEFAULT_SYNC_AGE_MS = 6 * 60 * 60 * 1000L

        private const val PREFS = "trivox_community_source_v26"
        private const val KEY_USERNAME = "username"
        private const val KEY_LAST_SUCCESS = "last_success"
        private const val KEY_LAST_COUNT = "last_count"
        private const val MAX_HTML_CHARS = 2 * 1024 * 1024
        private const val MAX_DISCOVERED_LINKS = 160
        private const val MAX_IMPORTED_PROFILES = 60
        private const val MAX_LINK_CHARS = 16 * 1024
        private const val CONNECT_TIMEOUT_MS = 4_000
        private const val READ_TIMEOUT_MS = 6_000
        private const val USER_AGENT = "Mozilla/5.0 (Android) Trivox/26 CommunitySync"
        private val USERNAME = Regex("^[A-Za-z0-9_]{5,32}$")
        private val CONFIG_LINK = Regex(
            "(?i)(?:vless|vmess|trojan|ss|shadowsocks|hy2|hysteria2|hysteria|wg|wireguard|ssh|openssh)://[^\\s<>\\\"']+"
        )
        private val NUMERIC_ENTITY = Regex("&#(x?[0-9A-Fa-f]+);")

        fun normalizeUsername(value: String): String? = value
            .trim()
            .removePrefix("https://t.me/")
            .removePrefix("http://t.me/")
            .removePrefix("t.me/")
            .removePrefix("@")
            .substringBefore('/')
            .lowercase(Locale.ROOT)
            .takeIf(USERNAME::matches)
    }
}
