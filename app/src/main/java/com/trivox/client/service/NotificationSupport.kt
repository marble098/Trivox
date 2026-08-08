package com.trivox.client.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.trivox.client.R
import com.trivox.client.data.ConfigRepository
import com.trivox.client.data.ConnectionMode
import com.trivox.client.data.SubscriptionRepository
import com.trivox.client.ui.MainActivity
import java.util.Locale

internal object NotificationSupport {
    const val CHANNEL = "trivox_connection"
    const val ID = 3107

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
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
            manager.createNotificationChannel(channel)
        }
    }

    fun build(
        service: Service,
        title: String,
        profileId: String?,
        mode: ConnectionMode,
        startedElapsed: Long,
        stopIntent: Intent
    ): Notification {
        val openIntent = PendingIntent.getActivity(
            service,
            REQUEST_OPEN,
            Intent(service, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopPendingIntent = PendingIntent.getService(
            service,
            if (mode == ConnectionMode.PROXY) REQUEST_STOP_PROXY else REQUEST_STOP_VPN,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        fun actionIntent(action: String, requestCode: Int): PendingIntent =
            PendingIntent.getActivity(
                service,
                requestCode,
                Intent(service, NotificationActionActivity::class.java)
                    .setAction(action),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

        val profile = ConfigRepository(service).find(profileId)
        val subscription = profile?.subscriptionId?.let {
            SubscriptionRepository(service).find(it)?.name
        }
        val modeText = service.getString(
            if (mode == ConnectionMode.PROXY) {
                R.string.notification_proxy_active
            } else {
                R.string.notification_vpn_active
            }
        )
        val detail = listOfNotNull(
            modeText,
            subscription?.takeIf(String::isNotBlank)
        ).joinToString(" • ")

        val builder = NotificationCompat.Builder(service, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(
                title.ifBlank { service.getString(R.string.app_name) }
            )
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
            .setContentIntent(openIntent)
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
            .addAction(
                0,
                service.getString(R.string.notification_switch),
                actionIntent(
                    NotificationActionActivity.ACTION_PICK,
                    REQUEST_PICK
                )
            )
            .addAction(
                0,
                service.getString(R.string.notification_next),
                actionIntent(
                    NotificationActionActivity.ACTION_NEXT,
                    REQUEST_NEXT
                )
            )
            .addAction(
                0,
                service.getString(R.string.notification_pause_15),
                actionIntent(
                    NotificationActionActivity.ACTION_PAUSE_15,
                    REQUEST_PAUSE
                )
            )
            .addAction(
                0,
                service.getString(R.string.stop),
                stopPendingIntent
            )

        if (
            startedElapsed > 0L &&
            startedElapsed <= SystemClock.elapsedRealtime()
        ) {
            val duration = (
                SystemClock.elapsedRealtime() - startedElapsed
            ).coerceAtLeast(0L)
            builder
                .setWhen(System.currentTimeMillis() - duration)
                .setShowWhen(true)
                .setUsesChronometer(true)
                .setChronometerCountDown(false)
        } else {
            builder
                .setShowWhen(false)
                .setUsesChronometer(false)
        }

        return builder.build()
    }

    fun notifyIfAllowed(
        service: Service,
        notification: Notification
    ) {
        val permissionGranted =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(
                    service,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED

        if (permissionGranted) {
            NotificationManagerCompat.from(service)
                .notify(ID, notification)
        }
    }

    fun formatDuration(ms: Long): String {
        val totalSeconds = ms.coerceAtLeast(0) / 1000
        val hours = totalSeconds / 3600
        val minutes = totalSeconds % 3600 / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(
                Locale.US,
                "%02d:%02d:%02d",
                hours,
                minutes,
                seconds
            )
        } else {
            String.format(
                Locale.US,
                "%02d:%02d",
                minutes,
                seconds
            )
        }
    }

    private const val REQUEST_OPEN = 31071
    private const val REQUEST_STOP_PROXY = 31072
    private const val REQUEST_STOP_VPN = 31073
    private const val REQUEST_PICK = 31074
    private const val REQUEST_NEXT = 31075
    private const val REQUEST_PAUSE = 31076
}
