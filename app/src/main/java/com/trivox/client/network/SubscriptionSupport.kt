package com.trivox.client.network

import java.io.InterruptedIOException
import java.util.concurrent.CancellationException

class SubscriptionCancelledException(
    cause: Throwable? = null
) : InterruptedIOException(
    "Subscription update cancelled"
) {
    init {
        if (cause != null) {
            initCause(cause)
        }
    }
}

fun Throwable.isSubscriptionCancellation(): Boolean {
    if (Thread.currentThread().isInterrupted) {
        return true
    }

    var current: Throwable? = this
    val visited = HashSet<Throwable>()

    while (
        current != null &&
        visited.add(current)
    ) {
        if (
            current is SubscriptionCancelledException ||
            current is InterruptedException ||
            current is InterruptedIOException ||
            current is CancellationException
        ) {
            return true
        }

        current = current.cause
    }

    return false
}

fun throwSubscriptionCancelled(
    cause: Throwable? = null
): Nothing {
    Thread.currentThread().interrupt()
    throw SubscriptionCancelledException(cause)
}
