package com.codexrm.mobile

import org.json.JSONArray
import org.json.JSONObject

internal enum class PendingSendStage { WAITING, UNCONFIRMED }

/** A durable copy of a submission. It is removed only by an explicit RPC acknowledgement. */
internal data class PendingSend(
    val id: String,
    val agentId: String,
    val threadId: String,
    val cwd: String,
    val submittedText: String,
    val files: List<JSONObject>,
    val attachmentKey: String,
    val steer: Boolean,
    val expectedTurnId: String,
    val createdAt: Long,
    val stage: PendingSendStage = PendingSendStage.WAITING,
    val continuation: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("agentId", agentId)
        .put("threadId", threadId)
        .put("cwd", cwd)
        .put("submittedText", submittedText)
        .put("files", JSONArray(files))
        .put("attachmentKey", attachmentKey)
        .put("steer", steer)
        .put("expectedTurnId", expectedTurnId)
        .put("createdAt", createdAt)
        .put("stage", stage.name)
        .put("continuation", continuation)

    companion object {
        fun fromJson(value: JSONObject): PendingSend? {
            val id = value.string("id")
            val agentId = value.string("agentId")
            if (id.isEmpty() || agentId.isEmpty()) return null
            val files = value.arrayAt("files")
            return PendingSend(
                id = id,
                agentId = agentId,
                threadId = value.string("threadId"),
                cwd = value.string("cwd"),
                submittedText = value.string("submittedText"),
                files = (0 until files.length()).mapNotNull { files.optJSONObject(it) },
                attachmentKey = value.string("attachmentKey"),
                steer = value.optBoolean("steer"),
                expectedTurnId = value.string("expectedTurnId"),
                createdAt = value.optLong("createdAt"),
                continuation = value.optBoolean("continuation"),
                stage = runCatching { PendingSendStage.valueOf(value.string("stage")) }
                    .getOrDefault(PendingSendStage.UNCONFIRMED),
            )
        }
    }
}

internal class OutboxState(initial: List<PendingSend> = emptyList()) {
    private val entries = LinkedHashMap<String, PendingSend>()

    init {
        initial.forEach { entries[it.id] = it.copy(stage = PendingSendStage.UNCONFIRMED) }
    }

    fun values(): List<PendingSend> = entries.values.toList()
    fun get(id: String): PendingSend? = entries[id]

    /** Roll back an in-memory mutation when its durable write failed. */
    fun restore(values: List<PendingSend>) {
        entries.clear()
        values.forEach { entries[it.id] = it }
    }

    fun begin(value: PendingSend) {
        entries[value.id] = value.copy(stage = PendingSendStage.WAITING)
    }

    fun update(value: PendingSend) {
        if (entries.containsKey(value.id)) entries[value.id] = value
    }

    fun threadStarted(id: String, threadId: String, cwd: String, attachmentKey: String): PendingSend? {
        val current = entries[id] ?: return null
        val updated = current.copy(
            threadId = threadId,
            cwd = cwd,
            attachmentKey = attachmentKey,
            stage = PendingSendStage.WAITING,
        )
        entries[id] = updated
        return updated
    }

    /** A transport outcome does not prove that the server rejected the submission. */
    fun failed(id: String, outcomeUnknown: Boolean) {
        val current = entries[id] ?: return
        if (outcomeUnknown) entries[id] = current.copy(stage = PendingSendStage.UNCONFIRMED)
        else entries.remove(id)
    }

    fun confirmed(id: String) {
        entries.remove(id)
    }

    fun matching(binding: ComposerBinding, pathKey: (String) -> String): List<PendingSend> =
        entries.values.filter {
            it.agentId == binding.agentId && it.threadId == binding.threadId &&
                (binding.threadId.isNotEmpty() || pathKey(it.cwd) == pathKey(binding.cwd))
        }
}

internal object OutboxRecovery {
    /** Restore the submission without duplicating it when edits were already saved together. */
    fun draft(savedDraft: String, pending: List<PendingSend>): String =
        pending.filterNot { it.continuation }.sortedBy { it.createdAt }.fold(savedDraft) { current, value ->
            ComposerText.afterFailedSend(current, value.submittedText)
        }
}
