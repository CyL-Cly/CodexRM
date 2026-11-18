package com.codexrm.mobile

/** Identity of the draft currently displayed by the composer. */
internal data class ComposerBinding(
    val agentId: String,
    val threadId: String,
    val cwd: String,
) {
    fun attachmentKey(pathKey: (String) -> String): String = agentId + if (threadId.isNotEmpty()) {
        ":thread:$threadId"
    } else {
        ":workspace:${pathKey(cwd)}"
    }
}

/** A restore may apply only while neither the binding nor the user's edits have changed. */
internal data class ComposerRestoreToken(
    val binding: ComposerBinding,
    val editVersion: Long,
) {
    fun canApply(currentBinding: ComposerBinding, currentEditVersion: Long): Boolean =
        binding == currentBinding && editVersion == currentEditVersion
}

internal object ComposerText {
    /** Remove only the submitted prefix; never erase text appended while upload/cache work ran. */
    fun afterSuccessfulSend(current: String, submitted: String): String = when {
        submitted.isEmpty() -> current
        current == submitted -> ""
        current.startsWith(submitted) -> current.substring(submitted.length)
        else -> current
    }

    /** Keep both the failed submission and text entered while its request was running. */
    fun afterFailedSend(current: String, submitted: String): String = when {
        submitted.isEmpty() -> current
        current.isEmpty() -> submitted
        else -> "$submitted\n\n$current"
    }
}
