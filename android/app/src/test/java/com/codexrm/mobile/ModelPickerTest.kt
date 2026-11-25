package com.codexrm.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelPickerTest {
    private val options = listOf(
        ModelOption("openai", "gpt-5", "OpenAI · GPT-5", "high", listOf("low", "high"), "GPT-5", "主力", "OpenAI"),
        ModelOption("openai", "gpt-4.1", "OpenAI · GPT-4.1", "medium", listOf("medium"), "GPT-4.1", "主力", "OpenAI"),
        ModelOption("local", "qwen-coder", "Local · Qwen Coder", "", emptyList(), "Qwen Coder", "离线", "Local"),
    )

    @Test fun `models are grouped by provider without a default model entry`() {
        val rows = ModelPicker.rows(options, "")
        assertEquals("OpenAI", rows.first().title)
        assertTrue(rows.none { it.defaultChoice })
        assertEquals(listOf("OpenAI", "Local"), rows.filter { it.header }.map { it.title })
        assertEquals(listOf("gpt-5", "gpt-4.1", "qwen-coder"), rows.mapNotNull { it.option?.model })
    }

    @Test fun `search matches model provider and notes case insensitively`() {
        assertEquals(listOf("qwen-coder"), ModelPicker.rows(options, "QWEN").mapNotNull { it.option?.model })
        assertEquals(listOf("gpt-5", "gpt-4.1"), ModelPicker.rows(options, "openai").mapNotNull { it.option?.model })
        assertEquals(listOf("qwen-coder"), ModelPicker.rows(options, "离线").mapNotNull { it.option?.model })
        assertTrue(ModelPicker.rows(options, "not-found").none { !it.header && it.option != null })
    }

    @Test fun `empty search result offers a clear-search action`() {
        val rows = ModelPicker.rows(options, "not-found")
        assertEquals(1, rows.size)
        assertTrue(rows.single().emptyChoice)
        assertEquals("没有匹配的模型", rows.single().title)
        assertTrue(ModelPicker.rows(options, "").none { it.emptyChoice })
    }
}
