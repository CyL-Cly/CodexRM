package com.codexrm.mobile

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UserMessageReconcilerTest {
    private val messages = UserMessageReconciler()

    private fun input(text: String): JSONArray = JSONArray().put(JSONObject().put("type", "text").put("text", text))
    private fun row(id: String, kind: MessageKind = MessageKind.USER) = ChatMessage(id, kind, "", "hello")

    @Test fun emptyStartResponseKeepsLocalRowUntilLateEchoReplacesIt() {
        val rows = arrayListOf(row("local"))
        messages.begin("local", input("hello"))
        assertNull(messages.acknowledge("local", "turn"))
        assertEquals(listOf("local"), rows.map { it.id })

        rows.add(row("assistant", MessageKind.ASSISTANT))
        val localId = messages.accept("turn", "real", input("hello"))
        assertEquals("local", localId)
        rows.replaceMessage(localId!!, row("real"))
        assertEquals(listOf("real", "assistant"), rows.map { it.id })
        assertNull(messages.accept("turn", "real", input("hello")))
    }

    @Test fun startedEventBeforeResponseReplacesLocalRowWithoutAddingAFallback() {
        messages.begin("local", input("hello"))
        messages.started("turn")
        assertEquals("local", messages.accept("turn", "real", input("hello")))
        assertNull(messages.acknowledge("local", "turn"))
        assertNull(messages.accept("turn", "real", input("hello")))
    }

    @Test fun itemBeforeStartedAndResponseIsReconciledWhenTurnIdBecomesKnown() {
        messages.begin("local", input("hello"))
        assertNull(messages.accept("turn", "real", input("hello")))
        messages.started("turn")
        assertEquals("real", messages.acknowledge("local", "turn"))
        val rows = arrayListOf(row("local"), row("assistant", MessageKind.ASSISTANT), row("real"))
        rows.replaceMessage("local", row("real"))
        assertEquals(listOf("real", "assistant"), rows.map { it.id })
    }

    @Test fun userItemInStartResponseAndLaterNotificationsRemainOneMessage() {
        messages.begin("local", input("hello"))
        messages.acknowledge("local", "turn")
        assertEquals("local", messages.accept("turn", "real", input("hello")))
        repeat(3) { assertNull(messages.accept("turn", "real", input("hello"))) }
    }

    @Test fun identicalTextInDifferentTurnsRemainsTwoMessages() {
        messages.begin("local-1", input("hello"))
        messages.acknowledge("local-1", "turn-1")
        assertEquals("local-1", messages.accept("turn-1", "real-1", input("hello")))
        messages.begin("local-2", input("hello"))
        messages.acknowledge("local-2", "turn-2")
        assertNull(messages.accept("turn-1", "real-1", input("hello")))
        assertEquals("local-2", messages.accept("turn-2", "real-2", input("hello")))
    }

    @Test fun unrelatedTurnCannotConsumePendingMessage() {
        messages.begin("local", input("hello"), "turn")
        assertNull(messages.accept("other", "other-real", input("hello")))
        assertEquals("local", messages.accept("turn", "real", input("hello")))
    }

    @Test fun repeatedIdenticalSteeringMessagesEachKeepTheirOwnEcho() {
        messages.begin("steer-1", input("hello"), "turn")
        messages.acknowledge("steer-1", "turn")
        messages.begin("steer-2", input("hello"), "turn")
        assertEquals("steer-1", messages.accept("turn", "real-1", input("hello")))
        assertNull(messages.acknowledge("steer-2", "turn"))
        assertNull(messages.accept("turn", "real-1", input("hello")))
        assertEquals("steer-2", messages.accept("turn", "real-2", input("hello")))
    }

    @Test fun earlierIdenticalHistoryCannotAcknowledgeANewSteer() {
        messages.accept("turn", "old", input("hello"))
        messages.begin("steer", input("hello"), "turn")
        assertNull(messages.acknowledge("steer", "turn"))
        assertNull(messages.accept("turn", "old", input("hello")))
        assertEquals("steer", messages.accept("turn", "new", input("hello")))
    }

    @Test fun reorderedSteeringEchoesMatchTheRightContent() {
        messages.begin("first", input("first"), "turn")
        messages.begin("second", input("second"), "turn")
        assertEquals("second", messages.accept("turn", "second-real", input("second")))
        assertEquals("first", messages.accept("turn", "first-real", input("first")))
    }

    @Test fun repeatedStartedEventForOldTurnDoesNotBindANewSend() {
        messages.observeTurn("old-turn")
        messages.begin("local", input("hello"))
        messages.started("old-turn")
        assertNull(messages.accept("old-turn", "old-real", input("hello")))
        messages.started("new-turn")
        assertEquals("local", messages.accept("new-turn", "new-real", input("hello")))
    }

    @Test fun completedItemCanFillInContentMissingFromStartedItem() {
        messages.begin("local", input("hello"), "turn")
        assertNull(messages.accept("turn", "real", JSONArray()))
        assertEquals("local", messages.accept("turn", "real", input("hello")))
        assertNull(messages.accept("turn", "real", input("hello")))
    }

    @Test fun attachmentOnlyMessagesMatchByImagePath() {
        fun image(path: String) = JSONArray().put(JSONObject().put("type", "localImage").put("path", path))
        messages.begin("first", image("/tmp/first.png"), "turn")
        messages.begin("second", image("/tmp/second.png"), "turn")
        assertEquals("second", messages.accept("turn", "second-real", image("/tmp/second.png")))
        assertEquals("first", messages.accept("turn", "first-real", image("/tmp/first.png")))
    }

    @Test fun textElementMetadataDoesNotPreventMatching() {
        messages.begin("local", input("hello").apply { getJSONObject(0).put("text_elements", JSONArray()) }, "turn")
        assertEquals("local", messages.accept("turn", "real", input("hello")))
    }

    @Test fun failedSendCannotConsumeAFutureEcho() {
        messages.begin("failed", input("hello"), "turn")
        messages.cancel("failed")
        assertNull(messages.accept("turn", "real", input("hello")))
    }

    @Test fun clearingConversationDropsPendingAndPreviouslySeenItems() {
        messages.begin("old-local", input("hello"), "old-turn")
        messages.accept("old-turn", "real", input("hello"))
        messages.clear()
        messages.begin("new-local", input("hello"), "new-turn")
        assertEquals("new-local", messages.accept("new-turn", "real", input("hello")))
        assertNull(messages.acknowledge("old-local", "old-turn"))
    }

    @Test fun replacingAnExistingItemMarksItDirtyWithoutAppendingIt() {
        val rows = arrayListOf(row("real"))
        val updated = row("real").apply { dirty = false }
        rows.replaceMessage("missing-local", updated)
        assertEquals(1, rows.size)
        assertEquals(true, rows.single().dirty)
    }
}
