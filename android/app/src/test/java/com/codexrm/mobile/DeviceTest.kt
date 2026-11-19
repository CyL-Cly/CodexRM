package com.codexrm.mobile

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceTest {
    @Test
    fun agentVersionSurvivesPersistenceAndClaim() {
        val device = Device("client", "token", "https://rm.example", "agent", "Computer", "windows", "0.6.13")
        assertEquals("0.6.13", Device.fromJson(device.toJson()).appVersion)

        val claim = JSONObject()
            .put("clientId", "client")
            .put("token", "token")
            .put("agent", JSONObject().put("agentId", "agent").put("serverName", "Computer").put("appVersion", "0.6.13"))
        assertEquals("0.6.13", Device.fromClaim("https://rm.example", claim).appVersion)
    }

    @Test
    fun pendingAgentUpdateSurvivesReconnectsAndConfirmsOnlyOnce() {
        val device = Device("client", "token", "https://rm.example", "agent", "Computer", appVersion = "0.6.13")
        assertFalse(device.confirmAgentUpdate("0.6.14"))

        device.pendingAgentUpdateFrom = device.appVersion
        // The relay may already have refreshed the displayed version before the info reply.
        device.appVersion = "0.6.14"
        val restored = Device.fromJson(device.toJson())
        assertFalse(restored.confirmAgentUpdate(""))
        assertFalse(restored.confirmAgentUpdate("0.6.13"))
        assertEquals("0.6.13", restored.pendingAgentUpdateFrom)
        assertTrue(restored.confirmAgentUpdate("0.6.14"))
        assertFalse(restored.confirmAgentUpdate("0.6.14"))
        assertFalse(Device.fromJson(restored.toJson()).confirmAgentUpdate("0.6.15"))
    }
}
