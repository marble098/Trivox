package com.trivox.client.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.trivox.client.R
import com.trivox.client.core.ConnectionRuntime
import com.trivox.client.data.ConfigRepository
import com.trivox.client.data.ConnectionMode
import com.trivox.client.data.ConnectionState
import com.trivox.client.data.SubscriptionRepository
import com.trivox.client.ui.MainActivity
import java.util.Locale

internal object NotificationSupport {
    const val CHANNEL = "trivox_connection"
    const val ID = 3107

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(
                    NotificationChannel(
                        CHANNEL,
                        context.getString(R.string.notification_channel),
                        NotificationManager.IMPORTANCE_LOW
                    ).apply {
                        description = context.getString(
                            R.string.notification_channel_description
                        )
                        setShowBadge(false)
                        lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                    }
                )
        }
    }

    fun build(
        service: Context,
        title: String,
        profileId: String?,
        mode: ConnectionMode,
        startedElapsed: Long,
        stopIntent: Intent
    ): Notification {
        fun flags() =
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        val open = PendingIntent.getActivity(
            service, 31071,
            Intent(service, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            }, flags()
        )
        val stop = PendingIntent.getService(
            service,
            if (mode == ConnectionMode.PROXY) 31072 else 31073,
            stopIntent, flags()
        )
        fun activity(action: String, code: Int, id: String? = null) =
            PendingIntent.getActivity(
                service, code,
                Intent(service, NotificationActionActivity::class.java)
                    .setAction(action)
                    .apply {
                        id?.let {
                            putExtra(NotificationActionActivity.EXTRA_PROFILE_ID, it)
                        }
                    }, flags()
            )
        fun receiver(action: String, code: Int) =
            PendingIntent.getBroadcast(
                service, code,
                Intent(service, NotificationQuickSwitchReceiver::class.java)
                    .setAction(action), flags()
            )

        val profile = ConfigRepository(service).find(profileId)
        val subscription = profile?.subscriptionId?.let {
            SubscriptionRepository(service).find(it)?.name
        }
        val modeText = service.getString(
            if (mode == ConnectionMode.PROXY)
                R.string.notification_proxy_active
            else R.string.notification_vpn_active
        )
        val detail = listOfNotNull(
            modeText, subscription?.takeIf(String::isNotBlank)
        ).joinToString(" • ")

        val b = NotificationCompat.Builder(service, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title.ifBlank { service.getString(R.string.app_name) })
            .setContentText(detail)
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    listOfNotNull(
                        detail,
                        profile?.let {
                            "${it.protocol.uppercase(Locale.ROOT)} • ${it.server}:${it.port}"
                        }
                    ).joinToString("\n")
                )
            )
            .setContentIntent(open)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(
                NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE
            )
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)

        val quick = NotificationQuickSwitchStore.current(service)
        if (quick == null) {
            // Keep Stop visible; Next is available inside quick-switch mode.
            b.addAction(
                0, service.getString(R.string.notification_switch),
                receiver(NotificationQuickSwitchReceiver.BEGIN, 31101)
            ).addAction(
                0, service.getString(R.string.notification_pause_15),
                activity(NotificationActionActivity.ACTION_PAUSE_15, 31102)
            ).addAction(
                0, service.getString(R.string.stop), stop
            )
        } else if (quick.stage == NotificationQuickSwitchStore.Stage.SUBSCRIPTION) {
            b.setContentTitle(
                service.getString(R.string.v16_notification_choose_subscription)
            ).setContentText(
                service.getString(
                    R.string.v16_notification_group_summary,
                    quick.group.name, quick.group.profiles.size,
                    quick.groupIndex + 1, quick.groupCount
                )
            ).setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    service.getString(
                        R.string.v16_notification_group_help,
                        quick.group.name, quick.group.profiles.size
                    )
                )
            ).addAction(
                0, service.getString(R.string.v16_notification_previous),
                receiver(NotificationQuickSwitchReceiver.PREV_GROUP, 31103)
            ).addAction(
                0, service.getString(R.string.v16_notification_profiles),
                receiver(NotificationQuickSwitchReceiver.OPEN_PROFILES, 31104)
            ).addAction(
                0, service.getString(R.string.v16_notification_next),
                receiver(NotificationQuickSwitchReceiver.NEXT_GROUP, 31105)
            )
        } else {
            val candidate = quick.profile
            if (candidate != null) {
                b.setContentTitle(candidate.name)
                    .setContentText(
                        service.getString(
                            R.string.v16_notification_profile_summary,
                            quick.group.name,
                            quick.profileIndex + 1, quick.profileCount
                        )
                    )
                    .setStyle(
                        NotificationCompat.BigTextStyle().bigText(
                            "${candidate.protocol.uppercase(Locale.ROOT)} • " +
                                "${candidate.server}:${candidate.port}"
                        )
                    )
                    .addAction(
                        0, service.getString(R.string.v16_notification_previous),
                        receiver(NotificationQuickSwitchReceiver.PREV_PROFILE, 31106)
                    )
                    .addAction(
                        0, service.getString(R.string.v16_notification_use),
                        activity(
                            NotificationActionActivity.ACTION_APPLY_PROFILE,
                            31107, candidate.id
                        )
                    )
                    .addAction(
                        0, service.getString(R.string.v16_notification_next),
                        receiver(NotificationQuickSwitchReceiver.NEXT_PROFILE, 31108)
                    )
            }
        }

        if (startedElapsed > 0L && startedElapsed <= SystemClock.elapsedRealtime()) {
            val duration =
                (SystemClock.elapsedRealtime() - startedElapsed).coerceAtLeast(0L)
            b.setWhen(System.currentTimeMillis() - duration)
                .setShowWhen(true)
                .setUsesChronometer(true)
                .setChronometerCountDown(false)
        } else {
            b.setShowWhen(false).setUsesChronometer(false)
        }
        return b.build()
    }

    fun refresh(context: Context) {
        val current = ConnectionRuntime.current()
        if (current.state !in setOf(
                ConnectionState.PREPARING,
                ConnectionState.CONNECTING,
                ConnectionState.CONNECTED,
                ConnectionState.RECONNECTING,
                ConnectionState.STOPPING
            )
        ) {
            NotificationQuickSwitchStore.clear(context)
            return
        }
        val session = ConnectionSessionStore.load(context)
        val mode = current.mode ?: session?.mode ?: return
        val profileId = current.profileId ?: session?.profileId
        val profile = ConfigRepository(context).find(profileId)
        val stopIntent =
            if (mode == ConnectionMode.PROXY)
                Intent(context, ConnectionService::class.java)
                    .setAction(ConnectionService.ACTION_STOP)
            else
                Intent(context, TrivoxVpnService::class.java)
                    .setAction(TrivoxVpnService.ACTION_STOP)
        notifyIfAllowed(
            context,
            build(
                service = context,
                title = profile?.name ?: current.profileName.ifBlank {
                    context.getString(R.string.app_name)
                },
                profileId = profileId,
                mode = mode,
                startedElapsed = current.startedElapsed,
                stopIntent = stopIntent
            )
        )
    }

    fun notifyIfAllowed(service: Context, notification: Notification) {
        val granted =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(
                    service, Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
        if (granted) NotificationManagerCompat.from(service).notify(ID, notification)
    }

    fun formatDuration(ms: Long): String {
        val totalSeconds = ms.coerceAtLeast(0) / 1000
        val hours = totalSeconds / 3600
        val minutes = totalSeconds % 3600 / 60
        val seconds = totalSeconds % 60
        return if (hours > 0)
            String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
        else
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}
