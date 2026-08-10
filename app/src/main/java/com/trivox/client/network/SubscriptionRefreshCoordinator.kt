package com.trivox.client.network

import android.content.Context
import com.trivox.client.data.ConfigRepository
import com.trivox.client.data.SubscriptionKind
import com.trivox.client.data.SubscriptionRepository
import com.trivox.client.data.SubscriptionSource
import com.trivox.client.util.Diagnostics
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean

class SubscriptionRefreshCoordinator(
    context: Context
) : AutoCloseable {
    data class Progress(
        val completed: Int,
        val total: Int,
        val sourceName: String
    )

    data class Summary(
        val updated: Int,
        val failed: Int,
        val profiles: Int,
        val partial: Int,
        val cancelled: Boolean
    )

    private data class RefreshResult(
        val profiles: Int,
        val partial: Boolean
    )

    private val appContext = context.applicationContext
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "trivox-subscription-refresh").apply {
            isDaemon = true
        }
    }
    private val busy = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    @Volatile
    private var activeTask: Future<*>? = null

    fun refreshEnabled(
        progress: (Progress) -> Unit = {},
        callback: (Summary) -> Unit
    ): Boolean = refreshInternal(
        sourceIds = null,
        enabledOnly = true,
        progress = progress,
        callback = callback
    )

    fun refreshSources(
        sourceIds: Collection<String>,
        progress: (Progress) -> Unit = {},
        callback: (Summary) -> Unit
    ): Boolean {
        val ids = sourceIds
            .asSequence()
            .filter(String::isNotBlank)
            .distinct()
            .toSet()
        if (ids.isEmpty()) return false
        return refreshInternal(
            sourceIds = ids,
            enabledOnly = false,
            progress = progress,
            callback = callback
        )
    }

    private fun refreshInternal(
        sourceIds: Set<String>?,
        enabledOnly: Boolean,
        progress: (Progress) -> Unit,
        callback: (Summary) -> Unit
    ): Boolean {
        if (
            closed.get() ||
            !busy.compareAndSet(false, true)
        ) {
            return false
        }

        activeTask = worker.submit {
            var updated = 0
            var failed = 0
            var profileCount = 0
            var partial = 0
            var cancelled = false

            try {
                val sources = SubscriptionRepository(appContext)
                    .all()
                    .filter { source ->
                        (sourceIds == null || source.id in sourceIds) &&
                            (!enabledOnly || source.enabled) &&
                            (
                                source.kind != SubscriptionKind.URL ||
                                    source.url.isNotBlank()
                            )
                    }

                for ((index, source) in sources.withIndex()) {
                    if (Thread.currentThread().isInterrupted) {
                        cancelled = true
                        break
                    }

                    runCatching {
                        progress(
                            Progress(
                                completed = index,
                                total = sources.size,
                                sourceName = source.name
                            )
                        )
                    }.onFailure { error ->
                        if (error.isSubscriptionCancellation()) {
                            cancelled = true
                        } else {
                            Diagnostics.warning(
                                "Subscription progress callback failed: " +
                                    userMessage(error)
                            )
                        }
                    }

                    if (cancelled) break

                    runCatching {
                        refreshOne(source)
                    }.onSuccess { result ->
                        profileCount += result.profiles
                        updated += 1
                        if (result.partial) partial += 1
                    }.onFailure { error ->
                        if (error.isSubscriptionCancellation()) {
                            cancelled = true
                            return@onFailure
                        }

                        failed += 1
                        SubscriptionRepository(appContext)
                            .update(source.id) {
                                it.lastError = userMessage(error)
                            }
                        Diagnostics.warning(
                            "Subscription ${source.name} failed: " +
                                userMessage(error)
                        )
                    }

                    if (cancelled) break
                }
            } finally {
                busy.set(false)
                activeTask = null
            }

            if (!closed.get()) {
                runCatching {
                    callback(
                        Summary(
                            updated = updated,
                            failed = failed,
                            profiles = profileCount,
                            partial = partial,
                            cancelled = cancelled
                        )
                    )
                }.onFailure {
                    if (!it.isSubscriptionCancellation()) {
                        Diagnostics.warning(
                            "Subscription refresh callback failed: " +
                                userMessage(it)
                        )
                    }
                }
            }
        }
        return true
    }

    private fun refreshOne(source: SubscriptionSource): RefreshResult {
        val result = SubscriptionProfileLoader.load(appContext, source)

        if (result.notModified) {
            // Server confirmed nothing changed (HTTP 304): skip re-parsing
            // and re-merging the whole catalog entirely, only refresh the
            // bookkeeping fields.
            SubscriptionRepository(appContext).update(source.id) {
                if (it.kind == SubscriptionKind.URL) {
                    it.url = result.finalUrl
                    it.etag = result.etag ?: it.etag
                    it.lastModified = result.lastModified ?: it.lastModified
                }
                it.lastSuccessAt = System.currentTimeMillis()
                it.lastError = ""
            }
            return RefreshResult(profiles = 0, partial = false)
        }

        val profiles = result.profiles.map {
            it.copy(
                subscriptionId = source.id,
                group = source.name
            )
        }
        val configs = ConfigRepository(appContext)

        if (result.complete) {
            configs.replaceSubscription(source.id, profiles)
        } else {
            configs.mergeSubscription(source.id, profiles)
        }

        SubscriptionRepository(appContext).update(source.id) {
            if (it.kind == SubscriptionKind.URL) {
                it.url = result.finalUrl
                it.etag = result.etag ?: ""
                it.lastModified = result.lastModified ?: ""
            }
            it.lastSuccessAt = System.currentTimeMillis()
            it.lastError = result.warnings.take(3).joinToString(" • ")
        }

        return RefreshResult(
            profiles = profiles.size,
            partial = !result.complete
        )
    }

    private fun userMessage(throwable: Throwable): String =
        (
            throwable.cause?.message
                ?: throwable.message
                ?: throwable.javaClass.simpleName
        )
            .replace(Regex("\\s+"), " ")
            .take(280)

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            activeTask?.cancel(true)
            activeTask = null
            worker.shutdownNow()
        }
    }
}
