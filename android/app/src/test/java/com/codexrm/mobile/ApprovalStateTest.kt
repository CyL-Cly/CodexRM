package com.codexrm.mobile

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

    @Test fun `incomplete ownership stays answerable instead of hanging the task`() {
        // Codex may omit turnId, or a request may arrive before turn/started.
        // Hiding it entirely would leave the task waiting forever, which is
        // worse than showing it in the thread it belongs to.
        assertTrue(ApprovalFilter.visible(scope.copy(turnId = ""), "agent-a", "thread-a", "turn-a"))
        assertTrue(ApprovalFilter.visible(scope.copy(turnId = ""), "agent-a", "thread-a", ""))
        assertTrue(ApprovalFilter.visible(scope, "agent-a", "thread-a", ""))
        assertTrue(ApprovalFilter.visible(scope.copy(threadId = ""), "agent-a", "thread-a", "turn-a"))
    }

    @Test fun `batch cannot cross agents or threads`() {
        assertTrue(ApprovalFilter.canBatch(listOf(scope, scope.copy(turnId = "turn-b"))))
        assertFalse(ApprovalFilter.canBatch(listOf(scope, scope.copy(threadId = "thread-b"))))
        assertFalse(ApprovalFilter.canBatch(listOf(scope, scope.copy(agentId = "agent-b"))))
        assertFalse(ApprovalFilter.canBatch(listOf(scope, scope.copy(threadId = ""))))
        assertFalse(ApprovalFilter.canBatch(listOf(scope)))
    }
}
