package com.trivox.client.service

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.trivox.client.R
import com.trivox.client.core.ConnectionRuntime
import com.trivox.client.data.ConfigProfile
import com.trivox.client.data.ConfigRepository
import com.trivox.client.data.ConnectionMode
import com.trivox.client.data.ConnectionState
import com.trivox.client.data.SubscriptionRepository
import com.trivox.client.util.Diagnostics

class NotificationActionActivity : AppCompatActivity() {
    private var pendingProfileId: String? = null
    private var pendingMode: ConnectionMode? = null

    private val vpnPermission = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val profileId = pendingProfileId
        val mode = pendingMode
        if (
            profileId != null &&
            mode == ConnectionMode.VPN &&
            VpnService.prepare(this) == null
        ) {
            startProfile(profileId, mode)
        } else {
            finishWithMessage(R.string.permission_required)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        when (intent?.action) {
            ACTION_PICK -> showSubscriptionPicker()
            ACTION_NEXT -> switchToNext()
            ACTION_PAUSE_15 -> pauseCurrent(15)
            ACTION_APPLY_PROFILE -> applyQuickSwitchProfile()
            else -> finish()
        }
    }

    private fun applyQuickSwitchProfile() {
        val id = intent.getStringExtra(EXTRA_PROFILE_ID)
        val profile = id?.let { ConfigRepository(this).find(it) }
        if (profile == null) {
            NotificationQuickSwitchStore.clear(this)
            finishWithMessage(R.string.quick_tile_no_profile)
            return
        }
        NotificationQuickSwitchStore.clear(this)
        switchTo(profile, activeMode())
    }

    private fun showSubscriptionPicker() {
        val repository = ConfigRepository(this)
        val profiles = repository.all().filter { it.enabled }
        if (profiles.isEmpty()) {
            finishWithMessage(R.string.quick_tile_no_profile)
            return
        }

        val subscriptions = SubscriptionRepository(this)
            .all()
            .filter { source -> profiles.any { it.subscriptionId == source.id } }
            .sortedBy { it.name.lowercase() }
        val entries = mutableListOf<PickerGroup>()
        entries += PickerGroup(
            getString(R.string.notification_all_configs),
            profiles
        )
        val local = profiles.filter { it.subscriptionId == null }
        if (local.isNotEmpty()) {
            entries += PickerGroup(
                getString(R.string.notification_local_configs),
                local
            )
        }
        subscriptions.forEach { source ->
            val sourceProfiles = profiles.filter { it.subscriptionId == source.id }
            if (sourceProfiles.isNotEmpty()) {
                entries += PickerGroup(source.name, sourceProfiles)
            }
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.notification_choose_subscription)
            .setItems(entries.map { "${it.name} (${it.profiles.size})" }.toTypedArray()) { _, which ->
                showProfilePicker(entries[which])
            }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun showProfilePicker(group: PickerGroup) {
        val profiles = group.profiles.sortedBy { it.name.lowercase() }
        AlertDialog.Builder(this)
            .setTitle(group.name)
            .setItems(profiles.map { it.name }.toTypedArray()) { _, which ->
                val mode = activeMode()
                switchTo(profiles[which], mode)
            }
            .setNegativeButton(R.string.cancel) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun switchToNext() {
        val repository = ConfigRepository(this)
        val current = ConnectionRuntime.current()
        val currentProfile = repository.find(current.profileId ?: repository.selectedId())
        val all = repository.all().filter { it.enabled }
        if (all.isEmpty()) {
            finishWithMessage(R.string.quick_tile_no_profile)
            return
        }

        val sameGroup = currentProfile?.let { active ->
            all.filter { it.subscriptionId == active.subscriptionId }
        }.orEmpty()
        val pool = sameGroup.takeIf { it.size > 1 } ?: all
        val currentIndex = pool.indexOfFirst { it.id == currentProfile?.id }
        val next = pool[
            if (currentIndex < 0 || currentIndex + 1 >= pool.size) 0
            else currentIndex + 1
        ]
        switchTo(next, activeMode())
    }

    private fun switchTo(profile: ConfigProfile, mode: ConnectionMode) {
        val repository = ConfigRepository(this)
        repository.select(profile.id)
        QuickConnectStore.save(this, mode, profile.id, profile.name)
        Diagnostics.info("Notification switch requested; mode=${mode.name}, profile=${profile.id}")
        pendingProfileId = profile.id
        pendingMode = mode
        val current = ConnectionRuntime.current()
        if (current.state !in setOf(ConnectionState.DISCONNECTED, ConnectionState.ERROR)) {
            if (!ConnectionSwitchCoordinator.queue(this, profile.id, mode, "notification")) {
                finishWithMessage(R.string.quick_tile_no_profile)
            } else {
                finish()
            }
        } else {
            requestStart(profile.id, mode)
        }
    }

    private fun requestStart(profileId: String, mode: ConnectionMode) {
        pendingProfileId = profileId
        pendingMode = mode

        if (mode == ConnectionMode.PROXY) {
            startProfile(profileId, mode)
            return
        }

        val prepare = VpnService.prepare(this)
        if (prepare == null) {
            startProfile(profileId, mode)
        } else {
            vpnPermission.launch(prepare)
        }
    }

    private fun startProfile(profileId: String, mode: ConnectionMode) {
        val intent = if (mode == ConnectionMode.PROXY) {
            Intent(this, ConnectionService::class.java)
                .setAction(ConnectionService.ACTION_START)
                .putExtra(ConnectionService.EXTRA_PROFILE_ID, profileId)
        } else {
            Intent(this, TrivoxVpnService::class.java)
                .setAction(TrivoxVpnService.ACTION_START)
                .putExtra(TrivoxVpnService.EXTRA_PROFILE_ID, profileId)
        }
        runCatching { ContextCompat.startForegroundService(this, intent) }
            .onFailure {
                Diagnostics.recordThrowable("Notification connection start", it)
                Toast.makeText(this, R.string.connection_start_failed, Toast.LENGTH_LONG).show()
            }
        finish()
    }

    private fun pauseCurrent(minutes: Int) {
        val current = ConnectionRuntime.current()
        val profileId = current.profileId
        val mode = current.mode
        if (
            profileId.isNullOrBlank() ||
            mode == null ||
            current.state != ConnectionState.CONNECTED
        ) {
            finishWithMessage(R.string.connect_first)
            return
        }

        ConnectionPauseController.schedule(
            context = this,
            profileId = profileId,
            mode = mode,
            durationMillis = minutes * 60_000L
        )
        stop(mode)
        Toast.makeText(
            this,
            getString(R.string.pause_scheduled, minutes),
            Toast.LENGTH_SHORT
        ).show()
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

    private fun activeMode(): ConnectionMode =
        ConnectionRuntime.current().mode
            ?: ConnectionSessionStore.load(this)?.mode
            ?: com.trivox.client.data.SettingsRepository(this).load().mode

    private fun finishWithMessage(resource: Int) {
        Toast.makeText(this, resource, Toast.LENGTH_LONG).show()
        finish()
    }

    private data class PickerGroup(
        val name: String,
        val profiles: List<ConfigProfile>
    )

    companion object {
        const val ACTION_PICK =
            "com.trivox.client.NOTIFICATION_PICK_PROFILE"
        const val ACTION_NEXT =
            "com.trivox.client.NOTIFICATION_NEXT_PROFILE"
        const val ACTION_PAUSE_15 =
            "com.trivox.client.NOTIFICATION_PAUSE_15"
        const val ACTION_APPLY_PROFILE =
            "com.trivox.client.NOTIFICATION_APPLY_PROFILE"
        const val EXTRA_PROFILE_ID = "profile_id"

    }
}
