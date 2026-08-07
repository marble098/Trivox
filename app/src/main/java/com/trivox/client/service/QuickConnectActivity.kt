package com.trivox.client.service

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.trivox.client.R
import com.trivox.client.data.ConfigRepository
import com.trivox.client.data.ConnectionMode
import com.trivox.client.data.SettingsRepository
import com.trivox.client.util.Diagnostics

class QuickConnectActivity : AppCompatActivity() {
    private var pendingProfileId: String? = null

    private val vpnPermission = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val id = pendingProfileId
        pendingProfileId = null
        if (id != null && VpnService.prepare(this) == null) {
            startVpn(id)
        } else {
            finishWithMessage(R.string.permission_required)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val active = ConnectionSessionStore.load(this)
        if (active != null) {
            stop(active.mode)
            finish()
            return
        }

        val repository = ConfigRepository(this)
        val saved = QuickConnectStore.load(this)
        val profileId = saved?.profileId ?: repository.selectedId()
        val profile = repository.find(profileId)

        if (profile == null || !profile.enabled) {
            finishWithMessage(R.string.quick_tile_no_profile)
            return
        }

        val mode = saved?.mode ?: SettingsRepository(this).load().mode
        Diagnostics.info(
            "Quick Settings requested ${mode.name} for ${profile.name}"
        )

        if (mode == ConnectionMode.PROXY) {
            ContextCompat.startForegroundService(
                this,
                Intent(this, ConnectionService::class.java)
                    .setAction(ConnectionService.ACTION_START)
                    .putExtra(ConnectionService.EXTRA_PROFILE_ID, profile.id)
            )
            finish()
            return
        }

        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent == null) {
            startVpn(profile.id)
        } else {
            pendingProfileId = profile.id
            vpnPermission.launch(prepareIntent)
        }
    }

    private fun startVpn(profileId: String) {
        ContextCompat.startForegroundService(
            this,
            Intent(this, TrivoxVpnService::class.java)
                .setAction(TrivoxVpnService.ACTION_START)
                .putExtra(TrivoxVpnService.EXTRA_PROFILE_ID, profileId)
        )
        finish()
    }

    private fun stop(mode: ConnectionMode) {
        val intent = if (mode == ConnectionMode.PROXY) {
            Intent(this, ConnectionService::class.java)
                .setAction(ConnectionService.ACTION_STOP)
        } else {
            Intent(this, TrivoxVpnService::class.java)
                .setAction(TrivoxVpnService.ACTION_STOP)
        }
        startService(intent)
    }

    private fun finishWithMessage(resource: Int) {
        Toast.makeText(this, resource, Toast.LENGTH_LONG).show()
        finish()
    }
}
