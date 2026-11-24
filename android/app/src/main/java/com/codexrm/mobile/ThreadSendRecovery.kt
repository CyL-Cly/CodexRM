package com.codexrm.mobile

internal fun desktopWriterConflict(error: Exception?): Boolean =
    error is CodexRpcError && error.message.orEmpty().contains("active writer", ignoreCase = true)

/** An unloaded thread also occurs after an Agent restart. Probe by resuming it before offering to close anything. */
internal fun threadNeedsResume(error: Exception?, threadId: String): Boolean {
    if (error !is CodexRpcError || threadId.isBlank()) return false
    val message = error.message.orEmpty().trim().trimEnd('.')
    return message.equals("thread not found", ignoreCase = true) ||
        message.equals("thread $threadId not found", ignoreCase = true) ||
        message.equals("thread not found: $threadId", ignoreCase = true) ||
        message.equals("no thread found for $threadId", ignoreCase = true) ||
        message.startsWith("thread is not loaded", ignoreCase = true) ||
        message.startsWith("thread $threadId is not loaded", ignoreCase = true)
}
