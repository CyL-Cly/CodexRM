package com.codexrm.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DirectRetryTest {
    @Test
    fun establishedConnectionRetriesOneSecondAfterLoss() {
        val retry = DirectRetry()
        retry.requested(10_000)
        retry.started(10_100)
        retry.connected()

        retry.failed(33_000, wasConnected = true)

        assertEquals(1_000L, retry.delay(33_000))
        assertEquals(0L, retry.delay(34_000))
    }

    @Test
    fun earlyDisconnectWaitsForRelayCooldownAfterDelayedStart() {
        val retry = DirectRetry()
        retry.requested(10_000)
        retry.started(12_000)
        retry.connected()

        retry.failed(13_000, wasConnected = true)

        assertEquals(19_000L, retry.delay(13_000))
        assertEquals(0L, retry.delay(32_000))
    }

    @Test
    fun missingStartRetriesAfterRelayCooldown() {
        val retry = DirectRetry()
        retry.requested(10_000)

        retry.failed(18_000, wasConnected = false)

        assertEquals(13_000L, retry.delay(18_000))
        assertEquals(0L, retry.delay(31_000))
    }

    @Test
    fun repeatedNegotiationFailuresBackOffAndSuccessfulConnectionClearsBackoff() {
        val retry = DirectRetry()
        var now = 100_000L
        for (delay in listOf(4_000L, 8_000L, 16_000L, 30_000L, 30_000L)) {
            retry.requested(now)
            now += 21_000
            retry.failed(now, wasConnected = false)
            assertEquals(delay, retry.delay(now))
            now += delay
        }

        retry.connected()
        retry.failed(now, wasConnected = true)
        assertEquals(1_000L, retry.delay(now))
        retry.requested(now + 1_000)
        now += 22_000
        retry.failed(now, wasConnected = false)
        assertEquals(4_000L, retry.delay(now))
    }

    @Test
    fun incompatibleMappingsSuspendRetryUntilAConnectionChanges() {
        val retry = DirectRetry()
        retry.requested(10_000)
        retry.suspendForMapping()

        assertNull(retry.delay(600_000))
        retry.onAgentAvailable()
        assertEquals(0L, retry.delay(600_000))

        retry.suspendForMapping()
        retry.reset()
        assertEquals(0L, retry.delay(600_000))
    }

    @Test
    fun replacementWebSocketCanNegotiateImmediately() {
        val retry = DirectRetry()
        retry.requested(10_000)
        retry.failed(10_500, wasConnected = false)

        retry.reset()

        assertEquals(0L, retry.delay(10_500))
    }
}
