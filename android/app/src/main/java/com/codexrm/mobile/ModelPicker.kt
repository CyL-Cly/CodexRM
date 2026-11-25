package com.codexrm.mobile

import java.util.Locale

internal data class ModelPickerRow(
    val title: String,
    val notes: String = "",
    val option: ModelOption? = null,
    val header: Boolean = false,
    val defaultChoice: Boolean = false,
    val emptyChoice: Boolean = false,
)

internal object ModelPicker {
    fun rows(options: List<ModelOption>, query: String): List<ModelPickerRow> {
        val needle = query.trim().lowercase(Locale.ROOT)
        val result = ArrayList<ModelPickerRow>()
        val matches = options.filter { option ->
            needle.isEmpty() || listOf(
                option.model, option.displayName, option.label, option.providerName, option.providerNotes,
            ).any { it.lowercase(Locale.ROOT).contains(needle) }
        }
        val groups = LinkedHashMap<Pair<String, String>, MutableList<ModelOption>>()
        matches.forEach { option ->
            val name = option.providerName.ifEmpty { option.providerId.ifEmpty { "当前 Codex 配置" } }
            groups.getOrPut(option.providerId to name) { ArrayList() } += option
        }
        groups.forEach { (provider, values) ->
            result += ModelPickerRow(provider.second.ifEmpty { provider.first }, header = true)
            values.forEach { option ->
                result += ModelPickerRow(option.displayName, option.providerNotes, option)
            }
        }
        if (result.isEmpty()) result += ModelPickerRow(
            "没有匹配的模型",
            "点击清除搜索并查看全部模型",
            emptyChoice = true,
        )
        return result
    }
}
