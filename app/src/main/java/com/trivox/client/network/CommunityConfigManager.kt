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
 * Lightweight community-source importer.
 *
 * Bootstrap order for the default source:
 *  1) Trivox-owned GitHub mirror
 *  2) jsDelivr mirror of the same Trivox cache branch
 *  3) Telegram public preview
 *  4) bounded text-rendering relay
 *  5) previously imported local cache
 *
 * This avoids making Telegram availability a prerequisite for first-run simple
 * mode in networks where t.me returns HTTP 451 or is otherwise filtered.
 */
class CommunityConfigManager(context: Context) {
    data class SyncResult(
        val success: Boolean,
        val imported: Int = 0,
        val source: String = "",
        val error: String = ""
    )

    private data class SourceCandidate(
        val label: String,
        val url: String
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
            val failures = ArrayList<String>()
            var selectedRoute = ""
            var links: List<String> = emptyList()

            for (candidate in sourceCandidates(normalized)) {
                val attempt = runCatching { fetchUrl(candidate.url) }
                if (attempt.isFailure) {
                    val error = attempt.exceptionOrNull()
                        ?: IllegalStateException("unknown_route_error")
                    val reason = compactFailure(error)
                    failures += "${candidate.label}=$reason"

                    if (reason.contains("HTTP 451", ignoreCase = true)) {
                        Diagnostics.warning(
                            "Community route ${candidate.label} is unavailable with HTTP 451; " +
                                "continuing with mirror/fallback routes"
                        )
                    } else {
                        Diagnostics.debug(
                            "Community route ${candidate.label} failed: $reason"
                        )
                    }
                    continue
                }

                val discovered = extractConfigLinks(attempt.getOrThrow())
                if (discovered.isEmpty()) {
                    failures += "${candidate.label}=no_supported_links"
                    Diagnostics.debug(
                        "Community route ${candidate.label} returned no supported links"
                    )
                    continue
                }

                links = discovered
                selectedRoute = candidate.label
                break
            }

            if (links.isEmpty()) {
                val cached = managedProfiles()
                if (cached.isNotEmpty()) {
                    Diagnostics.warning(
                        "All community network routes failed; preserving and using " +
                            "${cached.size} previously imported profiles"
                    )
                    return SyncResult(
                        success = true,
                        imported = cached.size,
                        source = "$sourceLabel • local cache"
                    )
                }

                error(
                    buildString {
                        append("Community sync unavailable after mirror and Telegram fallbacks")
                        if (failures.isNotEmpty()) {
                            append(": ")
                            append(
                                failures
                                    .take(MAX_REPORTED_FAILURES)
                                    .joinToString("; ")
                            )
                        }
                    }
                )
            }

            val parsed = ArrayList<ConfigProfile>()
            val seen = HashSet<String>()
            links.asReversed().forEach { link ->
                if (parsed.size >= MAX_IMPORTED_PROFILES) return@forEach
                runCatching { ConfigParser.parseText(link) }
                    .getOrNull()
                    ?.forEach { profile ->
                        val identity = profile.raw.trim().ifBlank {
                            profile.outboundJson.trim()
                        }
                        if (
                            seen.add(identity) &&
                            parsed.size < MAX_IMPORTED_PROFILES
                        ) {
                            parsed += profile.copy(
                                group = "$sourceLabel • Community",
                                subscriptionId = COMMUNITY_SUBSCRIPTION_ID
                            )
                        }
                    }
            }

            if (parsed.isEmpty()) {
                val cached = managedProfiles()
                if (cached.isNotEmpty()) {
                    Diagnostics.warning(
                        "Community route $selectedRoute contained no parseable profiles; " +
                            "keeping local cache"
                    )
                    return SyncResult(
                        success = true,
                        imported = cached.size,
                        source = "$sourceLabel • local cache"
                    )
                }
                error(
                    "Community source content did not contain a valid Trivox configuration"
                )
            }

            val repository = ConfigRepository(app)
            val activeCommunity = repository
                .find(ConnectionRuntime.current().profileId)
                ?.takeIf {
                    it.subscriptionId == COMMUNITY_SUBSCRIPTION_ID
                }

            val safeProfiles = parsed.toMutableList()
            if (
                activeCommunity != null &&
                safeProfiles.none {
                    it.raw.trim() == activeCommunity.raw.trim()
                }
            ) {
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
                .putString(KEY_LAST_ROUTE, selectedRoute)
                .apply()

            Diagnostics.info(
                "Community source synchronized; source=$sourceLabel, " +
                    "route=$selectedRoute, profiles=${parsed.size}"
            )

            SyncResult(
                success = true,
                imported = parsed.size,
                source = "$sourceLabel • $selectedRoute"
            )
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
        ConfigRepository(app)
            .deleteSubscription(COMMUNITY_SUBSCRIPTION_ID)
            .also {
                prefs.edit()
                    .remove(KEY_LAST_SUCCESS)
                    .remove(KEY_LAST_COUNT)
                    .remove(KEY_LAST_ROUTE)
                    .apply()
            }

    private fun sourceCandidates(username: String): List<SourceCandidate> =
        buildList {
            if (username == DEFAULT_CHANNEL) {
                add(
                    SourceCandidate(
                        label = "Trivox mirror",
                        url =
                            "https://raw.githubusercontent.com/" +
                                "$MIRROR_REPOSITORY/$MIRROR_BRANCH/community/" +
                                "$username.txt"
                    )
                )
                add(
                    SourceCandidate(
                        label = "CDN mirror",
                        url =
                            "https://cdn.jsdelivr.net/gh/" +
                                "$MIRROR_REPOSITORY@$MIRROR_BRANCH/community/" +
                                "$username.txt"
                    )
                )
            }

            add(
                SourceCandidate(
                    label = "Telegram",
                    url = "https://t.me/s/$username"
                )
            )
            add(
                SourceCandidate(
                    label = "Text relay",
                    url = "https://r.jina.ai/https://t.me/s/$username"
                )
            )
        }

    private fun fetchUrl(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.instanceFollowRedirects = true
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.useCaches = false
            connection.setRequestProperty(
                "Accept",
                "text/plain,text/html,application/xhtml+xml,*/*;q=0.5"
            )
            connection.setRequestProperty(
                "Accept-Language",
                "en-US,en;q=0.8"
            )
            connection.setRequestProperty(
                "Cache-Control",
                "no-cache"
            )
            connection.setRequestProperty(
                "User-Agent",
                USER_AGENT
            )

            val status = connection.responseCode
            if (status !in 200..299) {
                error("HTTP $status")
            }

            connection.inputStream
                .bufferedReader(Charsets.UTF_8)
                .use { reader ->
                    val out = StringBuilder()
                    val buffer = CharArray(16 * 1024)
                    while (true) {
                        val read = reader.read(buffer)
                        if (read < 0) break
                        if (out.length + read > MAX_HTML_CHARS) {
                            error("community_payload_too_large")
                        }
                        out.append(buffer, 0, read)
                    }
                    out.toString()
                }
        } finally {
            connection.disconnect()
        }
    }

    private fun extractConfigLinks(content: String): List<String> {
        val decoded = decodeHtmlEntities(content)
        val result = LinkedHashSet<String>()

        fun collect(value: String) {
            CONFIG_LINK.findAll(value).forEach { match ->
                if (result.size >= MAX_DISCOVERED_LINKS) return@forEach
                val candidate = match.value
                    .trim()
                    .trimEnd(
                        '.', ',', ';', ')', ']', '}', '”', '"', '\'',
                        '`'
                    )
                if (candidate.length in 8..MAX_LINK_CHARS) {
                    result += candidate
                }
            }
        }

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
            codePoint
                ?.takeIf { it in 0..0x10FFFF }
                ?.let { String(Character.toChars(it)) }
                ?: match.value
        }
        return text
    }

    private fun compactFailure(error: Throwable): String =
        error.message
            ?.replace(Regex("\\s+"), " ")
            ?.take(96)
            ?.ifBlank { null }
            ?: error.javaClass.simpleName

    companion object {
        const val DEFAULT_CHANNEL = "farahvpn"
        const val COMMUNITY_SUBSCRIPTION_ID = "community:telegram:public"
        const val DEFAULT_SYNC_AGE_MS = 6 * 60 * 60 * 1000L

        private const val MIRROR_REPOSITORY = "marble098/Trivox"
        private const val MIRROR_BRANCH = "community-cache"

        private const val PREFS = "trivox_community_source_v29"
        private const val KEY_USERNAME = "username"
        private const val KEY_LAST_SUCCESS = "last_success"
        private const val KEY_LAST_COUNT = "last_count"
        private const val KEY_LAST_ROUTE = "last_route"

        private const val MAX_HTML_CHARS = 2 * 1024 * 1024
        private const val MAX_DISCOVERED_LINKS = 160
        private const val MAX_IMPORTED_PROFILES = 60
        private const val MAX_LINK_CHARS = 16 * 1024
        private const val MAX_REPORTED_FAILURES = 5

        private const val CONNECT_TIMEOUT_MS = 4_000
        private const val READ_TIMEOUT_MS = 6_000
        private const val USER_AGENT =
            "Mozilla/5.0 (Android) Trivox/29 CommunitySync"

        private val USERNAME = Regex("^[A-Za-z0-9_]{5,32}$")
        private val CONFIG_LINK = Regex(
            "(?i)(?:vless|vmess|trojan|ss|shadowsocks|hy2|" +
                "hysteria2|hysteria|wg|wireguard|ssh|openssh)://" +
                "[^\\s<>\\\"']+"
        )
        private val NUMERIC_ENTITY =
            Regex("&#(x?[0-9A-Fa-f]+);")

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
