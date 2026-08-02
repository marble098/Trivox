package com.trivox.client.core

import android.content.Context
import com.trivox.client.config.XrayConfigBuilder
import com.trivox.client.data.ConnectionState
import com.trivox.client.util.Diagnostics
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

object ConnectionRuntime {
    data class Snapshot(
        val state: ConnectionState = ConnectionState.DISCONNECTED,
        val profileId: String? = null,
        val profileName: String = "",
        val startedElapsed: Long = 0,
        val error: String = ""
    )
    private val snapshot = AtomicReference(Snapshot())
    private val listeners = CopyOnWriteArrayList<(Snapshot) -> Unit>()
    fun current(): Snapshot = snapshot.get()
    fun update(value: Snapshot) { snapshot.set(value); listeners.forEach { it(value) } }
    fun addListener(listener: (Snapshot) -> Unit) { listeners += listener; listener(snapshot.get()) }
    fun removeListener(listener: (Snapshot) -> Unit) { listeners -= listener }
}

class CoreManager(context: Context) {
    val adapter: CoreAdapter = XrayCoreAdapter(context.applicationContext)

    fun prepare(request: CoreStartRequest): Pair<String?, CoreResult> = runCatching {
        val json = XrayConfigBuilder.build(request.profile, request.settings, request.mode, request.tunFd)
        val validation = adapter.validate(json)
        if (!validation.success) null to validation else json to CoreResult(true)
    }.getOrElse { null to CoreResult(false, "Configuration generation failed: ${it.message}") }

    fun start(request: CoreStartRequest, protect: ((Int) -> Boolean)? = null): CoreResult {
        val (json, prepared) = prepare(request)
        if (!prepared.success || json == null) return prepared
        val result = adapter.start(json, protect)
        if (!result.success) Diagnostics.error(result.error)
        return result
    }

    fun startValidated(request: CoreStartRequest, protect: ((Int) -> Boolean)? = null): CoreResult {
        val json = runCatching { XrayConfigBuilder.build(request.profile, request.settings, request.mode, request.tunFd) }
            .getOrElse { return CoreResult(false, "Configuration generation failed: ${it.message}") }
        return adapter.start(json, protect).also { if (!it.success) Diagnostics.error(it.error) }
    }

    fun isRunning(): Boolean = adapter.state().data?.optJSONObject("data")?.optBoolean("running") == true
    fun stop(): CoreResult = adapter.stop()
}
