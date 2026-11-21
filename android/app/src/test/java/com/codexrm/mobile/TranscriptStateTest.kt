package com.codexrm.mobile

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptStateTest {
    private fun turn(id: String, itemId: String, status: String = "completed", error: String = "") = JSONObject()
        .put("id", id)
        .put("status", status)
        .put("items", JSONArray().put(JSONObject().put("id", itemId).put("type", "agentMessage").put("text", "body-$itemId")))
        .apply { if (error.isNotEmpty()) put("error", JSONObject().put("message", error)) }

    @Test fun `paging uses full turn rendering including error and interrupted`() {
        val messages = TurnRenderer.messages(turn("turn-1", "item-1", "interrupted", "failed"), readOnly = false)
        assertEquals(listOf("item-1", "error-turn-1", "stopped-turn-1"), messages.map { it.id })
        assertEquals(MessageKind.ERROR, messages[1].kind)
        assertEquals("failed", messages[1].body)
    }

    @Test fun `omitted turn still displays a history marker`() {
        val omitted = JSONObject().put("id", "large").put("codexrmHistoryOmitted", true)
        val messages = TurnRenderer.messages(omitted, readOnly = true)
        assertEquals(listOf("omitted-large"), messages.map { it.id })
        assertEquals(MessageKind.STATUS, messages.single().kind)
    }

    @Test fun `older pages prepend chronologically without duplicates`() {
        val existing = TurnRenderer.messages(turn("turn-3", "item-3"), false)
        val olderPage = listOf(turn("turn-3", "item-3"), turn("turn-2", "item-2"), turn("turn-1", "item-1"))
        val chronological = olderPage.reversed().flatMap { TurnRenderer.messages(it, false) }
        val merged = TranscriptState.prepend(existing, chronological)
        assertEquals(listOf("item-1", "item-2", "item-3"), merged.messages.map { it.id })
        assertEquals(setOf("item-1", "item-2"), merged.historyIds)
    }

    @Test fun `snapshot replaces latest window while retaining separate history`() {
        val history = TurnRenderer.messages(turn("turn-1", "item-1", "interrupted"), false)
        val oldLatest = TurnRenderer.messages(turn("turn-2", "item-2"), false)
        val existing = history + oldLatest
        val newLatest = TurnRenderer.messages(turn("turn-2", "item-2", error = "new error"), false) +
            TurnRenderer.messages(turn("turn-3", "item-3"), false)
        val merged = TranscriptState.latest(existing, setOf("item-1", "stopped-turn-1"), newLatest, keepHistory = true)
        assertEquals(listOf("item-1", "stopped-turn-1", "item-2", "error-turn-2", "item-3"), merged.messages.map { it.id })
        assertEquals("new error", merged.messages.single { it.id == "error-turn-2" }.body)
        assertEquals(setOf("item-1", "stopped-turn-1"), merged.historyIds)
    }

    @Test fun `id-less items keep a stable identity across overlapping page reparse`() {
        fun anonymousTurn() = JSONObject().put("id", "turn-1").put("status", "completed")
            .put("items", JSONArray().put(JSONObject().put("type", "agentMessage").put("text", "same")))
        val first = TurnRenderer.messages(anonymousTurn(), false)
        val second = TurnRenderer.messages(JSONObject(anonymousTurn().toString()), false)
        assertEquals(first.map { it.id }, second.map { it.id })
        val merged = TranscriptState.prepend(first, second)
        assertEquals(1, merged.messages.count { it.kind == MessageKind.ASSISTANT })
    }
}
