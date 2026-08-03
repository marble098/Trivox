package com.trivox.client.network

import android.content.Context
import com.trivox.client.data.ConfigRepository
import com.trivox.client.data.SubscriptionRepository
import com.trivox.client.data.SubscriptionSource
import com.trivox.client.util.Diagnostics
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class SubscriptionRefreshCoordinator(
    context: Context
) : AutoCloseable {
    data class Summary(
        val updated: Int,
        val failed: Int,
        val profiles: Int
    )

    private val appContext = context.applicationContext
    private val worker = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)

    fun refreshEnabled(
        callback: (Summary) -> Unit
    ): Boolean {
        if (!busy.compareAndSet(false, true)) {
            return false
        }

        worker.execute {
            var updated = 0
            var failed = 0
            var profileCount = 0

            try {
                val sources = SubscriptionRepository(appContext)
                    .all()
                    .filter { it.enabled }

                for (source in sources) {
                    runCatching {
                        refreshOne(source)
                    }.onSuccess {
                        profileCount += it
                        updated += 1
                    }.onFailure { error ->
                        failed += 1
                        SubscriptionRepository(appContext).update(source.id) {
                            it.lastError = error.message.orEmpty()
                        }
                        Diagnostics.warning(
                            "Subscription ${source.name} failed: ${error.message}"
                        )
                    }
                }
            } finally {
                busy.set(false)
            }

            runCatching {
                callback(
                    Summary(
                        updated = updated,
                        failed = failed,
                        profiles = profileCount
                    )
                )
            }.onFailure {
                Diagnostics.warning(
                    "Subscription refresh callback failed: ${it.message}"
                )
            }
        }
        return true
    }

    private fun refreshOne(source: SubscriptionSource): Int {
        val result = SubscriptionManager().fetch(source.url)
        val profiles = result.profiles.map {
            it.copy(
                subscriptionId = source.id,
                group = source.name
            )
        }

        ConfigRepository(appContext).replaceSubscription(
            source.id,
            profiles
        )
        SubscriptionRepository(appContext).update(source.id) {
            it.url = result.finalUrl
            it.lastSuccessAt = System.currentTimeMillis()
            it.lastError = ""
        }
        return profiles.size
    }

    override fun close() {
        worker.shutdownNow()
    }
}
