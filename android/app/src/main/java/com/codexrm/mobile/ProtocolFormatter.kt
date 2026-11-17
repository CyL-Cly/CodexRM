package com.codexrm.mobile

import org.json.JSONArray
import org.json.JSONObject

internal object ProtocolFormatter {
    fun thread(value: JSONObject): ThreadSummary {
        val preview = value.string("preview").lineSequence().firstOrNull().orEmpty()
        return ThreadSummary(
            id = value.string("id"),
            title = value.string("name").ifEmpty { preview.ifEmpty { "新会话" } }.take(80),
            cwd = value.string("cwd"),
            active = value.objectAt("status").string("type") == "active",
            updatedAt = value.optLong("updatedAt", value.optLong("createdAt")),
            createdAt = value.optLong("createdAt"),
        )
    }

    fun fromItem(item: JSONObject): ChatMessage {
        val id = item.string("id").ifEmpty { "item-${item.hashCode()}" }
        val type = item.string("type")
        return when (type) {
            "userMessage" -> ChatMessage(id, MessageKind.USER, "", userText(item))
            "agentMessage", "planMessage" -> ChatMessage(id, MessageKind.ASSISTANT, "Codex", item.string("text"))
            "reasoning" -> ChatMessage(id, MessageKind.TOOL, "思考过程", reasoningText(item).bounded())
            "commandExecution" -> ChatMessage(
                id, MessageKind.TOOL, "运行命令",
                listOf(item.string("command"), item.string("aggregatedOutput").bounded())
                    .filter { it.isNotEmpty() }.joinToString("\n\n"),
                commandStatus(item),
            )
            "fileChange" -> ChatMessage(id, MessageKind.TOOL, "文件变更", fileChanges(item.arrayAt("changes")), item.string("status"))
            "diff" -> ChatMessage(id, MessageKind.TOOL, "本轮变更", item.string("diff").bounded(), "Diff")
            "plan" -> ChatMessage(id, MessageKind.TOOL, "执行计划", planText(item))
            "mcpToolCall" -> ChatMessage(
                id, MessageKind.TOOL, "${item.string("server").ifEmpty { "MCP" }} / ${item.string("tool").ifEmpty { "工具" }}",
                (item.opt("result") ?: item.opt("arguments") ?: JSONObject()).toPrettyString().bounded(), item.string("status"),
            )
            "webSearch" -> ChatMessage(
                id, MessageKind.TOOL, "搜索 · ${item.string("query")}",
                item.opt("action").toPrettyString().bounded(), item.string("status"),
            )
            "contextCompaction" -> ChatMessage(id, MessageKind.STATUS, "", "上下文已压缩")
            else -> ChatMessage(id, MessageKind.TOOL, type.ifEmpty { "执行事件" }, item.toPrettyString().bounded())
        }
    }

    fun update(message: ChatMessage, item: JSONObject) {
        val formatted = fromItem(item)
        message.change(formatted.title, formatted.body, formatted.status)
    }

    fun userText(item: JSONObject): String {
        val content = item.arrayAt("content")
        val parts = ArrayList<String>(content.length())
        for (index in 0 until content.length()) {
            val value = content.optJSONObject(index)
            val text = value?.string("text").orEmpty()
            if (text.isNotEmpty()) parts += text
            else if (value?.string("type") in setOf("image", "localImage")) parts += "[图片]"
        }
        return parts.joinToString("\n")
    }

    fun sandboxPolicy(mode: String): JSONObject = when (mode) {
        "read-only" -> JSONObject().put("type", "readOnly").put("networkAccess", false)
        "danger-full-access" -> JSONObject().put("type", "dangerFullAccess")
        else -> JSONObject()
            .put("type", "workspaceWrite")
            .put("writableRoots", JSONArray())
            .put("networkAccess", true)
            .put("excludeTmpdirEnvVar", false)
            .put("excludeSlashTmp", false)
    }

    fun input(text: String): JSONArray = JSONArray().put(
        JSONObject().put("type", "text").put("text", text).put("text_elements", JSONArray()),
    )

    fun requestTitle(message: JSONObject): String {
        val method = message.string("method")
        return when {
            method.contains("commandExecution") -> "允许执行这条命令？"
            method.contains("fileChange") -> "允许修改文件？"
            method.contains("requestUserInput") -> "Codex 需要你的回答"
            method.contains("permissions") -> "允许请求的权限？"
            else -> "Codex 需要你的操作"
        }
    }

    fun requestDetails(message: JSONObject): String {
        val params = message.objectAt("params")
        val parts = ArrayList<String>()
        listOf("reason", "command", "cwd").forEach { key ->
            params.string(key).takeIf { it.isNotEmpty() }?.let(parts::add)
        }
        params.optJSONObject("permissions")?.let { parts += it.toPrettyString().bounded() }
        if (parts.isEmpty() && !params.has("questions")) parts += params.toPrettyString().bounded(16_384)
        return parts.joinToString("\n\n")
    }

    fun permissionResult(message: JSONObject, decision: String): Any {
        if (message.string("method") == "item/permissions/requestApproval") {
            return JSONObject()
                .put("permissions", if (decision == "decline") JSONObject() else message.objectAt("params").objectAt("permissions"))
                .put("scope", if (decision == "acceptForSession") "session" else "turn")
        }
        return JSONObject().put("decision", decision)
    }

    private fun reasoningText(item: JSONObject): String {
        item.string("reasoningText").takeIf { it.isNotEmpty() }?.let { return it }
        val parts = ArrayList<String>()
        for (key in listOf("summary", "content")) {
            val array = item.arrayAt(key)
            for (index in 0 until array.length()) {
                when (val value = array.opt(index)) {
                    is String -> parts += value
                    is JSONObject -> parts += value.string("text")
                }
            }
        }
        return parts.filter { it.isNotEmpty() }.joinToString("\n")
    }

    private fun commandStatus(item: JSONObject): String = when (item.string("status")) {
        "completed" -> "退出码 ${if (item.has("exitCode")) item.optInt("exitCode") else 0}"
        "declined" -> "已拒绝"
        "" -> ""
        else -> "执行中"
    }

    private fun fileChanges(changes: JSONArray): String {
        val parts = ArrayList<String>(changes.length())
        for (index in 0 until changes.length()) {
            val change = changes.optJSONObject(index) ?: continue
            parts += "${change.string("path")}\n${change.string("diff")}".trimEnd().bounded()
        }
        return parts.joinToString("\n\n")
    }

    private fun planText(item: JSONObject): String {
        val plan = item.arrayAt("plan")
        if (plan.length() == 0) return item.string("text")
        val lines = ArrayList<String>(plan.length())
        for (index in 0 until plan.length()) {
            val step = plan.optJSONObject(index) ?: continue
            val mark = when (step.string("status")) { "completed" -> "✓"; "inProgress" -> "→"; else -> "○" }
            lines += "$mark ${step.string("step")}"
        }
        return lines.joinToString("\n")
    }
}

internal fun String.bounded(limit: Int = 65_536): String =
    if (length <= limit) this else "… 已省略 ${length - limit} 个字符 …\n" + substring(length - limit)

internal fun Any?.toPrettyString(): String = when (this) {
    null -> ""
    is JSONObject -> toString(2)
    is JSONArray -> toString(2)
    else -> toString()
}
