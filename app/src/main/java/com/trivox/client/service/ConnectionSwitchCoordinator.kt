package com.trivox.client.service

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.trivox.client.core.ConnectionRuntime
import com.trivox.client.data.ConfigRepository
import com.trivox.client.data.ConnectionMode
import com.trivox.client.data.ConnectionState
import com.trivox.client.util.Diagnostics
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

internal object ConnectionSwitchCoordinator {
    private const val PREFS = "trivox_connection_switch_v25"
    private const val MAX_AGE_MS = 30_000L
    private const val DESTROY_SETTLE_MS = 320L
    private const val RECOVERY_SETTLE_MS = 500L
    private const val RETRY_SETTLE_MS = 500L
    private const val MAX_START_RETRIES = 2

    private val handler = Handler(Looper.getMainLooper())
    private val dispatchScheduled = AtomicBoolean(false)

    private data class Pending(
        val token: String,
        val profileId: String,
        val targetMode: ConnectionMode,
        val sourceMode: ConnectionMode?,
        val reason: String,
        val requestedAt: Long,
        val retries: Int
    )

    fun queue(
        context: Context,
        profileId: String,
        targetMode: ConnectionMode,
        reason: String
    ): Boolean {
        val app = context.applicationContext
        val profile = ConfigRepository(app).find(profileId)
        if (profile == null || !profile.enabled) {
            Diagnostics.warning("Connection switch rejected: target profile is unavailable")
            return false
        }
        val current = ConnectionRuntime.current()
        save(
            app,
            Pending(
                UUID.randomUUID().toString(), profileId, targetMode,
                current.mode, reason, System.currentTimeMillis(), 0
            )
        )
        Diagnostics.info(
            "Connection switch queued; reason=$reason, source=${current.mode}, " +
                "targetMode=$targetMode, profile=$profileId"
        )
        if (current.state in IDLE_STATES) {
            scheduleDispatch(app, RECOVERY_SETTLE_MS)
        } else {
            requestStop(app, current.mode ?: targetMode)
        }
        return true
    }

    fun deferUntilServiceDestroyed(
        context: Context,
        profileId: String,
        targetMode: ConnectionMode,
        sourceMode: ConnectionMode,
        reason: String
    ) {
        val app = context.applicationContext
        val profile = ConfigRepository(app).find(profileId)
        if (profile == null || !profile.enabled) {
            Diagnostics.warning("Deferred connection start dropped: target profile is unavailable")
            return
        }
        save(
            app,
            Pending(
                UUID.randomUUID().toString(), profileId, targetMode,
                sourceMode, reason, System.currentTimeMillis(), 0
            )
        )
        Diagnostics.info(
            "Connection start deferred until old service destruction; " +
                "mode=$targetMode, profile=$profileId"
        )
    }

    fun onServiceDestroyed(context: Context, mode: ConnectionMode) {
        val app = context.applicationContext
        val pending = load(app) ?: return
        if (expired(pending)) {
            clear(app, pending.token)
            Diagnostics.warning("Expired connection switch was discarded after service destruction")
            return
        }
        if (pending.sourceMode != null && pending.sourceMode != mode) return
        Diagnostics.info(
            "Connection switch source service destroyed; scheduling target after native settle"
        )
        scheduleDispatch(app, DESTROY_SETTLE_MS)
    }

    fun recoverPending(context: Context) {
        val app = context.applicationContext
        val pending = load(app) ?: return
        if (expired(pending)) {
            clear(app, pending.token)
            return
        }
        if (ConnectionRuntime.current().state in IDLE_STATES) {
            Diagnostics.info("Recovering a pending connection switch after process/application restart")
            scheduleDispatch(app, RECOVERY_SETTLE_MS)
        }
    }

    private fun scheduleDispatch(context: Context, delayMs: Long) {
        if (!dispatchScheduled.compareAndSet(false, true)) return
        handler.postDelayed({
            dispatchScheduled.set(false)
            dispatch(context.applicationContext)
        }, delayMs)
    }

    private fun dispatch(context: Context) {
        val pending = load(context) ?: return
        if (expired(pending)) {
            clear(context, pending.token)
            Diagnostics.warning("Connection switch expired before target start")
            return
        }
        val runtime = ConnectionRuntime.current()
        if (runtime.state !in IDLE_STATES) {
            if (runtime.state == ConnectionState.STOPPING ||
                runtime.state == ConnectionState.RECONNECTING
            ) scheduleDispatch(context, RETRY_SETTLE_MS)
            return
        }
        val profile = ConfigRepository(context).find(pending.profileId)
        if (profile == null || !profile.enabled) {
            clear(context, pending.token)
            Diagnostics.warning("Connection switch target disappeared before start")
            return
        }
        if (pending.targetMode == ConnectionMode.VPN && VpnService.prepare(context) != null) {
            clear(context, pending.token)
            Diagnostics.warning(
                "Deferred VPN switch requires Android VPN permission; automatic start was skipped"
            )
            return
        }
        if (!clear(context, pending.token)) return
        val intent = if (pending.targetMode == ConnectionMode.PROXY) {
            Intent(context, ConnectionService::class.java)
                .setAction(ConnectionService.ACTION_START)
                .putExtra(ConnectionService.EXTRA_PROFILE_ID, pending.profileId)
        } else {
            Intent(context, TrivoxVpnService::class.java)
                .setAction(TrivoxVpnService.ACTION_START)
                .putExtra(TrivoxVpnService.EXTRA_PROFILE_ID, pending.profileId)
        }
        runCatching { ContextCompat.startForegroundService(context, intent) }
            .onSuccess {
                Diagnostics.info(
                    "Connection switch target dispatched; mode=${pending.targetMode}, " +
                        "profile=${pending.profileId}, reason=${pending.reason}"
                )
            }
            .onFailure { error ->
                Diagnostics.recordThrowable("Deferred connection switch start", error)
                if (pending.retries < MAX_START_RETRIES && !expired(pending)) {
                    save(context, pending.copy(retries = pending.retries + 1))
                    scheduleDispatch(context, RETRY_SETTLE_MS)
                }
            }
    }

    private fun requestStop(context: Context, mode: ConnectionMode) {
        val intent = if (mode == ConnectionMode.PROXY) {
            Intent(context, ConnectionService::class.java).setAction(ConnectionService.ACTION_STOP)
        } else {
            Intent(context, TrivoxVpnService::class.java).setAction(TrivoxVpnService.ACTION_STOP)
        }
        runCatching { context.startService(intent) }
            .onFailure {
                Diagnostics.recordThrowable("Connection switch stop request", it)
                scheduleDispatch(context, RETRY_SETTLE_MS)
            }
    }

    private fun save(context: Context, value: Pending) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("token", value.token)
            .putString("profile", value.profileId)
            .putString("target_mode", value.targetMode.name)
            .putString("source_mode", value.sourceMode?.name.orEmpty())
            .putString("reason", value.reason)
            .putLong("requested_at", value.requestedAt)
            .putInt("retries", value.retries)
            .commit()
    }

    private fun load(context: Context): Pending? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val token = prefs.getString("token", "").orEmpty()
        val profile = prefs.getString("profile", "").orEmpty()
        val target = runCatching {
            ConnectionMode.valueOf(prefs.getString("target_mode", "").orEmpty())
        }.getOrNull()
        if (token.isBlank() || profile.isBlank() || target == null) return null
        val source = runCatching {
            prefs.getString("source_mode", "").orEmpty()
                .takeIf(String::isNotBlank)?.let { ConnectionMode.valueOf(it) }
        }.getOrNull()
        return Pending(
            token, profile, target, source,
            prefs.getString("reason", "").orEmpty(),
            prefs.getLong("requested_at", 0L), prefs.getInt("retries", 0)
        )
    }

    private fun clear(context: Context, expectedToken: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString("token", "") != expectedToken) return false
        return prefs.edit().clear().commit()
    }

    private fun expired(value: Pending): Boolean =
        value.requestedAt <= 0L || System.currentTimeMillis() - value.requestedAt > MAX_AGE_MS

    private val IDLE_STATES = setOf(ConnectionState.DISCONNECTED, ConnectionState.ERROR)
}
