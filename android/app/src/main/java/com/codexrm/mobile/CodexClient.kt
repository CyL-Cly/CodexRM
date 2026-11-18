package com.codexrm.mobile

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import org.json.JSONArray
import java.io.IOException
import java.net.Inet4Address
import kotlin.math.min

internal enum class ConnectionState { CONNECTING, ONLINE, OFFLINE }
internal fun interface RpcCallback { fun onComplete(result: JSONObject?, error: Exception?) }

internal sealed interface DirectStatus {
    data class Connected(val lan: Boolean) : DirectStatus
    data object Negotiating : DirectStatus
    data class Relay(val reason: String) : DirectStatus
}

internal class CodexClient(
    context: Context,
    private val device: Device,
    private val listener: Listener,
) {
    internal interface Listener {
        fun onState(state: ConnectionState, agent: JSONObject?)
        fun onRpcEvent(message: JSONObject)
        fun onFailure(message: String)
        fun onAuthorizationExpired()
        fun onDirectFallback()
        fun onDirectStatusChanged() = Unit
    }

    private data class Pending(val callback: RpcCallback, val timeout: Runnable, val direct: Boolean)
    private val main = Handler(Looper.getMainLooper())
    private val pending = HashMap<Int, Pending>()
    private var socket: WebSocket? = null
    private var active = false
    private var online = false
    private var generation = 0
    private var nextId = 0
    private var reconnectAttempt = 0
    private var reconnect: Runnable? = null
    private var direct: DirectTransport? = null
    private var directId = ""
    private var directActive = false
    private var directLan = false
    internal val usingDirect: Boolean get() = directActive
    var directStatus: DirectStatus = initialDirectStatus()
        private set
    private var startTimeout: Runnable? = null
    private var directAvailable = false
    private var directLease = 0L
    private var nextAttempt = 0L
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private var defaultNetwork: android.net.Network? = null
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: android.net.Network) {
            main.post {
                if (!active || connectivity?.activeNetwork != network || network == defaultNetwork) return@post
                val changed = defaultNetwork != null || socket != null
                defaultNetwork = network
                if (changed) scheduleReconnect()
            }
        }
        override fun onLost(network: android.net.Network) {
            main.post {
                if (active && network == defaultNetwork && connectivity?.activeNetwork != network) {
                    defaultNetwork = null
                    scheduleReconnect()
                }
            }
        }
    }
    private val checkDirect = object : Runnable {
        override fun run() {
            if (!active) return
            if (online) {
                if (directActive) {
                    if (SystemClock.elapsedRealtime() >= directLease) dropDirect("直连授权续期超时。")
                    else if (!sendSignal("renew", directId)) dropDirect("无法通过 RM 续期直连授权。")
                } else tryDirect()
            }
            main.postDelayed(this, 4_000)
        }
    }

    fun start() {
        if (active) return
        active = true
        defaultNetwork = connectivity?.activeNetwork
        runCatching { connectivity?.registerDefaultNetworkCallback(networkCallback) }
        main.postDelayed(checkDirect, 4_000)
        open()
    }

    private fun open() {
        if (!active) return
        directAvailable = false
        nextAttempt = 0L
        resetDirectStatus()
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
                        stop()
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

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, null)
                main.post { if (active && currentGeneration == generation) scheduleReconnect() }
            }
        })
    }

    private fun handle(text: String, onDirect: Boolean = false) {
        val envelope = try { JSONObject(text) } catch (_: Exception) { return }
        val type = envelope.string("type")
        if (onDirect && (!directActive || type != "server_message")) return
        when (type) {
            "welcome", "agent_status" -> {
                val wasOnline = online
                online = envelope.string("status") == "connected"
                if (online) reconnectAttempt = 0
                listener.onState(if (online) ConnectionState.ONLINE else ConnectionState.OFFLINE, envelope.optJSONObject("agent"))
                if (!online && wasOnline) {
                    directAvailable = false
                    dropDirect()
                    resetDirectStatus()
                    rejectPending("远程设备已离线")
                }
                if (online) tryDirect()
            }
            "p2p" -> envelope.optJSONObject("p2p")?.let(::directSignal)
            "server_message" -> {
                val message = envelope.optJSONObject("message") ?: return
                if (message.has("method")) {
                    listener.onRpcEvent(message)
                    return
                }
                val id = (message.opt("id") as? Number)?.toInt() ?: return
                val request = pending[id]?.takeIf { it.direct == onDirect } ?: return
                pending.remove(id)
                main.removeCallbacks(request.timeout)
                val error = message.optJSONObject("error")
                if (error == null) request.callback.onComplete(message.optJSONObject("result") ?: JSONObject(), null)
                else request.callback.onComplete(null, IOException(error.string("message").ifEmpty { "Codex 请求失败" }))
            }
            "error" -> {
                val id = envelope.string("streamId").toIntOrNull()
                val request = if (id == null) null else pending[id]?.takeUnless { it.direct }?.also { pending.remove(id) }
                if (request == null) {
                    listener.onFailure(envelope.string("error").ifEmpty { "中继请求失败" })
                } else {
                    main.removeCallbacks(request.timeout)
                    request.callback.onComplete(null, IOException(envelope.string("error")))
                }
            }
        }
    }

    private fun sendSignal(kind: String, id: String = ""): Boolean {
        val signal = JSONObject().put("version", 1).put("kind", kind)
        if (id.isNotEmpty()) signal.put("id", id)
        if (kind == "begin" && localPrefixes().isNotEmpty()) signal.put("lan", true)
        return socket?.send(JSONObject().put("type", "p2p").put("p2p", signal).toString()) == true
    }

    private fun localPrefixes(): List<String> {
        val network = connectivity?.activeNetwork ?: return emptyList()
        val caps = connectivity.getNetworkCapabilities(network) ?: return emptyList()
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
            !(caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) return emptyList()
        return connectivity.getLinkProperties(network)?.linkAddresses.orEmpty()
            .filter { it.address is Inet4Address && it.address.isSiteLocalAddress && it.prefixLength in 1..31 }
            .take(8).map { "${it.address.hostAddress}/${it.prefixLength}" }
    }

    private fun tryDirect() {
        val now = SystemClock.elapsedRealtime()
        if (!online || !directAvailable || direct != null || now < nextAttempt || !NativeP2P.available) return
        nextAttempt = now + 45_000
        if (!sendSignal("begin")) {
            reportDirectStatus(DirectStatus.Relay("无法通过 RM 发送直连请求。"))
            return
        }
        reportDirectStatus(DirectStatus.Negotiating)
        val timeout = Runnable {
            startTimeout = null
            reportDirectStatus(DirectStatus.Relay("等待 RM 开始直连协商超时。"))
        }
        startTimeout = timeout
        main.postDelayed(timeout, 8_000)
    }

    private fun initialDirectStatus() = DirectStatus.Relay(
        if (!NativeP2P.available) "此应用无法加载 P2P 原生库。"
        else "当前连接未收到 RM 的 P2P 能力通知。请检查 RM 和 Agent 是否支持直连，以及双方是否通过 IPv4 连接 RM。"
    )

    private fun reportDirectStatus(status: DirectStatus) {
        if (directStatus == status) return
        directStatus = status
        listener.onDirectStatusChanged()
    }

    private fun clearStartTimeout() {
        startTimeout?.let(main::removeCallbacks)
        startTimeout = null
    }

    private fun resetDirectStatus() {
        clearStartTimeout()
        reportDirectStatus(initialDirectStatus())
    }

    private fun nativeFailure(reason: String): String = when (reason) {
        "prepare" -> "手机无法准备 UDP 直连候选地址。"
        "peer_offer_timeout" -> "等待 Agent 的直连候选地址超时。"
        "no_peer_candidate" -> "没有可用的 Agent IPv4 直连候选地址。"
        "punch_timeout" -> "UDP 打洞超时，双方未能建立直连路径。"
        "handshake" -> "直连握手失败或超时。"
        "activation_timeout" -> "等待 Agent 确认启用直连超时。"
        else -> "QUIC 连接已断开。"
    }

    private fun directSignal(signal: JSONObject) {
        if (signal.optInt("version") != 1) return
        val id = signal.string("id")
        when (signal.string("kind")) {
            "available" -> { directAvailable = true; tryDirect() }
            "busy" -> if (online && startTimeout != null) {
                clearStartTimeout()
                reportDirectStatus(DirectStatus.Relay("RM 的直连会话已满，稍后会自动重试。"))
            }
            "start" -> {
                if (!online || !directAvailable || id.length != 36 || direct != null || !NativeP2P.available) return
                clearStartTimeout()
                directLan = false
                signal.put("localPrefixes", JSONArray(if (signal.optBoolean("lan")) localPrefixes() else emptyList<String>()))
                val transport = DirectTransport.open(signal) ?: run {
                    sendSignal("stop", id)
                    reportDirectStatus(DirectStatus.Relay("手机无法启动 P2P 直连。"))
                    return
                }
                direct = transport
                directId = id
                reportDirectStatus(DirectStatus.Negotiating)
                transport.read(main, { text ->
                    if (direct === transport) {
                        val message = runCatching { JSONObject(text) }.getOrNull()
                        val outgoing = message?.optJSONObject("p2p")
                        when {
                            message?.string("type") == "native_direct_failure" -> dropDirect(nativeFailure(message.string("reason")))
                            message?.string("type") == "native_direct_lan" -> directLan = true
                            !directActive && message?.string("type") == "p2p" && outgoing?.string("id") == id -> {
                                if (socket?.send(text) != true) dropDirect("无法通过 RM 交换直连信令。")
                            }
                            directActive -> handle(text, onDirect = true)
                        }
                    }
                }, { if (direct === transport) dropDirect(if (directActive) "QUIC 连接已断开。" else "手机端直连协商已结束。") })
            }
            "offer", "ready" -> if (id == directId) direct?.signal(signal) ?: Unit
            "active" -> if (id == directId && direct != null && !directActive) {
                directLease = SystemClock.elapsedRealtime() + 12_000
                directActive = true
                direct?.signal(signal)
                reportDirectStatus(DirectStatus.Connected(directLan))
                Log.i("CodexRM-P2P", "Direct transport active (lan=$directLan)")
            }
            "lease" -> if (id == directId && directActive && SystemClock.elapsedRealtime() < directLease) {
                directLease = SystemClock.elapsedRealtime() + 12_000
            }
            "stop" -> if (id == directId) {
                val reason = when (signal.string("reason")) {
                    "client_candidate_unverified" -> "RM 未能验证手机的 UDP 地址。"
                    "agent_candidate_unverified" -> "RM 未能验证 Agent 的 UDP 地址。"
                    "invalid_offer" -> "RM 拒绝了格式无效的直连候选。"
                    else -> if (directActive) "RM 或 Agent 已结束直连会话。" else "RM 或 Agent 已结束直连协商，未提供具体原因。"
                }
                dropDirect(reason, signalPeer = false)
            }
        }
    }

    private fun dropDirect(failure: String? = null, signalPeer: Boolean = true) {
        val previous = direct ?: return
        val wasActive = directActive
        if (signalPeer) sendSignal("stop", directId)
        directActive = false
        directId = ""
        direct = null
        previous.close()
        if (failure != null && online) reportDirectStatus(DirectStatus.Relay(failure))
        val ids = pending.filterValues { it.direct }.keys.toList()
        ids.forEach { id ->
            pending.remove(id)?.let {
                main.removeCallbacks(it.timeout)
                it.callback.onComplete(null, IOException("直连已中断，请求结果未知，请先同步状态"))
            }
        }
        if (wasActive) {
            Log.i("CodexRM-P2P", "Direct transport closed; WebSocket fallback")
            if (failure != null && online) listener.onDirectFallback()
        }
    }

    private fun sendBusiness(text: String, onDirect: Boolean): Boolean {
        if (!onDirect) return socket?.send(text) == true
        val transport = direct
        if (transport?.send(text) == true) return true
        main.post { if (directActive && direct === transport) dropDirect("QUIC 无法发送数据。") }
        return false
    }

    fun request(method: String, params: JSONObject = JSONObject(), timeoutMs: Long = 120_000, callback: RpcCallback) {
        request(method, params, requireDirect = false, timeoutMs = timeoutMs, callback = callback)
    }

    fun requestDirectFile(operation: String, params: JSONObject, callback: RpcCallback) {
        request("codexrm/directFile/$operation", params, requireDirect = true, timeoutMs = 20_000, callback = callback)
    }

    fun measureDirectLatency(callback: (Long?, Exception?) -> Unit) {
        val started = SystemClock.elapsedRealtimeNanos()
        request("codexrm/info", JSONObject(), requireDirect = true, timeoutMs = 5_000) { _, error ->
            val elapsed = if (error == null) ((SystemClock.elapsedRealtimeNanos() - started + 500_000) / 1_000_000).coerceAtLeast(1) else null
            callback(elapsed, error)
        }
    }

    private fun request(method: String, params: JSONObject, requireDirect: Boolean, timeoutMs: Long, callback: RpcCallback) {
        if (requireDirect && !directActive) {
            main.post { callback.onComplete(null, IOException("P2P 直连已断开。")) }
            return
        }
        if (!active || !online || socket == null) {
            main.post { callback.onComplete(null, IOException("设备未连接，请确认本地 Agent 正在运行")) }
            return
        }
        nextId = if (nextId == Int.MAX_VALUE) 1 else nextId + 1
        val id = nextId
        val timeout = Runnable {
            pending.remove(id)?.callback?.onComplete(null, IOException(if (requireDirect) "P2P 直连请求超时" else "请求超时，请检查本地 Codex 是否正常运行"))
        }
        val onDirect = directActive
        pending[id] = Pending(callback, timeout, onDirect)
        val message = JSONObject().put("id", id).put("method", method).put("params", params)
        val envelope = JSONObject()
            .put("type", "client_message")
            .put("streamId", id.toString())
            .put("message", message)
        if (!sendBusiness(envelope.toString(), onDirect)) {
            pending.remove(id)
            callback.onComplete(null, IOException("消息发送失败"))
            return
        }
        main.postDelayed(timeout, timeoutMs)
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
        val sent = sendBusiness(envelope.toString(), directActive)
        if (!sent) listener.onFailure("连接已断开，操作未发送")
        return sent
    }

    private fun scheduleReconnect() {
        if (!active) return
        online = false
        dropDirect()
        resetDirectStatus()
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
        runCatching { connectivity?.unregisterNetworkCallback(networkCallback) }
        main.removeCallbacks(checkDirect)
        dropDirect()
        clearStartTimeout()
        generation++
        reconnect?.let(main::removeCallbacks)
        reconnect = null
        rejectPending("连接已关闭")
        socket?.close(1000, "Activity stopped")
        socket = null
    }
}
