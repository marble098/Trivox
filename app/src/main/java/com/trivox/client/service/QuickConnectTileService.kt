package com.trivox.client.service

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat
import androidx.core.service.quicksettings.PendingIntentActivityWrapper
import androidx.core.service.quicksettings.TileServiceCompat
import com.trivox.client.R
import com.trivox.client.data.ConfigRepository
import com.trivox.client.data.ConnectionMode

class QuickConnectTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        unlockAndRun {
            val active = ConnectionSessionStore.load(this)
            if (active != null) {
                stopActive(active.mode)
                updateTile()
                return@unlockAndRun
            }

            val last = QuickConnectStore.load(this)
            val repository = ConfigRepository(this)
            val profileId =
                last?.profileId ?: repository.selectedId()
            val profile = repository.find(profileId)

            if (profile == null || !profile.enabled) {
                launchQuickActivity()
                return@unlockAndRun
            }

            val mode = last?.mode ?: ConnectionMode.VPN
            if (mode == ConnectionMode.PROXY) {
                startConnection(
                    ConnectionMode.PROXY,
                    profile.id
                )
            } else if (VpnService.prepare(this) == null) {
                startConnection(
                    ConnectionMode.VPN,
                    profile.id
                )
            } else {
                launchQuickActivity()
            }
        }
    }

    private fun startConnection(
        mode: ConnectionMode,
        profileId: String
    ) {
        val intent =
            if (mode == ConnectionMode.PROXY) {
                Intent(
                    this,
                    ConnectionService::class.java
                )
                    .setAction(
                        ConnectionService.ACTION_START
                    )
                    .putExtra(
                        ConnectionService.EXTRA_PROFILE_ID,
                        profileId
                    )
            } else {
                Intent(
                    this,
                    TrivoxVpnService::class.java
                )
                    .setAction(
                        TrivoxVpnService.ACTION_START
                    )
                    .putExtra(
                        TrivoxVpnService.EXTRA_PROFILE_ID,
                        profileId
                    )
            }

        ContextCompat.startForegroundService(
            this,
            intent
        )

        qsTile?.apply {
            state = Tile.STATE_ACTIVE
            updateTile()
        }
    }

    private fun stopActive(mode: ConnectionMode) {
        val intent =
            if (mode == ConnectionMode.PROXY) {
                Intent(
                    this,
                    ConnectionService::class.java
                )
                    .setAction(
                        ConnectionService.ACTION_STOP
                    )
            } else {
                Intent(
                    this,
                    TrivoxVpnService::class.java
                )
                    .setAction(
                        TrivoxVpnService.ACTION_STOP
                    )
            }

        startService(intent)
    }

    private fun launchQuickActivity() {
        val intent =
            Intent(
                this,
                QuickConnectActivity::class.java
            )
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
                )

        val wrapper =
            PendingIntentActivityWrapper(
                this,
                QUICK_ACTIVITY_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT,
                false
            )

        TileServiceCompat.startActivityAndCollapse(
            this,
            wrapper
        )
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val active =
            ConnectionSessionStore.load(this)
        val last =
            QuickConnectStore.load(this)

        tile.state =
            when {
                active != null ->
                    Tile.STATE_ACTIVE

                last != null ->
                    Tile.STATE_INACTIVE

                else ->
                    Tile.STATE_UNAVAILABLE
            }

        tile.label =
            getString(R.string.quick_tile_label)

        if (Build.VERSION.SDK_INT >= 29) {
            tile.subtitle =
                when {
                    active != null ->
                        active.profileName.ifBlank {
                            getString(
                                R.string.state_connected
                            )
                        }

                    last != null ->
                        last.profileName.ifBlank {
                            getString(
                                R.string.quick_tile_ready
                            )
                        }

                    else ->
                        getString(
                            R.string.quick_tile_no_profile
                        )
                }
        }

        tile.updateTile()
    }

    companion object {
        private const val QUICK_ACTIVITY_REQUEST_CODE = 44

        fun requestUpdate(context: Context) {
            runCatching {
                TileService.requestListeningState(
                    context,
                    ComponentName(
                        context,
                        QuickConnectTileService::class.java
                    )
                )
            }
        }
    }
}
