package com.codexrm.mobile

import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test

class RpcOutcomeTest {
    @Test fun decodeUnknownOutcomeSeparatelyFromDefiniteRejection() {
        assertTrue(decodeCodexRpcError(JSONObject().put("code", -32098).put("message", "lost reply")) is RpcOutcomeUnknown)
        assertTrue(decodeCodexRpcError(JSONObject().put("code", -32602).put("message", "invalid model")) is CodexRpcError)
    }
}
