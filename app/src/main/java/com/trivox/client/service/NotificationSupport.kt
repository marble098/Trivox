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
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.trivox.client.R
import com.trivox.client.ui.MainActivity
import java.util.Locale

internal object NotificationSupport {
    const val CHANNEL = "trivox_connection"
    const val ID = 3107

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager =
                context.getSystemService(NotificationManager::class.java)

            val channel = NotificationChannel(
                CHANNEL,
                context.getString(R.string.notification_channel),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description =
                    context.getString(
                        R.string.notification_channel_description
                    )

                setShowBadge(false)
            }

            manager.createNotificationChannel(channel)
        }
    }

    fun build(
        service: Service,
        title: String,
        startedElapsed: Long,
        stopIntent: Intent
    ): Notification {
        val duration =
            if (startedElapsed > 0) {
                android.os.SystemClock.elapsedRealtime() -
                    startedElapsed
            } else {
                0L
            }

        val openIntent = PendingIntent.getActivity(
            service,
            1,
            Intent(service, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or
                PendingIntent.FLAG_IMMUTABLE
        )

        val stopPendingIntent = PendingIntent.getService(
            service,
            2,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(service, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title)
            .setContentText(
                service.getString(
                    R.string.session_time,
                    formatDuration(duration)
                )
            )
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .addAction(
                0,
                service.getString(R.string.stop),
                stopPendingIntent
            )
            .build()
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
            NotificationManagerCompat
                .from(service)
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
}
