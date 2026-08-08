package com.trivox.client.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.core.content.ContextCompat
import com.trivox.client.core.ConnectionRuntime
import com.trivox.client.data.ConfigProfile
import com.trivox.client.data.ConfigRepository
import com.trivox.client.data.ConnectionMode
import com.trivox.client.data.ConnectionState
import com.trivox.client.data.SettingsRepository
import com.trivox.client.util.Diagnostics

/**
 * Notification actions are deliberately handled by a receiver so Switch -> Use,
 * Next and Pause can complete without launching MainActivity or showing a
 * transparent activity. The v25 switch coordinator owns the stop/start race.
 */
class NotificationQuickSwitchReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action !in SUPPORTED_ACTIONS) return
        val pending = goAsync()
        try {
            runCatching {
                when (action) {
                BEGIN -> NotificationQuickSwitchStore.begin(context)
                PREV_GROUP -> NotificationQuickSwitchStore.moveGroup(context, -1)
                NEXT_GROUP -> NotificationQuickSwitchStore.moveGroup(context, 1)
                OPEN_PROFILES -> NotificationQuickSwitchStore.openProfiles(context)
                PREV_PROFILE -> NotificationQuickSwitchStore.moveProfile(context, -1)
                NEXT_PROFILE -> NotificationQuickSwitchStore.moveProfile(context, 1)
                USE -> applyCurrentCandidate(context)
                NEXT_CONNECT -> applyNextProfile(context)
                PAUSE -> pauseCurrent(context)
                    CANCEL -> NotificationQuickSwitchStore.clear(context)
                    else -> Unit
                }
                NotificationSupport.refresh(context)
            }.onFailure {
                Diagnostics.recordThrowable("Notification quick-switch action", it)
                NotificationQuickSwitchStore.clear(context)
                runCatching { NotificationSupport.refresh(context) }
            }
        } finally {
            pending.finish()
        }
    }

    private fun applyCurrentCandidate(context: Context) {
        val snapshot = NotificationQuickSwitchStore.current(context)
        val profile = snapshot?.profile ?: run {
            Diagnostics.warning("Notification Use ignored: no quick-switch candidate")
            return
        }
        NotificationQuickSwitchStore.clear(context)
        applyProfile(context, profile, "notification_use")
    }

    private fun applyNextProfile(context: Context) {
        val repository = ConfigRepository(context)
        val current = ConnectionRuntime.current()
        val currentProfile = repository.find(current.profileId ?: repository.selectedId())
        val all = repository.all().filter(ConfigProfile::enabled)
        if (all.isEmpty()) return
        val sameGroup = currentProfile?.let { active ->
            all.filter { it.subscriptionId == active.subscriptionId }
        }.orEmpty()
        val pool = sameGroup.takeIf { it.size > 1 } ?: all
        val index = pool.indexOfFirst { it.id == currentProfile?.id }
        val next = pool[if (index < 0 || index + 1 >= pool.size) 0 else index + 1]
        applyProfile(context, next, "notification_next")
    }

    private fun applyProfile(context: Context, profile: ConfigProfile, reason: String) {
        val repository = ConfigRepository(context)
        repository.select(profile.id)
        val mode = activeMode(context)
        QuickConnectStore.save(context, mode, profile.id, profile.name)
        Diagnostics.info(
            "Notification direct switch requested; reason=$reason, " +
                "mode=${mode.name}, profile=${profile.id}"
        )

        val current = ConnectionRuntime.current()
        if (
            current.profileId == profile.id &&
            current.state == ConnectionState.CONNECTED
        ) {
            Diagnostics.info("Notification switch ignored: selected profile is already active")
            return
        }
        if (current.state !in IDLE_STATES) {
            ConnectionSwitchCoordinator.queue(
                context = context,
                profileId = profile.id,
                targetMode = mode,
                reason = reason
            )
            return
        }

        if (mode == ConnectionMode.VPN && VpnService.prepare(context) != null) {
            Diagnostics.warning(
                "Notification direct VPN start needs Android VPN permission; opening permission bridge"
            )
            ContextCompat.startActivity(
                context,
                Intent(context, NotificationActionActivity::class.java)
                    .setAction(NotificationActionActivity.ACTION_APPLY_PROFILE)
                    .putExtra(NotificationActionActivity.EXTRA_PROFILE_ID, profile.id)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                null
            )
            return
        }

        val start = if (mode == ConnectionMode.PROXY) {
            Intent(context, ConnectionService::class.java)
                .setAction(ConnectionService.ACTION_START)
                .putExtra(ConnectionService.EXTRA_PROFILE_ID, profile.id)
        } else {
            Intent(context, TrivoxVpnService::class.java)
                .setAction(TrivoxVpnService.ACTION_START)
                .putExtra(TrivoxVpnService.EXTRA_PROFILE_ID, profile.id)
        }
        ContextCompat.startForegroundService(context, start)
    }

    private fun pauseCurrent(context: Context) {
        val current = ConnectionRuntime.current()
        val profileId = current.profileId ?: return
        val mode = current.mode ?: return
        if (current.state != ConnectionState.CONNECTED) return
        val minutes = SettingsRepository(context).load().notificationPauseMinutes
        ConnectionPauseController.schedule(
            context = context,
            profileId = profileId,
            mode = mode,
            durationMillis = minutes * 60_000L
        )
        val stop = if (mode == ConnectionMode.PROXY) {
            Intent(context, ConnectionService::class.java).setAction(ConnectionService.ACTION_STOP)
        } else {
            Intent(context, TrivoxVpnService::class.java).setAction(TrivoxVpnService.ACTION_STOP)
        }
        context.startService(stop)
        Diagnostics.info("Notification pause requested; minutes=$minutes, mode=$mode")
    }

    private fun activeMode(context: Context): ConnectionMode =
        ConnectionRuntime.current().mode
            ?: ConnectionSessionStore.load(context)?.mode
            ?: SettingsRepository(context).load().mode

    companion object {
        const val BEGIN = "com.trivox.client.NOTIFY_SWITCH_BEGIN"
        const val PREV_GROUP = "com.trivox.client.NOTIFY_SWITCH_PREV_GROUP"
        const val NEXT_GROUP = "com.trivox.client.NOTIFY_SWITCH_NEXT_GROUP"
        const val OPEN_PROFILES = "com.trivox.client.NOTIFY_SWITCH_PROFILES"
        const val PREV_PROFILE = "com.trivox.client.NOTIFY_SWITCH_PREV_PROFILE"
        const val NEXT_PROFILE = "com.trivox.client.NOTIFY_SWITCH_NEXT_PROFILE"
        const val USE = "com.trivox.client.NOTIFY_SWITCH_USE_DIRECT"
        const val NEXT_CONNECT = "com.trivox.client.NOTIFY_NEXT_CONNECT_DIRECT"
        const val PAUSE = "com.trivox.client.NOTIFY_PAUSE_DIRECT"
        const val CANCEL = "com.trivox.client.NOTIFY_SWITCH_CANCEL"

        private val IDLE_STATES = setOf(
            ConnectionState.DISCONNECTED,
            ConnectionState.ERROR
        )
        private val SUPPORTED_ACTIONS = setOf(
            BEGIN, PREV_GROUP, NEXT_GROUP, OPEN_PROFILES, PREV_PROFILE, NEXT_PROFILE,
            USE, NEXT_CONNECT, PAUSE, CANCEL
        )
    }
}
