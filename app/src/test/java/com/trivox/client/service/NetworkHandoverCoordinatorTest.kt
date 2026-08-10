package com.trivox.client.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkHandoverCoordinatorTest {
    @Test
    fun initialNetworkDoesNotReconnect() {
        val coordinator = NetworkHandoverCoordinator()

        val initial = coordinator.onAvailable(10L)

        assertFalse(initial.markReconnecting)
        assertFalse(initial.reconnect)
    }

    @Test
    fun lostThenAvailableMarksAndReconnectsOnce() {
        val coordinator = NetworkHandoverCoordinator()
        coordinator.onAvailable(10L)

        val lost = coordinator.onLost(10L)
        val recovered = coordinator.onAvailable(11L)

        assertTrue(lost.markReconnecting)
        assertFalse(lost.reconnect)
        assertTrue(recovered.markReconnecting)
        assertTrue(recovered.reconnect)
    }

    @Test
    fun availableBeforeOldLostDoesNotReenterReconnect() {
        val coordinator = NetworkHandoverCoordinator()
        coordinator.onAvailable(10L)

        val changed = coordinator.onAvailable(11L)
        val staleLoss = coordinator.onLost(10L)

        assertTrue(changed.markReconnecting)
        assertTrue(changed.reconnect)
        assertFalse(staleLoss.markReconnecting)
        assertFalse(staleLoss.reconnect)
    }

    @Test
    fun duplicateAvailableIsIgnored() {
        val coordinator = NetworkHandoverCoordinator()
        coordinator.onAvailable(10L)

        val duplicate = coordinator.onAvailable(10L)

        assertFalse(duplicate.markReconnecting)
        assertFalse(duplicate.reconnect)
    }

    @Test
    fun resetMakesNextNetworkInitialAgain() {
        val coordinator = NetworkHandoverCoordinator()
        coordinator.onAvailable(10L)
        coordinator.reset()

        val afterReset = coordinator.onAvailable(20L)

        assertFalse(afterReset.markReconnecting)
        assertFalse(afterReset.reconnect)
    }
}
