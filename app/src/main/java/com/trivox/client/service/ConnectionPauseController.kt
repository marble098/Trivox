package com.trivox.client.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.core.content.ContextCompat
import com.trivox.client.data.ConnectionMode
import com.trivox.client.util.Diagnostics

object ConnectionPauseController {
    data class PendingPause(
        val profileId: String,
        val mode: ConnectionMode,
        val resumeAt: Long
    ) {
        fun remainingMillis(
            now: Long = System.currentTimeMillis()
        ): Long =
            (resumeAt - now)
                .coerceAtLeast(0L)
    }

    fun schedule(
        context: Context,
        profileId: String,
        mode: ConnectionMode,
        durationMillis: Long
    ): PendingPause {
        val bounded =
            durationMillis.coerceIn(
                MIN_PAUSE_MS,
                MAX_PAUSE_MS
            )
        val pending =
            PendingPause(
                profileId = profileId,
                mode = mode,
                resumeAt =
                    System.currentTimeMillis() +
                        bounded
            )

        preferences(context)
            .edit()
            .putString(
                KEY_PROFILE_ID,
                pending.profileId
            )
            .putString(
                KEY_MODE,
                pending.mode.name
            )
            .putLong(
                KEY_RESUME_AT,
                pending.resumeAt
            )
            .apply()

        scheduleAlarm(
            context,
            pending.resumeAt
        )

        Diagnostics.info(
            "Connection paused until " +
                pending.resumeAt
        )

        return pending
    }

    fun pending(
        context: Context
    ): PendingPause? {
        val prefs =
            preferences(context)
        val profileId =
            prefs.getString(
                KEY_PROFILE_ID,
                null
            )
                ?.takeIf(String::isNotBlank)
                ?: return null
        val mode =
            runCatching {
                ConnectionMode.valueOf(
                    prefs.getString(
                        KEY_MODE,
                        ""
                    ).orEmpty()
                )
            }.getOrNull()
                ?: return null
        val resumeAt =
            prefs.getLong(
                KEY_RESUME_AT,
                0L
            )

        return resumeAt
            .takeIf {
                it > 0L
            }
            ?.let {
                PendingPause(
                    profileId,
                    mode,
                    it
                )
            }
    }

    fun cancel(
        context: Context
    ) {
        preferences(context)
            .edit()
            .clear()
            .apply()

        alarmManager(context)
            .cancel(
                resumeIntent(context)
            )
    }

    fun restoreOrResume(
        context: Context
    ): Boolean {
        val pending =
            pending(context)
                ?: return false

        if (
            pending.remainingMillis() > 0L
        ) {
            scheduleAlarm(
                context,
                pending.resumeAt
            )
            return true
        }

        resumeNow(context)
        return true
    }

    fun resumeNow(
        context: Context
    ): Boolean {
        val pending =
            pending(context)
                ?: return false

        if (
            pending.mode ==
            ConnectionMode.VPN &&
            VpnService.prepare(context) != null
        ) {
            Diagnostics.warning(
                "Paused VPN could not resume because " +
                    "Android requires VPN permission again"
            )
            cancel(context)
            return false
        }

        cancel(context)

        val service =
            if (
                pending.mode ==
                ConnectionMode.VPN
            ) {
                Intent(
                    context,
                    TrivoxVpnService::class.java
                )
                    .setAction(
                        TrivoxVpnService.ACTION_START
                    )
                    .putExtra(
                        TrivoxVpnService.EXTRA_PROFILE_ID,
                        pending.profileId
                    )
            } else {
                Intent(
                    context,
                    ConnectionService::class.java
                )
                    .setAction(
                        ConnectionService.ACTION_START
                    )
                    .putExtra(
                        ConnectionService.EXTRA_PROFILE_ID,
                        pending.profileId
                    )
            }

        return runCatching {
            ContextCompat.startForegroundService(
                context,
                service
            )
            Diagnostics.info(
                "Paused connection resumed automatically"
            )
            true
        }.getOrElse {
            Diagnostics.recordThrowable(
                "Pause auto resume",
                it
            )
            false
        }
    }

    private fun scheduleAlarm(
        context: Context,
        resumeAt: Long
    ) {
        alarmManager(context)
            .setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                resumeAt,
                resumeIntent(context)
            )
    }

    private fun resumeIntent(
        context: Context
    ): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(
                context,
                PauseResumeReceiver::class.java
            ).setAction(ACTION_RESUME),
            PendingIntent.FLAG_UPDATE_CURRENT or
                PendingIntent.FLAG_IMMUTABLE
        )

    private fun preferences(
        context: Context
    ) =
        context.applicationContext
            .getSharedPreferences(
                PREFS,
                Context.MODE_PRIVATE
            )

    private fun alarmManager(
        context: Context
    ): AlarmManager =
        context.getSystemService(
            Context.ALARM_SERVICE
        ) as AlarmManager

    const val ACTION_RESUME =
        "com.trivox.client.action.RESUME_PAUSED_CONNECTION"

    private const val PREFS =
        "connection_pause"
    private const val KEY_PROFILE_ID =
        "profile_id"
    private const val KEY_MODE =
        "mode"
    private const val KEY_RESUME_AT =
        "resume_at"
    private const val REQUEST_CODE = 7301
    private const val MIN_PAUSE_MS =
        60_000L
    private const val MAX_PAUSE_MS =
        24L * 60L * 60L * 1_000L
}
