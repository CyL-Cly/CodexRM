package com.codexrm.mobile

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceRevocationTest {
    @Test fun successfulAndAlreadyRevokedRequestsAreDone() {
        assertEquals(RevocationOutcome.DONE, revocationOutcome(null) { false })
        assertEquals(RevocationOutcome.DONE, revocationOutcome(401) { true })
    }

    @Test fun onlyInterruptedOfflineTransportWaitsForAnotherNetwork() {
        assertEquals(RevocationOutcome.WAIT_FOR_NETWORK, revocationOutcome(0) { false })
        assertEquals(RevocationOutcome.FAILED, revocationOutcome(0) { true })
        assertEquals(RevocationOutcome.FAILED, revocationOutcome(500) { true })
        assertEquals(RevocationOutcome.FAILED, revocationOutcome(500) { false })
    }

    @Test fun pendingReportRetainsOnlyTheDeletedPairingEndpointAndToken() {
        val pending = PendingDeviceRevocation("https://relay.example", "old-pairing-token")
        val encoded = pending.toJson()
        assertEquals(setOf("serverUrl", "token"), encoded.keys().asSequence().toSet())
        assertEquals(pending, PendingDeviceRevocation.fromJson(JSONObject(encoded.toString())))
        assertNull(PendingDeviceRevocation.fromJson(encoded.apply { remove("token") }))
    }
}
