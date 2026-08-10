package com.trivox.client.service

// TRIVOX_P1_HANDOVER_COORDINATOR

/**
 * Small, dependency-free state coordinator for Android default-network handover.
 *
 * Android may report:
 *   onAvailable(new) -> onLost(old)
 * or:
 *   onLost(old) -> onAvailable(new)
 *
 * The VPN service must behave correctly in both orders. This class deliberately
 * contains no Android type so the race policy is fully unit-testable on the JVM.
 */
internal class NetworkHandoverCoordinator {
    data class Decision(
        val markReconnecting: Boolean = false,
        val reconnect: Boolean = false,
        val reason: String = ""
    )

    private val lock = Any()
    private var initialized = false
    private var currentNetworkHandle: Long? = null

    fun onAvailable(networkHandle: Long): Decision =
        synchronized(lock) {
            val previous = currentNetworkHandle
            currentNetworkHandle = networkHandle

            if (!initialized) {
                initialized = true
                return@synchronized Decision(
                    reason = "initial_default_network"
                )
            }

            if (previous == networkHandle) {
                return@synchronized Decision(
                    reason = "duplicate_default_network"
                )
            }

            Decision(
                markReconnecting = true,
                reconnect = true,
                reason = if (previous == null) {
                    "default_network_recovered"
                } else {
                    "default_network_changed"
                }
            )
        }

    fun onLost(networkHandle: Long): Decision =
        synchronized(lock) {
            if (!initialized) {
                return@synchronized Decision(
                    reason = "loss_before_initial_network"
                )
            }

            if (currentNetworkHandle != networkHandle) {
                return@synchronized Decision(
                    reason = "stale_network_lost"
                )
            }

            currentNetworkHandle = null
            Decision(
                markReconnecting = true,
                reconnect = false,
                reason = "default_network_lost"
            )
        }

    fun reset() {
        synchronized(lock) {
            initialized = false
            currentNetworkHandle = null
        }
    }
}
