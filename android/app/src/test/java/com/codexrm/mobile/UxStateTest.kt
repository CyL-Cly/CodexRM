package com.codexrm.mobile

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UxStateTest {
    private fun scope(agent: String = "agent", thread: String = "thread", turn: String = "turn") =
        ApprovalScope(agent, thread, turn)

    @Test fun newChatShowsNoThreadApprovals() {
        assertFalse(ApprovalFilter.visible(scope(), "agent", "", "turn"))
        assertFalse(ApprovalFilter.visible(scope(), "agent", "", ""))
    }

    @Test fun approvalsNeverLeakAcrossAgentsOrThreads() {
        assertFalse(ApprovalFilter.visible(scope(thread = "other"), "agent", "thread", "turn"))
        assertFalse(ApprovalFilter.visible(scope(agent = "other"), "agent", "thread", "turn"))
        assertTrue(ApprovalFilter.visible(scope(), "agent", "thread", "turn"))
    }

    @Test fun requestsWithoutTurnIdStayAnswerable() {
        assertTrue(ApprovalFilter.visible(scope(turn = ""), "agent", "thread", "turn"))
        assertTrue(ApprovalFilter.visible(scope(turn = ""), "agent", "thread", ""))
        assertTrue(ApprovalFilter.visible(scope(), "agent", "thread", ""))
        assertFalse(ApprovalFilter.visible(scope(turn = "stale"), "agent", "thread", "turn"))
    }

    @Test fun batchNeverCrossesAgentOrThread() {
        assertTrue(ApprovalFilter.canBatch(listOf(scope(), scope(turn = "turn2"))))
        assertFalse(ApprovalFilter.canBatch(listOf(scope(), scope(thread = "other"))))
        assertFalse(ApprovalFilter.canBatch(listOf(scope(), scope(agent = "other"))))
        assertFalse(ApprovalFilter.canBatch(listOf(scope(), scope(thread = ""))))
        assertFalse(ApprovalFilter.canBatch(listOf(scope())))
    }

    @Test fun outboxKeepsUnknownOutcomeAndClearsOnlyOnConfirmation() {
        val pending = PendingSend("id", "agent", "", "/cwd", "text", emptyList(), "key", false, "", 1L)
        val state = OutboxState()
        state.begin(pending)
        state.failed("id", outcomeUnknown = true)
        assertEquals(1, state.values().size)
        assertEquals(PendingSendStage.UNCONFIRMED, state.get("id")?.stage)
        state.confirmed("id")
        assertTrue(state.values().isEmpty())
    }

    @Test fun outboxReloadTreatsWaitingSendAsUnconfirmed() {
        val pending = PendingSend("id", "agent", "thread", "/cwd", "text", emptyList(), "key", false, "", 1L)
        val reloaded = OutboxState(listOf(pending))
        assertEquals(PendingSendStage.UNCONFIRMED, reloaded.get("id")?.stage)
    }

    @Test fun outboxBindsToNewThreadBeforeTurnConfirmation() {
        val pending = PendingSend("id", "agent", "", "/cwd", "text", emptyList(), "old-key", false, "", 1L)
        val state = OutboxState()
        state.begin(pending)
        val bound = state.threadStarted("id", "new-thread", "/project", "new-key")
        assertEquals("new-thread", bound?.threadId)
        assertEquals("new-key", bound?.attachmentKey)
        assertEquals(1, state.matching(ComposerBinding("agent", "new-thread", "/project")) { it }.size)
        assertTrue(state.matching(ComposerBinding("agent", "", "/project")) { it }.isEmpty())
    }

    @Test fun outboxRecoveryDoesNotDuplicateAlreadySavedDraft() {
        val pending = PendingSend("id", "agent", "", "/cwd", "text", emptyList(), "key", false, "", 1L)
        assertEquals("text", OutboxRecovery.draft("", listOf(pending)))
        assertEquals("text", OutboxRecovery.draft("text", listOf(pending)))
        assertEquals("text\n\nnew", OutboxRecovery.draft("new", listOf(pending)))
    }

    private fun option(providerId: String, model: String, name: String, notes: String = "") =
        ModelOption(providerId, model, "$name · $model", "", emptyList(), model, notes, name)

    @Test fun modelPickerGroupsByProviderAndSearches() {
        val options = listOf(
            option("p1", "alpha", "Provider One"),
            option("p1", "beta", "Provider One"),
            option("p2", "gamma-image", "Provider Two"),
        )
        val rows = ModelPicker.rows(options, "")
        assertTrue(rows.first().title == "默认模型")
        assertEquals(2, rows.count { it.header })
        assertTrue(rows.any { it.header && it.title == "Provider One" })

        val searched = ModelPicker.rows(options, "gamma")
        assertFalse(searched.any { it.option?.model == "alpha" })
        assertTrue(searched.any { it.option?.model == "gamma-image" })

        val providerSearch = ModelPicker.rows(options, "provider two")
        assertTrue(providerSearch.any { it.option?.model == "gamma-image" })
        assertFalse(providerSearch.any { it.option?.model == "alpha" })
    }

    @Test fun turnRendererIncludesErrorAndInterruptedInPagedHistory() {
        val turn = JSONObject()
            .put("id", "t1")
            .put("status", "interrupted")
            .put("error", JSONObject().put("message", "boom"))
            .put("items", org.json.JSONArray())
        val messages = TurnRenderer.messages(turn, readOnly = false)
        assertTrue(messages.any { it.kind == MessageKind.ERROR && it.body == "boom" })
        assertTrue(messages.any { it.kind == MessageKind.STATUS && it.body == "任务已停止" })
    }

    @Test fun transcriptKeepsLoadedHistoryAndMergesLatestUpdates() {
        fun msg(id: String) = ChatMessage(id, MessageKind.ASSISTANT, "Codex", id)
        val older = listOf(msg("old-1"), msg("old-2"))
        val prepended = TranscriptState.prepend(listOf(msg("live")), older)
        assertEquals(listOf("old-1", "old-2", "live"), prepended.messages.map { it.id })
        assertEquals(setOf("old-1", "old-2"), prepended.historyIds)

        val latest = listOf(msg("old-2"), msg("new-live"))
        val merged = TranscriptState.latest(prepended.messages, prepended.historyIds, latest, keepHistory = true)
        assertEquals(listOf("old-1", "old-2", "new-live"), merged.messages.map { it.id })
        assertEquals(setOf("old-1"), merged.historyIds)
    }
}
