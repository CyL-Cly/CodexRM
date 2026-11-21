package com.codexrm.mobile

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderModelDraftTest {
    @Test fun changesPreviewImmediatelyAndUndoCancelsTheWrite() {
        val original = JSONArray("""[{"model":"one"},{"model":"two"},{"model":"three","blacklisted":true}]""")
        val draft = ProviderModelDraft("provider", original)
        draft.setBlacklisted("one", true)
        assertEquals(listOf("two" to false, "one" to true, "three" to true), draft.rows())
        assertFalse(original.getJSONObject(0).optBoolean("blacklisted"))
        draft.setBlacklisted("one", false)
        assertFalse(draft.hasChanges)
        assertEquals(0, draft.changes().length())
        assertEquals(listOf("one" to false, "two" to false, "three" to true), draft.rows())
    }

    @Test fun stagesAllChangesTogetherAcrossAnUpstreamRefresh() {
        val draft = ProviderModelDraft("provider", JSONArray("""[{"model":"one"},{"model":"two","blacklisted":true}]"""))
        draft.setBlacklisted("one", true)
        draft.setBlacklisted("two", false)
        draft.add("manual")
        draft.setBlacklisted("manual", true)
        draft.replaceModels(JSONArray("""[{"model":"two","blacklisted":true},{"model":"new-upstream"}]"""))
        assertTrue(draft.hasChanges)
        assertEquals(listOf("two" to false, "new-upstream" to false, "one" to true, "manual" to true), draft.rows())
        val changes = draft.changes()
        assertEquals(listOf("manual" to "add", "one" to "hide", "two" to "restore", "manual" to "hide"),
            (0 until changes.length()).map { changes.getJSONObject(it).run { string("model") to string("action") } })
    }

    private fun ProviderModelDraft.rows() = preview().map { it.string("model") to it.optBoolean("blacklisted") }
}
