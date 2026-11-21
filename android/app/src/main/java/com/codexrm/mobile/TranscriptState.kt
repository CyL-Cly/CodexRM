package com.codexrm.mobile

import org.json.JSONObject

internal data class TurnProgress(val id: String, val status: String) {
    val canContinue: Boolean get() = id.isNotEmpty() && (status == "failed" || status == "interrupted")

    companion object {
        fun from(turn: JSONObject) = TurnProgress(turn.string("id"), turn.string("status"))
    }
}

internal object TurnRenderer {
    fun messages(turn: JSONObject, readOnly: Boolean): List<ChatMessage> {
        val result = ArrayList<ChatMessage>()
        val items = turn.arrayAt("items")
        val turnId = turn.string("id")
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            // Overlapping pages must recognize the same id-less item even when the
            // server resends it in a freshly parsed JSONObject.
            val stableId = "$turnId:item:$index"
            if (item.string("id").isEmpty()) item.put("id", stableId)
            result += ProtocolFormatter.fromItem(item, stableId)
        }
        result += terminalMessages(turn, readOnly)
        return result
    }

    fun terminalMessages(turn: JSONObject, readOnly: Boolean): List<ChatMessage> {
        val result = ArrayList<ChatMessage>()
        val turnId = turn.string("id")
        if (turn.optBoolean("codexrmHistoryOmitted")) {
            result += ChatMessage("omitted-$turnId", MessageKind.STATUS, "", "这一轮内容过大，请在 Codex 桌面端查看完整内容。")
        }
        turn.optJSONObject("error")?.let {
            result += ChatMessage("error-$turnId", MessageKind.ERROR, "错误", it.string("message"))
        }
        if (turn.string("status") == "interrupted") {
            val text = if (readOnly) "原客户端状态：等待同步或任务已停止" else "任务已停止"
            result += ChatMessage("stopped-$turnId", MessageKind.STATUS, "", text)
        }
        return result
    }
}

internal data class TranscriptMerge(
    val messages: List<ChatMessage>,
    val historyIds: Set<String>,
)

internal object TranscriptState {
    /** Pages arrive newest-first; callers reverse turns before conversion. */
    fun prepend(existing: List<ChatMessage>, older: List<ChatMessage>): TranscriptMerge {
        val existingIds = existing.mapTo(HashSet()) { it.id }
        val addedIds = HashSet<String>()
        val prefix = older.filter { it.id !in existingIds && addedIds.add(it.id) }
        return TranscriptMerge(prefix + existing, addedIds)
    }

    /** Replace only the live tail; loaded history pages remain a separate prefix. */
    fun latest(
        existing: List<ChatMessage>,
        historyIds: Set<String>,
        latest: List<ChatMessage>,
        keepHistory: Boolean,
    ): TranscriptMerge {
        val latestIds = latest.mapTo(HashSet()) { it.id }
        val prefix = if (keepHistory) existing.filter { it.id in historyIds && it.id !in latestIds } else emptyList()
        val seen = HashSet<String>()
        val tail = latest.filter { seen.add(it.id) }
        return TranscriptMerge(prefix + tail, prefix.mapTo(HashSet()) { it.id })
    }
}
