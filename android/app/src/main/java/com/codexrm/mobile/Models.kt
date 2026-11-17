package com.codexrm.mobile

import org.json.JSONObject

internal data class Device(
    val clientId: String,
    val token: String,
    val serverUrl: String,
    val agentId: String,
    var serverName: String,
    var os: String = "",
    var online: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("clientId", clientId)
        .put("token", token)
        .put("serverUrl", serverUrl)
        .put("agentId", agentId)
        .put("serverName", serverName)
        .put("os", os)

    companion object {
        fun fromJson(value: JSONObject): Device = Device(
            clientId = value.string("clientId"),
            token = value.string("token"),
            serverUrl = value.string("serverUrl"),
            agentId = value.string("agentId"),
            serverName = value.string("serverName").ifEmpty { "CodexRM" },
            os = value.string("os"),
        )

        fun fromClaim(serverUrl: String, value: JSONObject): Device {
            val agent = value.objectAt("agent")
            return Device(
                clientId = value.string("clientId"),
                token = value.string("token"),
                serverUrl = serverUrl,
                agentId = agent.string("agentId"),
                serverName = agent.string("serverName").ifEmpty { "CodexRM" },
                os = agent.string("os"),
                online = agent.optBoolean("online"),
            ).also {
                require(it.clientId.isNotEmpty() && it.token.isNotEmpty() && it.agentId.isNotEmpty()) {
                    "配对响应缺少设备凭据"
                }
            }
        }
    }
}

internal data class ThreadSummary(
    val id: String,
    val title: String,
    val cwd: String,
    val active: Boolean,
    val updatedAt: Long = 0,
    val createdAt: Long = 0,
)

internal data class Workspace(val name: String, val path: String)

internal data class ModelOption(
    val providerId: String,
    val model: String,
    val label: String,
    val defaultEffort: String,
    val efforts: List<String>,
)

internal enum class MessageKind { USER, ASSISTANT, TOOL, ERROR, STATUS }

internal data class ChatMessage(
    val id: String,
    val kind: MessageKind,
    var title: String,
    var body: String,
    var status: String = "",
) {
    @Volatile var rendered: CharSequence? = null
    @Volatile var dirty = true
    fun change(title: String = this.title, body: String = this.body, status: String = this.status) {
        if (this.title == title && this.body == body && this.status == status) return
        this.title = title
        this.body = body
        this.status = status
        rendered = null
        dirty = true
    }
}

internal fun JSONObject.string(key: String): String = if (isNull(key)) "" else optString(key, "")
internal fun JSONObject.objectAt(key: String): JSONObject = optJSONObject(key) ?: JSONObject()
internal fun JSONObject.arrayAt(key: String) = optJSONArray(key) ?: org.json.JSONArray()
