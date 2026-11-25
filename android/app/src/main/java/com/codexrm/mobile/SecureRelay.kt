package com.codexrm.mobile

import android.os.Handler
import android.os.Looper
import android.util.Base64
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.Closeable
import java.io.IOException
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private val routeFormat = Regex("route_[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")
private val secretFormat = Regex("[A-Za-z0-9_-]{43}")
private val ready = "ready".toByteArray()

internal data class SecureInvitation(val route: String, val secret: String) {
    companion object {
        fun parse(code: String): SecureInvitation {
            val parts = code.split('.')
            require(parts.size == 3 && parts[0] == "rm2" && routeFormat.matches(parts[1]) && secretFormat.matches(parts[2])) {
                "请在电脑 Agent 上重新生成加密配对码"
            }
            val flags = Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
            val secret = Base64.decode(parts[2], flags)
            require(secret.size == 32 && Base64.encodeToString(secret, flags) == parts[2]) { "配对码格式无效" }
            return SecureInvitation(parts[1], Base64.encodeToString(secret, Base64.NO_WRAP))
        }
    }
}

// Access is serialized on the main thread, including WebSocket callbacks.
internal class SecureRelay(
    private val client: String,
    private val key: String,
    private val route: String = "",
    private val write: (String) -> Boolean,
    private val onReady: (String) -> Unit,
    private val onMessage: (JSONObject) -> Unit,
    private val onFailure: () -> Unit,
) : Closeable {
    private var handle = 0L
    private var negotiatedKey: String? = null
    private var authenticated = false
    private var closed = false

    fun receive(envelope: JSONObject) {
        if (closed) return
        try {
            when (envelope.string("type")) {
                "welcome", "agent_status" -> {
                    if (envelope.string("status") == "connected") {
                        if (handle == 0L) start()
                    } else if (handle != 0L) fail()
                }
                "secure" -> if (handle != 0L) receiveFrame(envelope.getJSONObject("secure"))
            }
        } catch (_: Exception) { fail() }
    }

    fun send(text: String): Boolean {
        if (!authenticated || closed) return false
        return try {
            sendFrame("data", call(2, text.toByteArray()))
            true
        } catch (_: Exception) { fail(); false }
    }

    private fun start() {
        check(NativeP2P.available)
        val config = JSONObject().put("client", client).put("route", route).put("key", key)
        handle = NativeP2P.secureOpen(config.toString().toByteArray())
        check(handle != 0L)
        sendFrame("hello", call(0, byteArrayOf()), route)
    }

    private fun receiveFrame(frame: JSONObject) {
        when (frame.string("step")) {
            "reply" -> if (negotiatedKey == null) {
                val result = call(1, payload(frame))
                negotiatedKey = if (route.isEmpty()) key else Base64.encodeToString(result, Base64.NO_WRAP)
                sendFrame("confirm", call(2, ready))
            }
            "confirm" -> if (negotiatedKey != null && !authenticated) {
                check(call(3, payload(frame)).contentEquals(ready))
                authenticated = true
                onReady(requireNotNull(negotiatedKey))
            }
            "data" -> if (authenticated) {
                val message = JSONObject(call(3, payload(frame)).decodeToString(throwOnInvalidSequence = true))
                check(message.string("type") == "server_message")
                onMessage(message)
            }
        }
    }

    private fun payload(frame: JSONObject) = Base64.decode(frame.getString("data"), Base64.NO_WRAP)

    private fun call(action: Int, bytes: ByteArray): ByteArray =
        NativeP2P.secureCall(handle, action, bytes) ?: throw IOException("Secure frame validation failed")

    private fun sendFrame(step: String, bytes: ByteArray, route: String = "") {
        val secure = JSONObject().put("step", step).put("data", Base64.encodeToString(bytes, Base64.NO_WRAP))
        if (route.isNotEmpty()) secure.put("route", route)
        if (!write(JSONObject().put("type", "secure").put("secure", secure).toString())) throw IOException("WebSocket closed")
    }

    private fun fail() {
        if (closed) return
        close()
        onFailure()
    }

    override fun close() {
        if (closed) return
        closed = true
        if (handle != 0L) NativeP2P.secureClose(handle)
        handle = 0
    }
}

internal fun signedDirectPin(device: Device, attempt: String, role: String, pin: String): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(Base64.decode(device.secureKey, Base64.NO_WRAP), "HmacSHA256"))
    return Base64.encodeToString(mac.doFinal("CodexRM direct pin v2\n${device.clientId}\n$attempt\n$role\n$pin".toByteArray()), Base64.NO_WRAP)
}

internal fun verifyDirectPin(device: Device, attempt: String, signal: JSONObject): Boolean = runCatching {
    val expected = signedDirectPin(device, attempt, "agent", signal.getString("pin"))
    MessageDigest.isEqual(expected.toByteArray(), signal.getString("pinMac").toByteArray())
}.getOrDefault(false)

internal fun relaySocketRequest(device: Device): Request = Request.Builder()
    .url(device.serverUrl.trimEnd('/') + "/api/v1/client/ws")
    .header("Sec-WebSocket-Protocol", "codexrm, codexrm.token.${device.token}")
    .build()

internal fun verifySecurePairing(device: Device, invitation: SecureInvitation, complete: (String?, String?) -> Unit): () -> Unit {
    val main = Handler(Looper.getMainLooper())
    var finished = false
    lateinit var socket: WebSocket
    lateinit var relay: SecureRelay
    lateinit var timeout: Runnable
    fun finish(key: String?, error: String?, cancelled: Boolean = false) {
        if (finished) return
        finished = true
        main.removeCallbacks(timeout)
        relay.close()
        socket.cancel()
        if (!cancelled) complete(key, error)
    }
    timeout = Runnable { finish(null, "无法验证电脑 Agent，请确认它在线并重新生成配对码") }
    relay = SecureRelay(device.clientId, invitation.secret, invitation.route,
        write = { socket.send(it) }, onReady = { finish(it, null) }, onMessage = {},
        onFailure = { finish(null, "电脑 Agent 的身份验证失败，请重新生成配对码") })
    socket = Network.webSocketClient.newWebSocket(relaySocketRequest(device), object : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            main.post { if (!finished) runCatching { relay.receive(JSONObject(text)) }.onFailure { finish(null, "配对连接无效") } }
        }
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            main.post { finish(null, "配对连接已断开，请重新生成配对码") }
        }
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            main.post { finish(null, "配对连接已断开，请重新生成配对码") }
        }
    })
    main.postDelayed(timeout, 15_000)
    return { finish(null, null, cancelled = true) }
}