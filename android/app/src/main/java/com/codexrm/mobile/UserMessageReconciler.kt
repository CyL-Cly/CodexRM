package com.codexrm.mobile

import org.json.JSONArray

// RPC responses and item notifications can arrive in either order. Keep the local
// row until its real item is known, and match only messages from the same turn.
internal class UserMessageReconciler {
    private data class Pending(val id: String, var turnId: String, val content: List<List<String>>, val after: Long)
    private data class Echo(val turnId: String, val content: List<List<String>>, val sequence: Long, var localId: String? = null)

    private val pending = LinkedHashMap<String, Pending>()
    private val echoes = LinkedHashMap<String, Echo>()
    private val turns = HashSet<String>()
    private var sequence = 0L

    fun begin(id: String, content: JSONArray, turnId: String = "") {
        pending[id] = Pending(id, turnId, contentKey(content), sequence)
    }

    fun observeTurn(turnId: String) { turns.add(turnId) }

    fun started(turnId: String) {
        if (turns.add(turnId)) pending.values.firstOrNull { it.turnId.isEmpty() }?.turnId = turnId
    }

    // Returns an item that arrived before the RPC response, if any.
    fun acknowledge(id: String, turnId: String): String? {
        turns.add(turnId)
        val local = pending[id] ?: return null
        local.turnId = turnId
        val match = echoes.entries.firstOrNull {
            it.value.localId == null && it.value.turnId == turnId && it.value.content == local.content && it.value.sequence > local.after
        } ?: return null
        match.value.localId = id
        pending.remove(id)
        return match.key
    }

    // Returns the local row to replace. Repeated started/completed notifications
    // for an existing item must not consume a later send with identical content.
    fun accept(turnId: String, id: String, content: JSONArray): String? {
        turns.add(turnId)
        val key = contentKey(content)
        val echo = echoes[id]?.copy(turnId = turnId, content = key) ?: Echo(turnId, key, ++sequence)
        echoes[id] = echo
        if (echo.localId != null) return null
        val local = pending.values.firstOrNull {
            it.turnId == turnId && it.content == key && echo.sequence > it.after
        } ?: return null
        echo.localId = local.id
        pending.remove(local.id)
        return local.id
    }

    fun cancel(id: String) { pending.remove(id) }

    fun clear() {
        pending.clear(); echoes.clear(); turns.clear(); sequence = 0
    }

    private fun contentKey(content: JSONArray): List<List<String>> = (0 until content.length()).map { index ->
        val value = content.optJSONObject(index)
        listOf("type", "text", "path", "url", "image_url", "imageUrl").map { value?.string(it).orEmpty() }
    }
}

// Preserve the local row's position even if an early notification already added
// the real item elsewhere in the list.
internal fun MutableList<ChatMessage>.replaceMessage(id: String, message: ChatMessage) {
    if (id != message.id && any { it.id == id }) removeAll { it.id == message.id }
    val index = indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: indexOfFirst { it.id == message.id }
    message.dirty = true
    if (index >= 0) this[index] = message else add(message)
}
