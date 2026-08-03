package com.trivox.client.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.core.content.ContextCompat
import com.trivox.client.data.ConfigRepository
import com.trivox.client.data.ConnectionMode
import com.trivox.client.data.SettingsRepository
import com.trivox.client.util.Diagnostics

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent
    ) {
        if (
            intent.action !=
            Intent.ACTION_BOOT_COMPLETED
        ) {
            return
        }

        Diagnostics.initialize(context)

        if (
            ConnectionPauseController
                .restoreOrResume(context)
        ) {
            return
        }

        if (
            Diagnostics
                .hasRecentNativeCrash()
        ) {
            Diagnostics.warning(
                "Automatic boot reconnect was " +
                    "skipped after a recent " +
                    "native crash"
            )
            return
        }

        val settings =
            SettingsRepository(context)
                .load()

        if (!settings.reconnectOnBoot) {
            return
        }

        val profileId =
            ConfigRepository(context)
                .selectedId()
                ?: return

        if (
            settings.mode ==
            ConnectionMode.VPN &&
            VpnService.prepare(context) !=
            null
        ) {
            Diagnostics.warning(
                "Boot VPN reconnect needs " +
                    "user permission"
            )
            return
        }

        val service =
            if (
                settings.mode ==
                ConnectionMode.VPN
            ) {
                Intent(
                    context,
                    TrivoxVpnService::class.java
                )
                    .setAction(
                        TrivoxVpnService
                            .ACTION_START
                    )
                    .putExtra(
                        TrivoxVpnService
                            .EXTRA_PROFILE_ID,
                        profileId
                    )
            } else {
                Intent(
                    context,
                    ConnectionService::class.java
                )
                    .setAction(
                        ConnectionService
                            .ACTION_START
                    )
                    .putExtra(
                        ConnectionService
                            .EXTRA_PROFILE_ID,
                        profileId
                    )
            }

        Diagnostics.info(
            "Starting saved connection " +
                "after device boot"
        )

        ContextCompat
            .startForegroundService(
                context,
                service
            )
    }
}
