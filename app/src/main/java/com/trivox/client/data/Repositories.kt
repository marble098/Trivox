package com.trivox.client.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class ConfigRepository(context: Context) {
    private val prefs =
        context.applicationContext.getSharedPreferences(
            "profiles",
            Context.MODE_PRIVATE
        )

    companion object {
        private val GLOBAL_LOCK = Any()
    }

    fun all(): MutableList<ConfigProfile> =
        synchronized(GLOBAL_LOCK) {
            read()
        }

    fun find(id: String?): ConfigProfile? =
        id?.let { target ->
            synchronized(GLOBAL_LOCK) {
                read().firstOrNull { it.id == target }
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
                items[index]
            }
        }

    fun save(profile: ConfigProfile) {
        synchronized(GLOBAL_LOCK) {
            val items = read()
            val index =
                items.indexOfFirst {
                    it.id == profile.id
                }

            if (index >= 0) {
                items[index] = profile
            } else {
                items += profile
            }

            write(items)
        }
    }

    fun saveAll(
        profiles: Collection<ConfigProfile>
    ) {
        synchronized(GLOBAL_LOCK) {
            val items = read()

            profiles.forEach { profile ->
                val duplicate =
                    items.indexOfFirst {
                        it.raw.trim() ==
                            profile.raw.trim()
                    }

                if (duplicate < 0) {
                    items += profile
                } else if (
                    profile.subscriptionId !=
                    null
                ) {
                    val old =
                        items[duplicate]

                    items[duplicate] =
                        mergePreserved(
                            incoming =
                                profile.copy(
                                    id = old.id
                                ),
                            old = old
                        )
                }
            }

            write(items)
        }
    }

    fun replaceSubscription(
        subscriptionId: String,
        profiles: Collection<ConfigProfile>
    ) {
        synchronized(GLOBAL_LOCK) {
            val existing = read()
            val previous =
                existing
                    .filter {
                        it.subscriptionId ==
                            subscriptionId
                    }
                    .associateBy {
                        it.raw.trim()
                    }

            val retained =
                existing
                    .filterNot {
                        it.subscriptionId ==
                            subscriptionId
                    }
                    .toMutableList()

            profiles.forEach { profile ->
                val incoming =
                    profile.copy(
                        subscriptionId =
                            subscriptionId
                    )
                val old =
                    previous[
                        incoming.raw.trim()
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

                val duplicate =
                    retained.indexOfFirst {
                        it.raw.trim() ==
                            merged.raw.trim()
                    }

                if (duplicate < 0) {
                    retained += merged
                }
            }

            write(retained)
        }
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

            if (
                selectedId() in
                removedIds
            ) {
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
        synchronized(GLOBAL_LOCK) {
            val items =
                read().filterNot {
                    it.id == id
                }

            write(items)

            if (selectedId() == id) {
                select(null)
            }
        }
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
                ConfigProfile.fromJson(
                    array.getJSONObject(it)
                )
            }.getOrNull()
        }.toMutableList()
    }

    private fun write(
        items: Collection<ConfigProfile>
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
            testStatus =
                old.testStatus,
            lastTestAt =
                old.lastTestAt,
            cumulativeSessionMs =
                old.cumulativeSessionMs,
            lastSessionMs =
                old.lastSessionMs,
            exitIp = old.exitIp,
            exitCountry =
                old.exitCountry,
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
