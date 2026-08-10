package com.trivox.client.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** TRIVOX_V7_IMPORT_WIREGUARD
 * Atomic result for destination-aware imports. "All" is a view, never a
 * physical subscription bucket.
 */
data class ProfileImportResult(
    val received: Int,
    val added: Int,
    val updated: Int,
    val moved: Int,
    val skipped: Int
) {
    val changed: Int
        get() = added + updated + moved
}

class ConfigRepository(context: Context) {
    private val prefs =
        context.applicationContext.getSharedPreferences(
            "profiles",
            Context.MODE_PRIVATE
        )

    companion object {
        private val GLOBAL_LOCK = Any()

        @Volatile
        private var cachedRaw: String? = null

        @Volatile
        private var cachedItems:
            List<ConfigProfile>? = null

        private const val
            MAX_STORED_PROFILES = 10_000
        private const val
            MAX_STORED_JSON_CHARS =
                12 * 1024 * 1024
    }

    fun all(): MutableList<ConfigProfile> =
        synchronized(GLOBAL_LOCK) {
            read()
        }

    fun find(id: String?): ConfigProfile? =
        id?.let { target ->
            synchronized(GLOBAL_LOCK) {
                read().firstOrNull {
                    it.id == target
                }
            }
        }

    fun update(
        id: String,
        transform: (ConfigProfile) -> Unit
    ): ConfigProfile? =
        synchronized(GLOBAL_LOCK) {
            val items = read()
            val index =
                items.indexOfFirst {
                    it.id == id
                }

            if (index < 0) {
                null
            } else {
                transform(items[index])
                write(items)
                items[index].copy()
            }
        }

    fun updateMany(
        ids: Collection<String>,
        transform: (ConfigProfile) -> Unit
    ): Int = synchronized(GLOBAL_LOCK) {
        if (ids.isEmpty()) {
            return@synchronized 0
        }

        val wanted = ids.toHashSet()
        val items = read()
        var changed = 0

        items.forEach { profile ->
            if (profile.id in wanted) {
                transform(profile)
                changed += 1
            }
        }

        if (changed > 0) {
            write(items)
        }

        changed
    }

    fun save(profile: ConfigProfile) {
        synchronized(GLOBAL_LOCK) {
            val items = read()
            val index =
                items.indexOfFirst {
                    it.id == profile.id
                }

            if (index >= 0) {
                items[index] = profile.copy()
            } else {
                items += profile.copy()
            }

            write(items)
        }
    }

    fun saveAll(
        profiles: Collection<ConfigProfile>
    ) {
        synchronized(GLOBAL_LOCK) {
            val items = read()
            val identityIndex =
                buildIdentityIndex(items)

            profiles.forEach { profile ->
                val incomingIdentity =
                    profileIdentity(profile)
                val duplicate =
                    identityIndex[incomingIdentity]

                if (duplicate == null) {
                    identityIndex[incomingIdentity] =
                        items.size
                    items += profile.copy()
                } else if (
                    profile.subscriptionId != null
                ) {
                    val old = items[duplicate]

                    val merged =
                        mergePreserved(
                            incoming =
                                profile.copy(
                                    id = old.id
                                ),
                            old = old
                        )
                    items[duplicate] = merged
                    identityIndex[
                        profileIdentity(merged)
                    ] = duplicate
                }
            }

            write(items)
        }
    }

    /**
     * Precomputes each profile's canonical identity once so repeated
     * dedup/merge lookups are O(1) map reads instead of re-hashing every
     * existing profile's outboundJson on every scan (which would be
     * O(existing * incoming) hashing work for a large catalog).
     */
    private fun buildIdentityIndex(
        items: List<ConfigProfile>
    ): HashMap<String, Int> {
        val index = HashMap<String, Int>(items.size * 2)
        items.forEachIndexed { i, profile ->
            index[profileIdentity(profile)] = i
        }
        return index
    }

    /**
     * Imports profiles into a stable destination snapshot. A null destination
     * means local/unmanaged storage; the All tab remains only an aggregate view.
     * Exact duplicates already owned by a managed subscription are not stolen by
     * a local import, while importing into another explicit subscription moves
     * the existing profile atomically and preserves user/runtime metadata.
     */
    fun importProfiles(
        profiles: Collection<ConfigProfile>,
        subscriptionId: String?,
        groupName: String? = null
    ): ProfileImportResult = synchronized(GLOBAL_LOCK) {
        if (profiles.isEmpty()) {
            return@synchronized ProfileImportResult(0, 0, 0, 0, 0)
        }

        val unique = LinkedHashMap<String, ConfigProfile>()
        profiles.forEach { profile ->
            unique.putIfAbsent(profileIdentity(profile), profile)
        }

        val items = read()
        val identityIndex = buildIdentityIndex(items)
        val idIndex = HashMap<String, Int>(items.size * 2)
        items.forEachIndexed { i, p -> idIndex[p.id] = i }

        var added = 0
        var updated = 0
        var moved = 0
        var skipped = profiles.size - unique.size

        unique.values.forEach { profile ->
            val incoming = profile.copy(
                subscriptionId = subscriptionId,
                group = if (subscriptionId == null) {
                    profile.group.ifBlank { "Default" }
                } else {
                    groupName?.takeIf(String::isNotBlank)
                        ?: profile.group.ifBlank { "Default" }
                }
            )
            val identity = profileIdentity(incoming)
            val index = idIndex[incoming.id] ?: identityIndex[identity]

            if (index == null) {
                idIndex[incoming.id] = items.size
                identityIndex[identity] = items.size
                items += incoming
                added += 1
                return@forEach
            }

            val old = items[index]
            if (subscriptionId == null && old.subscriptionId != null) {
                skipped += 1
                return@forEach
            }

            val merged = mergePreserved(
                incoming = incoming.copy(id = old.id),
                old = old
            )
            idIndex[merged.id] = index
            identityIndex[profileIdentity(merged)] = index

            when {
                old.subscriptionId != subscriptionId -> {
                    items[index] = merged
                    moved += 1
                }

                old != merged -> {
                    items[index] = merged
                    updated += 1
                }

                else -> skipped += 1
            }
        }

        if (added + updated + moved > 0) {
            write(items)
        }

        ProfileImportResult(
            received = profiles.size,
            added = added,
            updated = updated,
            moved = moved,
            skipped = skipped
        )
    }

    private fun profileIdentity(profile: ConfigProfile): String =
        ProfileIdentity.of(profile)

    fun replaceSubscription(
        subscriptionId: String,
        profiles: Collection<ConfigProfile>
    ) {
        synchronized(GLOBAL_LOCK) {
            val existing = read()
            val subscriptionProfiles =
                existing.filter {
                    it.subscriptionId ==
                        subscriptionId
                }
            val previousByIdentity =
                subscriptionProfiles
                    .associateBy {
                        profileIdentity(it)
                    }
            val previousById =
                subscriptionProfiles
                    .associateBy(
                        ConfigProfile::id
                    )

            val retained =
                existing
                    .filterNot {
                        it.subscriptionId ==
                            subscriptionId
                    }
                    .toMutableList()
            val retainedIdentityIndex =
                buildIdentityIndex(retained)
            val retainedIdIndex =
                HashMap<String, Int>(retained.size * 2)
            retained.forEachIndexed { i, p ->
                retainedIdIndex[p.id] = i
            }

            profiles.forEach { profile ->
                val incoming =
                    profile.copy(
                        subscriptionId =
                            subscriptionId
                    )
                val old =
                    previousByIdentity[
                        profileIdentity(incoming)
                    ] ?: previousById[
                        incoming.id
                    ]

                val merged =
                    if (old == null) {
                        incoming
                    } else {
                        mergePreserved(
                            incoming =
                                incoming.copy(
                                    id = old.id
                                ),
                            old = old
                        )
                    }

                val mergedIdentity = profileIdentity(merged)
                val duplicate =
                    retainedIdIndex[merged.id]
                        ?: retainedIdentityIndex[mergedIdentity]

                if (duplicate == null) {
                    retainedIdIndex[merged.id] = retained.size
                    retainedIdentityIndex[mergedIdentity] = retained.size
                    retained += merged
                }
            }

            write(retained)
        }
    }


    fun mergeSubscription(
        subscriptionId: String,
        profiles: Collection<ConfigProfile>
    ): Int =
        synchronized(GLOBAL_LOCK) {
            if (profiles.isEmpty()) {
                return@synchronized 0
            }

            val items = read()
            val anyIdIndex = HashMap<String, Int>(items.size * 2)
            val anyIdentityIndex = buildIdentityIndex(items)
            val sameSubIdIndex = HashMap<String, Int>()
            val sameSubIdentityIndex = HashMap<String, Int>()
            items.forEachIndexed { i, p ->
                anyIdIndex[p.id] = i
                if (p.subscriptionId == subscriptionId) {
                    sameSubIdIndex[p.id] = i
                    sameSubIdentityIndex[profileIdentity(p)] = i
                }
            }
            var mergedCount = 0

            profiles.forEach { profile ->
                val incoming =
                    profile.copy(
                        subscriptionId =
                            subscriptionId
                    )
                val incomingIdentity =
                    profileIdentity(incoming)
                val index =
                    sameSubIdIndex[incoming.id]
                        ?: sameSubIdentityIndex[incomingIdentity]

                if (index != null) {
                    val old =
                        items[index]

                    val merged =
                        mergePreserved(
                            incoming =
                                incoming.copy(
                                    id = old.id
                                ),
                            old = old
                        )
                    items[index] = merged
                    val mergedIdentity = profileIdentity(merged)
                    anyIdIndex[merged.id] = index
                    anyIdentityIndex[mergedIdentity] = index
                    sameSubIdIndex[merged.id] = index
                    sameSubIdentityIndex[mergedIdentity] = index
                } else {
                    val duplicate =
                        anyIdIndex[incoming.id]
                            ?: anyIdentityIndex[incomingIdentity]

                    if (duplicate == null) {
                        val newIndex = items.size
                        items += incoming
                        anyIdIndex[incoming.id] = newIndex
                        anyIdentityIndex[incomingIdentity] = newIndex
                        sameSubIdIndex[incoming.id] = newIndex
                        sameSubIdentityIndex[incomingIdentity] = newIndex
                    }
                }

                mergedCount += 1
            }

            write(items)
            mergedCount
        }

    fun renameSubscription(
        subscriptionId: String,
        groupName: String
    ): Int =
        synchronized(GLOBAL_LOCK) {
            val items = read()
            var changed = 0

            items.forEach { profile ->
                if (
                    profile.subscriptionId ==
                    subscriptionId &&
                    profile.group != groupName
                ) {
                    profile.group = groupName
                    changed += 1
                }
            }

            if (changed > 0) {
                write(items)
            }

            changed
        }

    fun deleteSubscription(
        subscriptionId: String
    ): Int =
        synchronized(GLOBAL_LOCK) {
            val items = read()
            val removedIds =
                items
                    .filter {
                        it.subscriptionId ==
                            subscriptionId
                    }
                    .mapTo(
                        mutableSetOf()
                    ) {
                        it.id
                    }

            if (removedIds.isEmpty()) {
                return@synchronized 0
            }

            write(
                items.filterNot {
                    it.id in removedIds
                }
            )

            if (selectedId() in removedIds) {
                select(null)
            }

            removedIds.size
        }

    fun countForSubscription(
        subscriptionId: String
    ): Int =
        synchronized(GLOBAL_LOCK) {
            read().count {
                it.subscriptionId ==
                    subscriptionId
            }
        }

    fun delete(id: String) {
        deleteMany(listOf(id))
    }

    fun deleteMany(
        ids: Collection<String>
    ): Int = synchronized(GLOBAL_LOCK) {
        if (ids.isEmpty()) {
            return@synchronized 0
        }

        val wanted = ids.toHashSet()
        val items = read()
        val before = items.size
        items.removeAll {
            it.id in wanted
        }
        val removed = before - items.size

        if (removed > 0) {
            write(items)
        }

        if (selectedId() in wanted) {
            select(null)
        }

        removed
    }

    fun selectedId(): String? =
        prefs.getString(
            "selected",
            null
        )

    fun select(id: String?) {
        prefs.edit()
            .putString(
                "selected",
                id
            )
            .apply()
    }

    private fun read():
        MutableList<ConfigProfile> {
        val raw =
            prefs.getString(
                "items",
                "[]"
            ) ?: "[]"
        val cached = cachedItems

        if (
            cachedRaw == raw &&
            cached != null
        ) {
            return cached
                .map { it.copy() }
                .toMutableList()
        }

        val array =
            runCatching {
                JSONArray(raw)
            }.getOrDefault(
                JSONArray()
            )
        val parsed =
            (0 until array.length())
                .mapNotNull {
                    runCatching {
                        ConfigProfile.fromJson(
                            array.getJSONObject(it)
                        )
                    }.getOrNull()
                }
                .toMutableList()

        cachedRaw = raw
        cachedItems =
            parsed.map { it.copy() }

        return parsed
    }

    private fun write(
        items: Collection<ConfigProfile>
    ) {
        check(
            items.size <=
                MAX_STORED_PROFILES
        ) {
            "Profile storage limit exceeded"
        }

        val snapshot =
            items.map { it.copy() }
        val raw =
            JSONArray().apply {
                snapshot.forEach {
                    put(it.toJson())
                }
            }.toString()

        check(
            raw.length <=
                MAX_STORED_JSON_CHARS
        ) {
            "Profile storage exceeds the safety limit"
        }

        cachedRaw = raw
        cachedItems = snapshot

        prefs.edit()
            .putString(
                "items",
                raw
            )
            .apply()
    }

    private fun mergePreserved(
        incoming: ConfigProfile,
        old: ConfigProfile
    ): ConfigProfile =
        incoming.copy(
            enabled = old.enabled,
            favorite = old.favorite,
            latencyMs = old.latencyMs,
            latencyJitterMs =
                old.latencyJitterMs,
            latencySuccessRatio =
                old.latencySuccessRatio,
            latencyMethod =
                old.latencyMethod,
            testStatus = old.testStatus,
            lastTestAt = old.lastTestAt,
            tcpLatencyMs = old.tcpLatencyMs,
            tcpLatencyJitterMs =
                old.tcpLatencyJitterMs,
            tcpSuccessRatio =
                old.tcpSuccessRatio,
            tcpTestStatus =
                old.tcpTestStatus,
            tcpLastTestAt =
                old.tcpLastTestAt,
            realLatencyMs = old.realLatencyMs,
            realLatencyJitterMs =
                old.realLatencyJitterMs,
            realSuccessRatio =
                old.realSuccessRatio,
            realTestStatus =
                old.realTestStatus,
            realLastTestAt =
                old.realLastTestAt,
            cumulativeSessionMs =
                old.cumulativeSessionMs,
            lastSessionMs = old.lastSessionMs,
            exitIp = old.exitIp,
            exitCountry = old.exitCountry,
            exitCountryCode =
                old.exitCountryCode,
            exitFlag = old.exitFlag,
            exitIsp = old.exitIsp,
            lastExitCheckAt =
                old.lastExitCheckAt
        )
}

class SettingsRepository(context: Context) {
    private val prefs =
        context.applicationContext.getSharedPreferences(
            "settings",
            Context.MODE_PRIVATE
        )

    fun load(): AppSettings {
        val raw =
            runCatching {
                JSONObject(
                    prefs.getString(
                        "value",
                        "{}"
                    )!!
                )
            }.getOrDefault(
                JSONObject()
            )

        val storedSocksPort =
            raw.optInt(
                "socksPort",
                AppSettings
                    .DEFAULT_MIXED_PORT
            )
        val storedHttpPort =
            raw.optInt(
                "httpPort",
                storedSocksPort
            )
        val settings =
            runCatching {
                AppSettings.fromJson(raw)
            }.getOrDefault(
                AppSettings()
            )

        if (
            storedSocksPort !=
            settings.socksPort ||
            storedHttpPort !=
            settings.socksPort
        ) {
            save(settings)
        }

        return settings
    }

    fun save(settings: AppSettings) {
        settings.normalize()

        prefs.edit()
            .putString(
                "value",
                settings
                    .toJson()
                    .toString()
            )
            .apply()
    }
}

class SubscriptionRepository(
    context: Context
) {
    private val prefs =
        context.applicationContext
            .getSharedPreferences(
                "subscriptions",
                Context.MODE_PRIVATE
            )

    companion object {
        private val GLOBAL_LOCK = Any()
    }

    fun all():
        MutableList<SubscriptionSource> =
        synchronized(GLOBAL_LOCK) {
            read()
        }

    fun find(
        id: String?
    ): SubscriptionSource? =
        id?.let { target ->
            synchronized(GLOBAL_LOCK) {
                read().firstOrNull {
                    it.id == target
                }
            }
        }

    fun save(
        source: SubscriptionSource
    ) {
        synchronized(GLOBAL_LOCK) {
            val items = read()
            val index =
                items.indexOfFirst {
                    it.id == source.id
                }

            if (index >= 0) {
                items[index] = source
            } else {
                items += source
            }

            write(items)
        }
    }

    fun update(
        id: String,
        transform:
            (SubscriptionSource) -> Unit
    ): SubscriptionSource? =
        synchronized(GLOBAL_LOCK) {
            val items = read()
            val index =
                items.indexOfFirst {
                    it.id == id
                }

            if (index < 0) {
                null
            } else {
                transform(items[index])
                write(items)
                items[index]
            }
        }

    fun delete(id: String) {
        synchronized(GLOBAL_LOCK) {
            write(
                read().filterNot {
                    it.id == id
                }
            )
        }
    }

    private fun read():
        MutableList<SubscriptionSource> {
        val array =
            runCatching {
                JSONArray(
                    prefs.getString(
                        "items",
                        "[]"
                    )
                )
            }.getOrDefault(
                JSONArray()
            )

        return (
            0 until array.length()
        ).mapNotNull {
            runCatching {
                SubscriptionSource
                    .fromJson(
                        array
                            .getJSONObject(it)
                    )
            }.getOrNull()
        }.toMutableList()
    }

    private fun write(
        items:
            Collection<SubscriptionSource>
    ) {
        prefs.edit()
            .putString(
                "items",
                JSONArray().apply {
                    items.forEach {
                        put(it.toJson())
                    }
                }.toString()
            )
            .apply()
    }
}
