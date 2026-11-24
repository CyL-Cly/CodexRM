package com.codexrm.mobile

internal data class ModelChoice(
    val threadId: String,
    val providerId: String,
    val model: String,
    val effort: String,
)

internal object ModelSelection {
    fun retain(
        catalog: ModelCatalog,
        choice: ModelChoice?,
        threadId: String,
        threadProviderId: String?,
    ): Triple<String, String, String>? {
        if (choice == null || choice.threadId != threadId || choice.model.isEmpty()) return null
        if (threadProviderId != null && !catalog.sameProvider(choice.providerId, threadProviderId)) return null
        val resolved = catalog.selectedOption(catalog.options(choice.providerId), choice.providerId, choice.model) ?: return null
        return Triple(resolved.providerId, resolved.model, choice.effort)
    }
}
