package com.codexrm.mobile

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApprovalStateTest {
    private val scope = ApprovalScope("agent-a", "thread-a", "turn-a")

    @Test fun `approval is visible only to its own agent thread and turn`() {
        assertTrue(ApprovalFilter.visible(scope, "agent-a", "thread-a", "turn-a"))
        assertFalse(ApprovalFilter.visible(scope, "agent-b", "thread-a", "turn-a"))
        assertFalse(ApprovalFilter.visible(scope, "agent-a", "thread-b", "turn-a"))
        assertFalse(ApprovalFilter.visible(scope, "agent-a", "thread-a", "turn-b"))
    }

    @Test fun `new chat never exposes thread approvals`() {
        assertFalse(ApprovalFilter.visible(scope, "agent-a", "", "turn-a"))
        assertFalse(ApprovalFilter.visible(scope, "agent-a", "", ""))
        assertFalse(ApprovalFilter.visible(null, "agent-a", "thread-a", "turn-a"))
    }

    @Test fun `missing thread ownership is quarantined instead of leaking across threads`() {
        // A request without threadId must never be guessed into whichever chat is open.
        assertFalse(ApprovalFilter.visible(scope.copy(threadId = ""), "agent-a", "thread-a", "turn-a"))
        assertFalse(ApprovalFilter.visible(scope.copy(threadId = ""), "agent-a", "thread-b", "turn-a"))
        // Missing turnId is still answerable inside the exact owning thread.
        assertTrue(ApprovalFilter.visible(scope.copy(turnId = ""), "agent-a", "thread-a", "turn-a"))
        assertTrue(ApprovalFilter.visible(scope.copy(turnId = ""), "agent-a", "thread-a", ""))
        assertTrue(ApprovalFilter.visible(scope, "agent-a", "thread-a", ""))
    }

    @Test fun `command approval respects the server decision list`() {
        val request = JSONObject().put("method", "item/commandExecution/requestApproval")
        val params = JSONObject().put("availableDecisions", JSONArray()
            .put("accept").put(JSONObject().put("acceptWithExecpolicyAmendment", JSONObject())).put("cancel"))
        request.put("params", params)
        assertEquals(listOf("accept", "cancel"), ProtocolFormatter.permissionDecisions(request))
        params.put("availableDecisions", JSONArray().put("acceptForSession"))
        assertEquals(listOf("acceptForSession"), ProtocolFormatter.permissionDecisions(request))
        params.put("availableDecisions", JSONArray())
        assertTrue(ProtocolFormatter.permissionDecisions(request).isEmpty())
        params.remove("availableDecisions")
        assertEquals(listOf("accept", "acceptForSession", "decline"), ProtocolFormatter.permissionDecisions(request))
    }

    @Test fun `session permission reply grants only the requested permissions`() {
        val permissions = JSONObject().put("network", JSONObject().put("enabled", true))
        val request = JSONObject().put("method", "item/permissions/requestApproval")
            .put("params", JSONObject().put("permissions", permissions))
        assertTrue("acceptForSession" in ProtocolFormatter.permissionDecisions(request))
        val result = ProtocolFormatter.permissionResult(request, "acceptForSession") as JSONObject
        assertEquals("session", result.getString("scope"))
        assertEquals(permissions.toString(), result.getJSONObject("permissions").toString())
    }

    @Test fun `batch cannot cross agents or threads`() {
        assertTrue(ApprovalFilter.canBatch(listOf(scope, scope.copy(turnId = "turn-b"))))
        assertFalse(ApprovalFilter.canBatch(listOf(scope, scope.copy(threadId = "thread-b"))))
        assertFalse(ApprovalFilter.canBatch(listOf(scope, scope.copy(agentId = "agent-b"))))
        assertFalse(ApprovalFilter.canBatch(listOf(scope, scope.copy(threadId = ""))))
        assertFalse(ApprovalFilter.canBatch(listOf(scope)))
    }
}
