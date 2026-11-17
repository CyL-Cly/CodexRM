package com.codexrm.mobile

import android.os.Handler
import android.os.Looper
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.IOException
import kotlin.math.min

internal enum class ConnectionState { CONNECTING, ONLINE, OFFLINE }
internal fun interface RpcCallback { fun onComplete(result: JSONObject?, error: Exception?) }

internal class CodexClient(
    private val device: Device,
    private val listener: Listener,
) {
    internal interface Listener {
        fun onState(state: ConnectionState, agent: JSONObject?)
        fun onRpcEvent(message: JSONObject)
        fun onFailure(message: String)
        fun onAuthorizationExpired()
    }

    private data class Pending(val callback: RpcCallback, val timeout: Runnable)
    private val main = Handler(Looper.getMainLooper())
    private val pending = HashMap<Int, Pending>()
    private var socket: WebSocket? = null
    private var active = false
    private var online = false
    private var generation = 0
    private var nextId = 0
    private var reconnectAttempt = 0
    private var reconnect: Runnable? = null

    fun start() {
        if (active) return
        active = true
        open()
    }

    private fun open() {
        if (!active) return
        rejectPending("连接已中断")
        online = false
        listener.onState(ConnectionState.CONNECTING, null)
        val currentGeneration = ++generation
        val url = device.serverUrl.trimEnd('/') + "/api/v1/client/ws"
        val request = Request.Builder()
            .url(url)
            // The relay accepts the second value as credentials and negotiates "codexrm".
            .header("Sec-WebSocket-Protocol", "codexrm, codexrm.token.${device.token}")
            .build()
        socket = Network.webSocketClient.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                main.post {
                    if (active && currentGeneration == generation) handle(text)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                main.post {
                    if (!active || currentGeneration != generation) return@post
                    if (response?.code == 401) {
                        active = false
                        online = false
                        rejectPending("配对已失效")
                        listener.onState(ConnectionState.OFFLINE, null)
                        listener.onAuthorizationExpired()
                    } else {
                        listener.onFailure(t.message ?: "WebSocket 连接失败")
                        scheduleReconnect()
                    }
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                main.post {
                    if (active && currentGeneration == generation) scheduleReconnect()
                }
            }
        })
    }

    private fun handle(text: String) {
        val envelope = try { JSONObject(text) } catch (_: Exception) { return }
        when (envelope.string("type")) {
            "welcome", "agent_status" -> {
                val wasOnline = online
                online = envelope.string("status") == "connected"
                if (online) reconnectAttempt = 0
                listener.onState(if (online) ConnectionState.ONLINE else ConnectionState.OFFLINE, envelope.optJSONObject("agent"))
                if (!online && wasOnline) rejectPending("远程设备已离线")
            }
            "server_message" -> {
                val message = envelope.optJSONObject("message") ?: return
                if (message.has("method")) {
                    listener.onRpcEvent(message)
                    return
                }
                val id = (message.opt("id") as? Number)?.toInt() ?: return
                val request = pending.remove(id) ?: return
                main.removeCallbacks(request.timeout)
                val error = message.optJSONObject("error")
                if (error == null) request.callback.onComplete(message.optJSONObject("result") ?: JSONObject(), null)
                else request.callback.onComplete(null, IOException(error.string("message").ifEmpty { "Codex 请求失败" }))
            }
            "error" -> {
                val id = envelope.string("streamId").toIntOrNull()
                val request = if (id == null) null else pending.remove(id)
                if (request == null) {
                    listener.onFailure(envelope.string("error").ifEmpty { "中继请求失败" })
                } else {
                    main.removeCallbacks(request.timeout)
                    request.callback.onComplete(null, IOException(envelope.string("error")))
                }
            }
        }
    }

    fun request(method: String, params: JSONObject = JSONObject(), callback: RpcCallback) {
        if (!active || !online || socket == null) {
            main.post { callback.onComplete(null, IOException("设备未连接，请确认本地 Agent 正在运行")) }
            return
        }
        nextId = if (nextId == Int.MAX_VALUE) 1 else nextId + 1
        val id = nextId
        val timeout = Runnable {
            pending.remove(id)?.callback?.onComplete(null, IOException("请求超时，请检查本地 Codex 是否正常运行"))
        }
        pending[id] = Pending(callback, timeout)
        val message = JSONObject().put("id", id).put("method", method).put("params", params)
        val envelope = JSONObject()
            .put("type", "client_message")
            .put("streamId", id.toString())
            .put("message", message)
        if (socket?.send(envelope.toString()) != true) {
            pending.remove(id)
            callback.onComplete(null, IOException("消息发送失败"))
            return
        }
        main.postDelayed(timeout, 120_000)
    }

    fun respond(id: Any, result: Any): Boolean {
        val message = JSONObject().put("id", id).put("result", result)
        return sendResponse(message)
    }

    fun respondError(id: Any, message: String): Boolean {
        val error = JSONObject().put("code", -32000).put("message", message)
        return sendResponse(JSONObject().put("id", id).put("error", error))
    }

    private fun sendResponse(message: JSONObject): Boolean {
        if (!active || !online) {
            listener.onFailure("连接已断开，操作未发送")
            return false
        }
        val envelope = JSONObject().put("type", "client_message").put("message", message)
        val sent = socket?.send(envelope.toString()) == true
        if (!sent) listener.onFailure("连接已断开，操作未发送")
        return sent
    }

    private fun scheduleReconnect() {
        if (!active) return
        online = false
        generation++
        socket?.cancel()
        socket = null
        rejectPending("服务器连接中断")
        listener.onState(ConnectionState.CONNECTING, null)
        reconnect?.let(main::removeCallbacks)
        val delay = min(30_000L, 1_000L * (1 shl min(5, reconnectAttempt)))
        reconnectAttempt++
        val task = Runnable { open() }
        reconnect = task
        main.postDelayed(task, delay)
    }

    private fun rejectPending(message: String) {
        val requests = pending.values.toList()
        pending.clear()
        requests.forEach {
            main.removeCallbacks(it.timeout)
            it.callback.onComplete(null, IOException(message))
        }
    }

    fun stop() {
        if (!active && socket == null) return
        active = false
        online = false
        generation++
        reconnect?.let(main::removeCallbacks)
        reconnect = null
        rejectPending("连接已关闭")
        socket?.close(1000, "Activity stopped")
        socket = null
    }
}
