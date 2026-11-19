package com.codexrm.mobile

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OutboxTest {
    private fun pending(id: String = "send-1") = PendingSend(
        id, "agent-a", "", "/work", " original ",
        listOf(JSONObject().put("draftId", "file-1").put("name", "a.txt")),
        "agent-a:workspace:/work", false, "", 10,
    )

    @Test fun `json round trip retains submitted draft and attachment identity`() {
        val restored = PendingSend.fromJson(pending().toJson())!!
        assertEquals(" original ", restored.submittedText)
        assertEquals("agent-a:workspace:/work", restored.attachmentKey)
        assertEquals("file-1", restored.files.single().string("draftId"))
    }

    @Test fun `recovering an unconfirmed continuation preserves the real composer draft`() {
        val action = pending().copy(continuation = true)
        val recovered = OutboxState(listOf(PendingSend.fromJson(action.toJson())!!))
        assertEquals(true, recovered.values().single().continuation)
        assertEquals("new draft", OutboxRecovery.draft("new draft", recovered.values()))
    }

    @Test fun `only explicit confirmation or definite failure discards outbox`() {
        val state = OutboxState()
        state.begin(pending())
        state.failed("send-1", outcomeUnknown = true)
        assertEquals(PendingSendStage.UNCONFIRMED, state.get("send-1")?.stage)
        state.failed("send-1", outcomeUnknown = false)
        assertNull(state.get("send-1"))

        state.begin(pending())
        state.confirmed("send-1")
        assertNull(state.get("send-1"))
    }

    @Test fun `thread acknowledgement moves binding but not final confirmation`() {
        val state = OutboxState(); state.begin(pending())
        val moved = state.threadStarted("send-1", "thread-1", "/work", "agent-a:thread:thread-1")!!
        assertEquals("thread-1", moved.threadId)
        assertEquals(" original ", moved.submittedText)
        assertEquals(1, state.values().size)
        state.confirmed(moved.id)
        assertEquals(0, state.values().size)
    }

    @Test fun `process recovery is unconfirmed and preserves later edits`() {
        val recovered = OutboxState(listOf(pending()))
        assertEquals(PendingSendStage.UNCONFIRMED, recovered.values().single().stage)
        assertEquals(" original \n\nnew edit", OutboxRecovery.draft("new edit", recovered.values()))
        assertEquals(" original \nnew edit", OutboxRecovery.draft(" original \nnew edit", recovered.values()))
    }
}
