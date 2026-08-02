package com.trivox.client.core

import android.content.Context
import com.trivox.client.config.XrayConfigBuilder
import com.trivox.client.data.ConnectionMode
import com.trivox.client.data.ConnectionState
import com.trivox.client.util.Diagnostics
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

object ConnectionRuntime {
    data class Snapshot(
        val state: ConnectionState =
            ConnectionState.DISCONNECTED,
        val profileId: String? = null,
        val profileName: String = "",
        val startedElapsed: Long = 0,
        val error: String = "",
        val mode: ConnectionMode? = null,
        val sessionId: Long = 0
    )

    private val snapshot =
        AtomicReference(Snapshot())
    private val listeners =
        CopyOnWriteArrayList<(Snapshot) -> Unit>()
    private val sessionCounter = AtomicLong(0)

    fun current(): Snapshot = snapshot.get()

    fun nextSessionId(): Long =
        sessionCounter.incrementAndGet()

    fun update(value: Snapshot) {
        snapshot.set(value)
        notifyListeners(value)
    }

    fun updateSession(
        sessionId: Long,
        transform: (Snapshot) -> Snapshot
    ): Boolean {
        while (true) {
            val current = snapshot.get()

            if (
                current.sessionId != sessionId ||
                sessionId == 0L
            ) {
                return false
            }

            val next = transform(current)

            if (
                snapshot.compareAndSet(
                    current,
                    next
                )
            ) {
                notifyListeners(next)
                return true
            }
        }
    }

    fun addListener(
        listener: (Snapshot) -> Unit
    ) {
        listeners += listener
        runCatching {
            listener(snapshot.get())
        }.onFailure {
            Diagnostics.warning(
                "Connection listener failed: ${it.message}"
            )
        }
    }

    private fun notifyListeners(value: Snapshot) {
        listeners.forEach { listener ->
            runCatching {
                listener(value)
            }.onFailure {
                Diagnostics.warning(
                    "Connection listener failed: ${it.message}"
                )
            }
        }
    }

    fun removeListener(
        listener: (Snapshot) -> Unit
    ) {
        listeners -= listener
    }
}

class CoreManager(context: Context) {
    val adapter: CoreAdapter =
        XrayCoreAdapter(
            context.applicationContext
        )

    fun prepare(
        request: CoreStartRequest
    ): Pair<String?, CoreResult> =
        runCatching {
            val json = XrayConfigBuilder.build(
                request.profile,
                request.settings,
                request.mode,
                request.tunFd
            )

            val validation =
                adapter.validate(json)

            if (!validation.success) {
                null to validation
            } else {
                json to CoreResult(true)
            }
        }.getOrElse {
            null to CoreResult(
                false,
                "Configuration generation failed: " +
                    it.message
            )
        }

    fun start(
        request: CoreStartRequest,
        protect: ((Int) -> Boolean)? = null
    ): CoreResult {
        val (json, prepared) =
            prepare(request)

        if (
            !prepared.success ||
            json == null
        ) {
            return prepared
        }

        val result =
            adapter.start(json, protect)

        if (!result.success) {
            Diagnostics.error(result.error)
        }

        return result
    }

    fun startValidated(
        request: CoreStartRequest,
        protect: ((Int) -> Boolean)? = null
    ): CoreResult {
        val json = runCatching {
            XrayConfigBuilder.build(
                request.profile,
                request.settings,
                request.mode,
                request.tunFd
            )
        }.getOrElse {
            return CoreResult(
                false,
                "Configuration generation failed: " +
                    it.message
            )
        }

        return adapter
            .start(json, protect)
            .also {
                if (!it.success) {
                    Diagnostics.error(it.error)
                }
            }
    }

    fun isRunning(): Boolean =
        adapter.state()
            .data
            ?.optJSONObject("data")
            ?.optBoolean("running") == true

    fun stop(): CoreResult =
        adapter.stop()
}
