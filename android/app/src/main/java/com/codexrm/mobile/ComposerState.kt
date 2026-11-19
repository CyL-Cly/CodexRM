package com.codexrm.mobile

import org.json.JSONArray
import org.json.JSONObject

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
        current == submitted || current.startsWith(submitted) -> current
        else -> "$submitted\n\n$current"
    }
}

/** Recover the text and uploaded attachments produced by the Android and web composers. */
internal data class QueuedComposerDraft(val text: String, val files: List<JSONObject>) {
    fun appendTo(existing: String): String = listOf(existing, text).filter(String::isNotEmpty).joinToString("\n\n")

    companion object {
        fun fromInput(input: JSONArray): QueuedComposerDraft? {
            val text = mutableListOf<String>()
            val files = mutableListOf<JSONObject>()
            val images = mutableListOf<String>()
            for (index in 0 until input.length()) {
                val item = input.optJSONObject(index) ?: return null
                when (item.string("type")) {
                    "text" -> {
                        if (item.arrayAt("text_elements").length() != 0) return null
                        val value = item.string("text")
                        val attachment = if ('\n' in value) null else MessageImages.attachment(value)
                        if (attachment == null) text.add(value)
                        else files.add(JSONObject().apply {
                            for (field in listOf("id", "name", "path", "size", "mimeType")) put(field, attachment.opt(field))
                        })
                    }
                    "localImage" -> images.add(item.string("path"))
                    else -> return null
                }
            }
            // A bare image without compatible attachment metadata cannot round-trip through the composer.
            val attachmentImages = files.filter { it.string("mimeType") in AttachmentStore.IMAGE_TYPES }.map { it.string("path") }
            if (images != attachmentImages || (text.all(String::isBlank) && files.isEmpty())) return null
            return QueuedComposerDraft(text.joinToString("\n"), files)
        }
    }
}
