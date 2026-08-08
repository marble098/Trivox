package com.trivox.client.service

import android.content.Context
import com.trivox.client.R
import com.trivox.client.core.ConnectionRuntime
import com.trivox.client.data.ConfigProfile
import com.trivox.client.data.ConfigRepository
import com.trivox.client.data.SubscriptionRepository
import com.trivox.client.data.SettingsRepository
import com.trivox.client.network.CommunityConfigManager

internal object NotificationQuickSwitchStore {
    enum class Stage { SUBSCRIPTION, PROFILE }

    data class Group(
        val key: String,
        val name: String,
        val profiles: List<ConfigProfile>
    )

    data class Snapshot(
        val stage: Stage,
        val group: Group,
        val profile: ConfigProfile?,
        val groupIndex: Int,
        val groupCount: Int,
        val profileIndex: Int,
        val profileCount: Int
    )

    private const val PREFS = "trivox_notification_quick_switch"
    private const val KEY_STAGE = "stage"
    private const val KEY_GROUP = "group"
    private const val KEY_PROFILE = "profile"
    private const val KEY_UPDATED = "updated"
    private const val LOCAL = "__local__"
    private const val COMMUNITY = "__community__"
    private const val OTHER = "__other__"
    private const val TTL_MS = 60_000L

    fun begin(context: Context): Snapshot? {
        val groups = groups(context)
        if (groups.isEmpty()) {
            clear(context)
            return null
        }
        val repo = ConfigRepository(context)
        val currentId = ConnectionRuntime.current().profileId ?: repo.selectedId()
        val group = groups.firstOrNull { g -> g.profiles.any { it.id == currentId } }
            ?: groups.first()
        val profile = group.profiles.firstOrNull { it.id == currentId }
            ?: group.profiles.firstOrNull()
        save(context, Stage.SUBSCRIPTION, group.key, profile?.id)
        return current(context)
    }

    fun moveGroup(context: Context, delta: Int): Snapshot? {
        val groups = groups(context)
        if (groups.isEmpty()) return null
        val old = current(context) ?: begin(context) ?: return null
        val i = groups.indexOfFirst { it.key == old.group.key }.coerceAtLeast(0)
        val group = groups[floorMod(i + delta, groups.size)]
        save(context, Stage.SUBSCRIPTION, group.key, group.profiles.firstOrNull()?.id)
        return current(context)
    }

    fun openProfiles(context: Context): Snapshot? {
        val old = current(context) ?: begin(context) ?: return null
        save(
            context, Stage.PROFILE, old.group.key,
            old.profile?.id ?: old.group.profiles.firstOrNull()?.id
        )
        return current(context)
    }

    fun moveProfile(context: Context, delta: Int): Snapshot? {
        val old = current(context) ?: begin(context) ?: return null
        if (old.group.profiles.isEmpty()) return old
        val i = old.group.profiles.indexOfFirst { it.id == old.profile?.id }
            .coerceAtLeast(0)
        val profile = old.group.profiles[
            floorMod(i + delta, old.group.profiles.size)
        ]
        save(context, Stage.PROFILE, old.group.key, profile.id)
        return current(context)
    }

    fun current(context: Context): Snapshot? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val updated = prefs.getLong(KEY_UPDATED, 0L)
        if (updated <= 0L || System.currentTimeMillis() - updated > TTL_MS) {
            clear(context); return null
        }
        val groups = groups(context)
        if (groups.isEmpty()) { clear(context); return null }
        val group = groups.firstOrNull {
            it.key == prefs.getString(KEY_GROUP, null)
        } ?: groups.first()
        val profile = group.profiles.firstOrNull {
            it.id == prefs.getString(KEY_PROFILE, null)
        } ?: group.profiles.firstOrNull()
        val stage = runCatching {
            Stage.valueOf(
                prefs.getString(KEY_STAGE, Stage.SUBSCRIPTION.name)
                    ?: Stage.SUBSCRIPTION.name
            )
        }.getOrDefault(Stage.SUBSCRIPTION)
        return Snapshot(
            stage, group, profile,
            groups.indexOf(group).coerceAtLeast(0), groups.size,
            group.profiles.indexOf(profile).coerceAtLeast(0), group.profiles.size
        )
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().clear().apply()
    }

    private fun save(
        context: Context, stage: Stage, group: String, profile: String?
    ) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_STAGE, stage.name)
            .putString(KEY_GROUP, group)
            .putString(KEY_PROFILE, profile)
            .putLong(KEY_UPDATED, System.currentTimeMillis())
            .apply()
    }

    private fun groups(context: Context): List<Group> {
        val enabled = ConfigRepository(context).all()
            .filter(ConfigProfile::enabled)
        if (enabled.isEmpty()) return emptyList()

        val result = ArrayList<Group>()
        val local = enabled.filter { it.subscriptionId.isNullOrBlank() }
            .sortedBy { it.name.lowercase() }
        if (local.isNotEmpty()) {
            result += Group(
                LOCAL, context.getString(R.string.notification_local_configs), local
            )
        }

        val community = enabled
            .filter { it.subscriptionId == CommunityConfigManager.COMMUNITY_SUBSCRIPTION_ID }
            .sortedBy { it.name.lowercase() }
        if (community.isNotEmpty()) {
            val username = SettingsRepository(context).load().communityChannelUsername
            result += Group(COMMUNITY, "@$username", community)
        }

        SubscriptionRepository(context).all()
            .sortedBy { it.name.lowercase() }
            .forEach { source ->
                val profiles = enabled.filter { it.subscriptionId == source.id }
                    .sortedBy { it.name.lowercase() }
                if (profiles.isNotEmpty()) {
                    result += Group("sub:${source.id}", source.name, profiles)
                }
            }

        val covered = result.flatMap { it.profiles }.mapTo(HashSet()) { it.id }
        val other = enabled.filterNot { it.id in covered }
            .sortedBy { it.name.lowercase() }
        if (other.isNotEmpty()) {
            result += Group(
                OTHER, context.getString(R.string.notification_all_configs), other
            )
        }
        return result
    }

    private fun floorMod(value: Int, size: Int): Int =
        ((value % size) + size) % size
}
