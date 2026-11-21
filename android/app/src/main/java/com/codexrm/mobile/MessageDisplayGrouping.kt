package com.codexrm.mobile

/** A consecutive run of tool messages represented by one collapsible transcript row. */
internal data class ToolOperationGroup(val messages: List<ChatMessage>) {
    init {
        require(messages.size >= 2)
        require(messages.all { it.kind == MessageKind.TOOL })
    }

    val latest: ChatMessage get() = messages.last()
    fun contains(id: String): Boolean = messages.any { it.id == id }
    fun isExpanded(expandedMessageIds: Set<String>): Boolean = messages.any { it.id in expandedMessageIds }
}

internal data class MessageDisplayRow(
    val message: ChatMessage,
    val group: ToolOperationGroup? = null,
    val groupHeader: Boolean = false,
    val groupExpanded: Boolean = false,
) {
    val groupContent: Boolean get() = group != null && !groupHeader
}

/** Builds ListView rows without changing the adapter's original message collection. */
internal object MessageDisplayGrouping {
    fun rows(messages: List<ChatMessage>, expandedMessageIds: Set<String>): List<MessageDisplayRow> {
        val result = ArrayList<MessageDisplayRow>(messages.size)
        var index = 0
        while (index < messages.size) {
            val message = messages[index]
            if (message.kind != MessageKind.TOOL) {
                result += MessageDisplayRow(message)
                index++
                continue
            }
            var end = index + 1
            while (end < messages.size && messages[end].kind == MessageKind.TOOL) end++
            if (end == index + 1) {
                result += MessageDisplayRow(message)
            } else {
                val group = ToolOperationGroup(messages.subList(index, end).toList())
                val open = group.isExpanded(expandedMessageIds)
                result += MessageDisplayRow(group.latest, group, groupHeader = true, groupExpanded = open)
                if (open) group.messages.forEach {
                    result += MessageDisplayRow(it, group, groupExpanded = true)
                }
            }
            index = end
        }
        return result
    }

    fun groupContaining(messages: List<ChatMessage>, id: String): ToolOperationGroup? {
        var index = 0
        while (index < messages.size) {
            if (messages[index].kind != MessageKind.TOOL) {
                index++
                continue
            }
            var end = index + 1
            while (end < messages.size && messages[end].kind == MessageKind.TOOL) end++
            if (end - index >= 2) {
                val group = ToolOperationGroup(messages.subList(index, end).toList())
                if (group.contains(id)) return group
            }
            index = end
        }
        return null
    }

    /** Prefer the expanded content row over its duplicate-id group header. */
    fun positionOf(rows: List<MessageDisplayRow>, id: String, groupHeader: Boolean? = null): Int {
        if (groupHeader != null) {
            return rows.indexOfFirst { it.message.id == id && it.groupHeader == groupHeader }
        }
        val content = rows.indexOfFirst { it.message.id == id && !it.groupHeader }
        return if (content >= 0) content else rows.indexOfFirst { it.message.id == id }
    }
}
