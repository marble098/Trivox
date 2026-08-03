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
        private val GLOBAL_LOCK =
            Any()
    }

    fun all(): MutableList<ConfigProfile> =
        synchronized(GLOBAL_LOCK) {
            val array = runCatching {
                JSONArray(
                    prefs.getString("items", "[]")
                )
            }.getOrDefault(JSONArray())

            (0 until array.length()).mapNotNull {
                runCatching {
                    ConfigProfile.fromJson(
                        array.getJSONObject(it)
                    )
                }.getOrNull()
            }.toMutableList()
        }

    fun find(id: String?): ConfigProfile? =
        id?.let { target ->
            all().firstOrNull { it.id == target }
        }

    fun update(
        id: String,
        transform:
            (ConfigProfile) -> Unit
    ): ConfigProfile? =
        synchronized(GLOBAL_LOCK) {
            val items = all()
            val index =
                items.indexOfFirst {
                    it.id == id
                }

            if (index < 0) {
                null
            } else {
                transform(
                    items[index]
                )
                write(items)
                items[index]
            }
        }

    fun save(profile: ConfigProfile) =
        synchronized(GLOBAL_LOCK) {
            val items = all()
            val index =
                items.indexOfFirst { it.id == profile.id }

            if (index >= 0) {
                items[index] = profile
            } else {
                items += profile
            }

            write(items)
        }

    fun saveAll(profiles: Collection<ConfigProfile>) =
        synchronized(GLOBAL_LOCK) {
            val items = all()

            profiles.forEach { profile ->
                val duplicate = items.indexOfFirst {
                    it.raw.trim() == profile.raw.trim()
                }

                if (duplicate < 0) {
                    items += profile
                } else if (profile.subscriptionId != null) {
                    val old = items[duplicate]
                    items[duplicate] =
                        profile.copy(
                            id = old.id,
                            enabled =
                                old.enabled,
                            favorite =
                                old.favorite,
                            latencyMs =
                                old.latencyMs,
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
                            exitIp =
                                old.exitIp,
                            exitCountry =
                                old.exitCountry,
                            exitCountryCode =
                                old.exitCountryCode,
                            exitFlag =
                                old.exitFlag,
                            exitIsp =
                                old.exitIsp,
                            lastExitCheckAt =
                                old.lastExitCheckAt
                        )
                }
            }

            write(items)
        }

    fun replaceSubscription(
        subscriptionId: String,
        profiles: Collection<ConfigProfile>
    ) = synchronized(GLOBAL_LOCK) {
        val existing = all()
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

        profiles.forEach {
                profile ->
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
                    incoming.copy(
                        id = old.id,
                        enabled =
                            old.enabled,
                        favorite =
                            old.favorite,
                        latencyMs =
                            old.latencyMs,
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
                        exitIp =
                            old.exitIp,
                        exitCountry =
                            old.exitCountry,
                        exitCountryCode =
                            old.exitCountryCode,
                        exitFlag =
                            old.exitFlag,
                        exitIsp =
                            old.exitIsp,
                        lastExitCheckAt =
                            old.lastExitCheckAt
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

    fun delete(id: String) =
        synchronized(GLOBAL_LOCK) {
            write(all().filterNot { it.id == id })
        }

    fun selectedId(): String? =
        prefs.getString("selected", null)

    fun select(id: String?) {
        prefs.edit()
            .putString("selected", id)
            .apply()
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
}

class SettingsRepository(context: Context) {
    private val prefs =
        context.applicationContext.getSharedPreferences(
            "settings",
            Context.MODE_PRIVATE
        )

    fun load(): AppSettings {
        val raw = runCatching {
            JSONObject(
                prefs.getString("value", "{}")!!
            )
        }.getOrDefault(JSONObject())

        val storedSocksPort = raw.optInt(
            "socksPort",
            AppSettings.DEFAULT_MIXED_PORT
        )
        val storedHttpPort = raw.optInt(
            "httpPort",
            storedSocksPort
        )
        val settings = runCatching {
            AppSettings.fromJson(raw)
        }.getOrDefault(AppSettings())

        if (
            storedSocksPort != settings.socksPort ||
            storedHttpPort != settings.socksPort
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
                settings.toJson().toString()
            )
            .apply()
    }
}

class SubscriptionRepository(context: Context) {
    private val prefs =
        context.applicationContext.getSharedPreferences(
            "subscriptions",
            Context.MODE_PRIVATE
        )

    @Synchronized
    fun all(): MutableList<SubscriptionSource> {
        val array = runCatching {
            JSONArray(
                prefs.getString("items", "[]")
            )
        }.getOrDefault(JSONArray())

        return (0 until array.length()).mapNotNull {
            runCatching {
                SubscriptionSource.fromJson(
                    array.getJSONObject(it)
                )
            }.getOrNull()
        }.toMutableList()
    }

    @Synchronized
    fun save(source: SubscriptionSource) {
        val items = all()
        val index =
            items.indexOfFirst { it.id == source.id }

        if (index >= 0) {
            items[index] = source
        } else {
            items += source
        }

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

    @Synchronized
    fun delete(id: String) {
        prefs.edit()
            .putString(
                "items",
                JSONArray().apply {
                    all()
                        .filterNot { it.id == id }
                        .forEach {
                            put(it.toJson())
                        }
                }.toString()
            )
            .apply()
    }
}
