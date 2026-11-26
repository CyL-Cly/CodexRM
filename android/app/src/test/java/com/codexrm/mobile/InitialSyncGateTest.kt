package com.codexrm.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InitialSyncGateTest {
    private var timer: Runnable? = null
    private var delayMs = 0L
    private val gate = InitialSyncGate(
        postDelayed = { task, delay -> timer = task; delayMs = delay },
        remove = { task -> if (timer === task) timer = null },
    )

    @Test
    fun noLanCandidateDoesNotDelaySync() {
        var syncs = 0
        gate.await(canTryLan = false) { syncs++ }

        assertEquals(1, syncs)
        assertFalse(gate.waiting)
        assertEquals(0L, delayMs)
    }

    @Test
    fun confirmedTransportStartsSyncOnceBeforeTimeout() {
        val routes = mutableListOf<String>()
        var direct = false
        gate.await(canTryLan = true) { routes += if (direct) "direct" else "relay" }
        val timeout = requireNotNull(timer)
        assertTrue(gate.waiting)
        assertTrue(routes.isEmpty())

        direct = true
        gate.release()
        timeout.run()

        assertEquals(listOf("direct"), routes)
        assertFalse(gate.waiting)
    }

    @Test
    fun timeoutStartsSyncOverRelayWithoutRepeatingAfterLateDirect() {
        val routes = mutableListOf<String>()
        var direct = false
        gate.await(canTryLan = true) { routes += if (direct) "direct" else "relay" }
        gate.await(canTryLan = true) { error("Duplicate startup sync") }
        assertEquals(2_500L, delayMs)

        requireNotNull(timer).run()
        direct = true
        gate.release()

        assertEquals(listOf("relay"), routes)
        assertFalse(gate.waiting)
    }

    @Test
    fun cancelledWaitCannotStartSyncOnOldOrReplacementConnection() {
        gate.await(canTryLan = true) { error("Sync leaked across a disconnect") }
        val stale = requireNotNull(timer)
        gate.cancel()
        assertFalse(gate.waiting)

        var syncs = 0
        gate.await(canTryLan = true) { syncs++ }
        stale.run()
        assertTrue(gate.waiting)
        assertEquals(0, syncs)
        gate.release()
        assertEquals(1, syncs)
    }
}
