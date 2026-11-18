package com.codexrm.mobile

import org.json.JSONObject
import org.junit.Assert.assertEquals
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
}
