package com.codexrm.mobile

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelCatalogTest {
    private val fixture = """
        {"providers":[
          {"id":"", "name":"当前 Codex 配置", "aliasOf":"ccswitch_a", "models":[
            {"model":"shared", "displayName":"Shared", "defaultReasoningEffort":"high"}, {"model":"native-only"}]},
          {"id":"ccswitch_a", "runtimeId":"ccswitch_a", "name":"Sub2API", "notes":"日常使用", "models":[
            {"model":"shared"}, {"model":"cc-only"}]},
          {"id":"ccswitch_b", "runtimeId":"ccswitch_b", "name":"Sub2API", "notes":"备用账号", "models":[
            {"model":"shared"}, {"model":"hidden", "hidden":true}]}
        ]}
    """.trimIndent()

    @Test fun `matching current configuration is listed once using CC Switch title and notes`() {
        val options = ModelCatalog(JSONObject(fixture)).options("")
        assertEquals(listOf("shared", "native-only", "cc-only", "shared"), options.map { it.model })
        assertEquals("Sub2API · Shared", options.first().label)
        assertEquals("high", options.first().defaultEffort)
        assertEquals(listOf("日常使用", "日常使用", "日常使用", "备用账号"), options.map { it.providerNotes })
        assertEquals(listOf("", "", "ccswitch_a", "ccswitch_b"), options.map { it.providerId })
    }

    @Test fun `existing CC Switch preference keeps its original provider`() {
        val catalog = ModelCatalog(JSONObject(fixture))
        val options = catalog.options(catalog.providerId("ccswitch_a"))
        assertEquals(listOf("ccswitch_a", "ccswitch_b"), options.filter { it.model == "shared" }.map { it.providerId })
    }

    @Test fun `different model names can switch between third-party endpoints`() {
        val catalog = ModelCatalog(JSONObject(fixture))
        val thread = JSONObject().put("modelProvider", "ccswitch_a").put("model", "cc-only")
        val model = catalog.options("ccswitch_a").first { it.providerId == "ccswitch_b" }
        assertEquals("ccswitch_b", catalog.switchEndpoint(thread, model))
        thread.put("modelProvider", "openai")
        assertEquals(null, catalog.switchEndpoint(thread, model))
    }

    @Test fun `model available through current configuration keeps the saved endpoint`() {
        val catalog = ModelCatalog(JSONObject(fixture))
        val selected = catalog.selectedOption(catalog.options("ccswitch_a"), "ccswitch_a", "native-only")
        assertEquals("ccswitch_a", selected?.providerId)
        assertEquals("native-only", selected?.model)
    }

    @Test fun `without confirmed match current configuration stays selectable`() {
        val json = JSONObject(fixture)
        json.getJSONArray("providers").getJSONObject(0).remove("aliasOf")
        val options = ModelCatalog(json).options("")
        assertEquals(listOf("", "ccswitch_a", "ccswitch_b"), options.filter { it.model == "shared" }.map { it.providerId })
        assertEquals("当前 Codex 配置 · Shared", options.first().label)
    }
}
