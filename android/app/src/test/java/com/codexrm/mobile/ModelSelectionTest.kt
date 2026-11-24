package com.codexrm.mobile

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelSelectionTest {
    private val catalog = ModelCatalog(JSONObject("""
        {"providers":[
          {"id":"", "name":"Codex", "aliasOf":"ccswitch_a", "models":[
            {"model":"first"}, {"model":"picked", "defaultReasoningEffort":"low"}]},
          {"id":"ccswitch_a", "runtimeId":"ccswitch_a", "name":"Daily", "models":[
            {"model":"first"}, {"model":"picked"}]},
          {"id":"ccswitch_b", "runtimeId":"ccswitch_b", "name":"Backup", "models":[
            {"model":"other"}]}
        ]}
    """.trimIndent()))

    @Test fun `explicit choice survives a thread whose stored model is the first one`() {
        val choice = ModelChoice("thread-a", "ccswitch_a", "picked", "low")
        assertEquals(
            Triple("ccswitch_a", "picked", "low"),
            ModelSelection.retain(catalog, choice, "thread-a", "ccswitch_a"),
        )
    }

    @Test fun `explicit choice stays on the new chat it was made for`() {
        val choice = ModelChoice("", "", "picked", "high")
        assertEquals(Triple("", "picked", "high"), ModelSelection.retain(catalog, choice, "", null))
    }

    @Test fun `opening another thread does not reuse the previous choice`() {
        val choice = ModelChoice("thread-a", "ccswitch_a", "picked", "low")
        assertNull(ModelSelection.retain(catalog, choice, "thread-b", "ccswitch_a"))
    }

    @Test fun `a different provider falls back instead of keeping the choice`() {
        val choice = ModelChoice("thread-a", "ccswitch_b", "other", "")
        assertNull(ModelSelection.retain(catalog, choice, "thread-a", "ccswitch_a"))
    }

    @Test fun `native alias of the active provider still matches`() {
        val choice = ModelChoice("thread-a", "", "picked", "")
        assertEquals(Triple("", "picked", ""), ModelSelection.retain(catalog, choice, "thread-a", "ccswitch_a"))
    }

    @Test fun `a removed model is not replaced with the first catalog entry`() {
        val choice = ModelChoice("", "ccswitch_a", "removed", "high")
        assertNull(ModelSelection.retain(catalog, choice, "", null))
    }
}
