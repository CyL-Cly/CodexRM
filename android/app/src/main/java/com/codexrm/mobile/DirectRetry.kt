package com.codexrm.mobile

internal class DirectRetry {
    private var relayReadyAt = 0L
    private var retryAt = 0L
    private var failures = 0
    private var suspendedForMapping = false

    fun delay(now: Long): Long? {
        if (suspendedForMapping) return null
        return (maxOf(relayReadyAt, retryAt) - now).coerceAtLeast(0)
    }

    fun suspendForMapping() {
        suspendedForMapping = true
    }

    fun onAgentAvailable() {
        suspendedForMapping = false
    }

    fun onNetworkChanged() {
        suspendedForMapping = false
        failures = 0
        retryAt = 0
    }

    fun requested(now: Long) {
        // RM rate limits begin per WebSocket for the 20-second P2PAttempt window.
        // Allow a small margin when there is no start response to anchor the timer.
        relayReadyAt = now + RELAY_WINDOW_MS + 1_000
    }

    fun started(now: Long) {
        relayReadyAt = maxOf(relayReadyAt, now + RELAY_WINDOW_MS)
    }

    fun connected() {
        failures = 0
    }

    fun failed(now: Long, wasConnected: Boolean) {
        val delay = if (wasConnected) {
            1_000L
        } else {
            val backoff = (4_000L shl failures).coerceAtMost(30_000L)
            failures = (failures + 1).coerceAtMost(3)
            backoff
        }
        retryAt = now + delay
    }

    fun reset() {
        relayReadyAt = 0
        retryAt = 0
        failures = 0
        suspendedForMapping = false
    }

    private companion object {
        const val RELAY_WINDOW_MS = 20_000L
    }
}
