package com.codexrm.mobile

import org.json.JSONObject

internal class ModelCatalog(result: JSONObject = JSONObject()) {
    private class Provider(value: JSONObject) {
        val id = value.string("id")
        val runtimeId = value.string("runtimeId")
        val name = value.string("name")
        val notes = value.string("notes")
        val aliasOf = value.string("aliasOf")
        val switchableRuntimeId = value.string("switchableRuntimeId")
        val models = value.arrayAt("models")
    }

    private val providers = result.arrayAt("providers").let { array ->
        (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let(::Provider) }
    }

    fun providerId(runtimeId: String): String = providers.firstOrNull {
        runtimeId.isNotEmpty() && (it.id == runtimeId || it.runtimeId == runtimeId)
    }?.id ?: runtimeId.takeIf { it.startsWith("ccswitch_") }.orEmpty()

    fun threadProviderId(thread: JSONObject): String =
        thread.string("codexrmProvider").ifEmpty { providerId(thread.string("modelProvider")) }

    fun selectedOption(options: List<ModelOption>, providerId: String, model: String): ModelOption? {
        options.firstOrNull { it.providerId == providerId && it.model == model }?.let { return it }
        val activeAlias = providers.firstOrNull { it.id.isEmpty() }?.aliasOf?.takeIf(String::isNotEmpty) ?: return null
        val aliasId = when (providerId) {
            "" -> activeAlias
            activeAlias -> ""
            else -> return null
        }
        return options.firstOrNull { it.providerId == aliasId && it.model == model }?.copy(providerId = providerId)
    }

    fun sameProvider(left: String, right: String): Boolean {
        if (left == right) return true
        val alias = providers.firstOrNull { it.id.isEmpty() }?.aliasOf?.takeIf(String::isNotEmpty) ?: return false
        return (left.isEmpty() && right == alias) || (right.isEmpty() && left == alias)
    }

    fun switchEndpoint(thread: JSONObject, model: ModelOption): String? {
        val runtimeId = thread.string("modelProvider")
        val native = providers.firstOrNull { it.id.isEmpty() }
        if (thread.string("codexrmProvider").isEmpty() && !runtimeId.startsWith("ccswitch_") &&
            (runtimeId.isEmpty() || runtimeId != native?.switchableRuntimeId)) return null
        val targetId = model.providerId.ifEmpty { native?.aliasOf.orEmpty() }
        return providers.firstOrNull { it.id == targetId && it.runtimeId.startsWith("ccswitch_") }?.id
    }

    fun options(preferredProviderId: String): List<ModelOption> {
        val native = providers.firstOrNull { it.id.isEmpty() }
        val active = providers.firstOrNull { it.id.isNotEmpty() && it.id == native?.aliasOf }
        return providers.flatMap { provider ->
            when {
                active == null -> modelOptions(provider)
                provider === active -> emptyList()
                provider === native -> {
                    val nativeOptions = modelOptions(provider, active)
                    val activeOptions = modelOptions(active)
                    // Prefer the original provider ID for a saved selection or existing thread.
                    val preferred = if (preferredProviderId == active.id) activeOptions else nativeOptions
                    val other = if (preferredProviderId == active.id) nativeOptions else activeOptions
                    (preferred + other).distinctBy { it.model }
                }
                else -> modelOptions(provider)
            }
        }
    }

    private fun modelOptions(provider: Provider, title: Provider = provider): List<ModelOption> =
        (0 until provider.models.length()).mapNotNull { index ->
            val value = provider.models.optJSONObject(index) ?: return@mapNotNull null
            if (value.optBoolean("hidden")) return@mapNotNull null
            val model = value.string("model").ifEmpty { value.string("id") }
            if (model.isEmpty()) return@mapNotNull null
            val supported = value.arrayAt("supportedReasoningEfforts")
            val efforts = (0 until supported.length()).mapNotNull {
                supported.optJSONObject(it)?.string("reasoningEffort")?.takeIf(String::isNotEmpty)
            }
            // 请勿翻译模型和思考强度名
            val displayName = value.string("displayName").ifEmpty { model }
            ModelOption(
                provider.id, model,
                listOf(title.name, displayName).filter(String::isNotEmpty).joinToString(" · "),
                value.string("defaultReasoningEffort"), efforts, displayName, title.notes, title.name,
            )
        }
}
