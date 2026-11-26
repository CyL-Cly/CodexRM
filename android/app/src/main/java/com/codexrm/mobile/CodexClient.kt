package com.codexrm.mobile

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network as AndroidNetwork
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import org.json.JSONArray
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import kotlin.math.min

private const val RPC_OUTCOME_UNKNOWN = -32098
private const val DIRECT_START_TIMEOUT_MS = 8_000L
// Allow the start signal and a full native P2P negotiation (P2PAttempt in Go).
private const val DESKTOP_SHARE_CONNECT_TIMEOUT_MS = 30_000L
private const val DUAL_UNPREDICTABLE_NAT = "dual_unpredictable_nat"

internal enum class ConnectionState { CONNECTING, RECONNECTING, ONLINE, OFFLINE }
internal class RpcOutcomeUnknown(message: String) : IOException(message)
internal class CodexRpcError(message: String) : IOException(message)

internal fun decodeCodexRpcError(error: JSONObject): IOException {
    val message = error.string("message").ifEmpty { "Codex 请求失败" }
    return if (error.optInt("code") == RPC_OUTCOME_UNKNOWN) RpcOutcomeUnknown(message) else CodexRpcError(message)
}

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
    private val relayOnly: Boolean = false,
) {
    internal interface Listener {
        fun onState(state: ConnectionState, agent: JSONObject?)
        fun onRpcEvent(message: JSONObject)
        fun onFailure(message: String)
        fun onAuthorizationExpired()
        fun onDirectFallback()
        fun onDirectStatusChanged() = Unit
        fun onLocalPermissionRequired() = Unit
    }

    private data class Pending(val callback: RpcCallback, val timeout: Runnable, val direct: Boolean)
    private class PendingNAT(val id: String, val callback: (NatDiagnosticResult) -> Unit, val timeout: Runnable) {
        var started = false
        var phoneDone = false
        var usesP2PStun = false
        var phone: JSONObject? = null
        var agent: JSONObject? = null
    }
    private var pendingNAT: PendingNAT? = null
    private var natRetryAtMs = 0L
    private val main = Handler(Looper.getMainLooper())
    private val pending = HashMap<Int, Pending>()
    private var socket: WebSocket? = null
    private var local: LocalCodexConnection? = null
    private val localRunningThreads = mutableSetOf<String>()
    private val isLocal get() = device.agentId == LocalCodex.AGENT
    private var active = false
    private var online = false
    private var generation = 0
    private var nextId = 0
    private var reconnectAttempt = 0
    private var reconnect: Runnable? = null
    private var secureRelay: SecureRelay? = null
    private val welcomeTimeout = Runnable { scheduleReconnect() }
    private var direct: DirectTransport? = null
    private var directId = ""
    private var directActive = false
    private var directLan = false
    private var directNetworkHandle = 0L
    private val localContext = context.takeIf { isLocal }
    private val appContext = context.applicationContext
    private var directWifiLock: WifiManager.WifiLock? = null
    internal val usingDirect: Boolean get() = directActive
    internal fun directTransport(): DirectTransport? = activeDirectTransport
    private val activeDirectTransport: DirectTransport? get() = direct.takeIf { directActive }

    internal fun withDesktopShareTransport(ready: (DirectTransport?) -> Unit): () -> Unit {
        val started = SystemClock.elapsedRealtime()
        val connection = socket
        val check = object : Runnable {
            private var negotiated = directStatus == DirectStatus.Negotiating
            override fun run() {
                val transport = activeDirectTransport
                val now = SystemClock.elapsedRealtime()
                negotiated = negotiated || directStatus == DirectStatus.Negotiating
                if (transport != null || !active || !online || socket !== connection || relayOnly || !NativeP2P.available ||
                    (negotiated && directStatus is DirectStatus.Relay) || directRetry.delay(now) == null ||
                    (!directAvailable && now - started >= DIRECT_START_TIMEOUT_MS) ||
                    now - started >= DESKTOP_SHARE_CONNECT_TIMEOUT_MS
                ) ready(transport)
                else main.postDelayed(this, 100)
            }
        }
        main.post(check)
        return { main.removeCallbacks(check) }
    }
    var directStatus: DirectStatus = initialDirectStatus()
        private set
    private var startTimeout: Runnable? = null
    // An available signal may arrive before agent_status. Keep it for this RM socket.
    private var directAvailable = false
    private var directLease = 0L
    private val directRetry = DirectRetry()
    private val retryDirect = Runnable { tryDirect() }
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private var defaultNetwork: AndroidNetwork? = null
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: AndroidNetwork) {
            main.post {
                if (!active || connectivity?.activeNetwork != network || network == defaultNetwork) return@post
                val changed = defaultNetwork != null || socket != null || reconnect != null
                defaultNetwork = network
                if (changed) scheduleReconnect(networkRestored = true)
            }
        }
        override fun onLost(network: AndroidNetwork) {
            main.post {
                if (active && network == defaultNetwork && connectivity?.activeNetwork != network) {
                    defaultNetwork = null
                    scheduleReconnect()
                }
            }
        }
    }
    private var directSources = emptyList<DirectSource>()
    private val refreshDirectSources = Runnable {
        if (active) {
            val sources = collectDirectSources()
            if (sources != directSources) {
                directSources = sources
                // Let a live QUIC connection finish on its current network. If it
                // goes away the native link fails and the next offer uses this list.
                directRetry.onNetworkChanged()
                tryDirect()
            }
        }
    }
    private val directNetworksCallback = object : ConnectivityManager.NetworkCallback() {
        private fun changed() {
            main.removeCallbacks(refreshDirectSources)
            main.postDelayed(refreshDirectSources, 500)
        }
        override fun onAvailable(network: AndroidNetwork) = changed()
        override fun onLost(network: AndroidNetwork) = changed()
        override fun onLinkPropertiesChanged(network: AndroidNetwork, linkProperties: LinkProperties) = changed()
        override fun onCapabilitiesChanged(network: AndroidNetwork, networkCapabilities: NetworkCapabilities) = changed()
    }
    private val renewDirectLease = object : Runnable {
        override fun run() {
            if (!active) return
            if (online && directActive) {
                if (SystemClock.elapsedRealtime() >= directLease) dropDirect("直连授权续期超时。")
                else if (!sendSignal("renew", directId)) dropDirect("无法通过 RM 续期直连授权。")
            }
            main.postDelayed(this, 4_000)
        }
    }

    fun start() {
        if (active) return
        active = true
        if (isLocal) {
            listener.onState(ConnectionState.CONNECTING, null)
            local = LocalCodexConnection(requireNotNull(localContext), { handle(it, authenticated = true) }, { message, permission ->
                stop()
                listener.onState(ConnectionState.OFFLINE, null)
                if (permission) listener.onLocalPermissionRequired() else listener.onFailure(message)
            }).also { it.start() }
            return
        }
        defaultNetwork = connectivity?.activeNetwork
        runCatching { connectivity?.registerDefaultNetworkCallback(networkCallback) }
        if (!relayOnly && NativeP2P.available) {
            directSources = collectDirectSources()
            val networks = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build()
            runCatching { connectivity?.registerNetworkCallback(networks, directNetworksCallback) }
        }
        main.postDelayed(renewDirectLease, 4_000)
        listener.onState(ConnectionState.CONNECTING, null)
        open()
    }

    private fun open() {
        if (!active) return
        if (device.secureKey.isEmpty() && device.serverUrl.startsWith("http://", ignoreCase = true)) {
            stop()
            listener.onAuthorizationExpired()
            return
        }
        reconnect = null
        directAvailable = false
        directRetry.reset()
        resetDirectStatus()
        rejectPending("连接已中断")
        online = false
        val currentGeneration = ++generation
        closeSecureRelay()
        if (device.secureKey.isNotEmpty()) {
            secureRelay = SecureRelay(device.clientId, device.secureKey,
                write = { socket?.send(it) == true },
                onReady = { handle(JSONObject().put("type", "welcome").put("status", "connected").toString(), authenticated = true) },
                onMessage = { handle(it.toString(), authenticated = true) },
                onFailure = { if (active && currentGeneration == generation) scheduleReconnect() })
        }
        val request = relaySocketRequest(device)
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
                        Log.d("CodexRM", "Relay WebSocket failed", t)
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
        // The WebSocket ping timer starts only after the handshake.
        main.postDelayed(welcomeTimeout, 15_000)
    }

    private fun handle(text: String, onDirect: Boolean = false, authenticated: Boolean = false) {
        val envelope = try { JSONObject(text) } catch (_: Exception) { return }
        val type = envelope.string("type")
        if (!onDirect && !authenticated && secureRelay != null && type != "p2p") {
            secureRelay?.receive(envelope)
            return
        }
        if (onDirect && (!directActive || type != "server_message")) return
        when (type) {
            "welcome", "agent_status" -> {
                main.removeCallbacks(welcomeTimeout)
                reconnectAttempt = 0
                val wasOnline = online
                online = envelope.string("status") == "connected"
                listener.onState(if (online) ConnectionState.ONLINE else ConnectionState.OFFLINE, envelope.optJSONObject("agent"))
                if (!online && wasOnline) {
                    dropDirect()
                    resetDirectStatus()
                    rejectPending("远程设备已离线")
                    finishNAT("远程设备已离线。")
                }
                if (online && !isLocal) tryDirect()
            }
            "p2p" -> envelope.optJSONObject("p2p")?.let {
                if (it.string("kind").startsWith("diagnose_")) handleNAT(it) else directSignal(it)
            }
            "server_message" -> {
                val message = envelope.optJSONObject("message") ?: return
                if (message.has("method")) {
                    if (isLocal) trackLocalTurn(message)
                    listener.onRpcEvent(message)
                    return
                }
                val id = (message.opt("id") as? Number)?.toInt() ?: return
                val request = pending[id]?.takeIf { it.direct == onDirect } ?: return
                pending.remove(id)
                updateLocalWork()
                main.removeCallbacks(request.timeout)
                val error = message.optJSONObject("error")
                if (error == null) request.callback.onComplete(message.optJSONObject("result") ?: JSONObject(), null)
                else request.callback.onComplete(null, decodeCodexRpcError(error))
            }
            "error" -> {
                val id = envelope.string("streamId").toIntOrNull()
                val request = if (id == null) null else pending[id]?.takeUnless { it.direct }?.also { pending.remove(id) }
                updateLocalWork()
                if (request == null) {
                    listener.onFailure(envelope.string("error").ifEmpty { "中继请求失败" })
                } else {
                    main.removeCallbacks(request.timeout)
                    request.callback.onComplete(null, IOException(envelope.string("error")))
                }
            }
        }
    }

    private fun updateLocalWork() {
        local?.setBusy(pending.isNotEmpty() || localRunningThreads.isNotEmpty())
    }

    private fun trackLocalTurn(message: JSONObject) {
        val threadId = message.optJSONObject("params")?.string("threadId").orEmpty()
        if (threadId.isEmpty()) return
        when (message.string("method")) {
            "turn/started" -> localRunningThreads.add(threadId)
            "turn/completed" -> localRunningThreads.remove(threadId)
            else -> return
        }
        updateLocalWork()
    }

    private fun sendSignal(kind: String, id: String = ""): Boolean {
        val signal = JSONObject().put("version", 1).put("kind", kind)
        if (id.isNotEmpty()) signal.put("id", id)
        if (kind == "begin") {
            signal.put("dualNatPunch", true)
            signal.put("clientInitiated", true)
            signal.put("ipv6", true)
            if (collectDirectSources().any { it.lan }) signal.put("lan", true)
        }
        return socket?.send(JSONObject().put("type", "p2p").put("p2p", signal).toString()) == true
    }

    fun diagnoseNAT(callback: (NatDiagnosticResult) -> Unit) {
        val unavailable = when {
            !active || !online -> "Agent 未连接。"
            !NativeP2P.available -> "手机无法加载 P2P 原生库。"
            pendingNAT != null -> "诊断已在进行，请稍后再试。"
            SystemClock.elapsedRealtime() < natRetryAtMs -> "诊断刚刚发起过，请稍后重试。"
            else -> null
        }
        if (unavailable != null) {
            callback(NatDiagnosticResult(null, null, unavailable, retryAtMs = natRetryAtMs))
            return
        }
        val id = java.util.UUID.randomUUID().toString()
        val timeout = Runnable {
            val reason = if (pendingNAT?.started == true) "部分诊断未在时限内返回。" else "RM 未响应诊断请求，请检查 RM 是否已升级。"
            finishNAT(reason)
        }
        pendingNAT = PendingNAT(id, callback, timeout)
        main.postDelayed(timeout, 4_000)
        if (!sendSignal("diagnose", id)) finishNAT("无法通过 RM 发送诊断请求。")
    }

    private fun handleNAT(signal: JSONObject) {
        val request = pendingNAT ?: return
        if (signal.optInt("version") != 1 || signal.string("id") != request.id) return
        when (signal.string("kind")) {
            "diagnose_error" -> {
                val reason = signal.string("reason")
                if (reason == "diagnose_busy") setNATCooldown(signal, 14_000)
                finishNAT(when (reason) {
                    "agent_unavailable" -> "Agent 未连接。"
                    "agent_unsupported" -> "Agent 不支持 NAT 诊断，请更新 Agent。"
                    "no_ipv4_stun" -> "RM 没有可用的 IPv4 诊断端点，请管理员配置 CODEXRM_P2P_NAT_STUN。"
                    "diagnose_busy" -> "诊断刚刚发起过，请稍后重试。"
                    else -> "RM 无法启动 NAT 诊断。"
                })
            }
            "diagnose_start" -> {
                if (request.started) return
                val endpoints = signal.optJSONArray("stun")?.takeIf { it.length() in 1..4 }
                if (endpoints == null) { finishNAT("RM 没有下发可用的 STUN 端点。"); return }
                request.started = true
                request.usesP2PStun = signal.optBoolean("natUsesP2PStun")
                setNATCooldown(signal, 14_000)
                main.removeCallbacks(request.timeout)
                main.postDelayed(request.timeout, 14_000)
                val data = endpoints.toString().toByteArray(Charsets.UTF_8)
                Thread({
                    val report = runCatching {
                        NativeP2P.diagnoseNat(data)?.let { JSONObject(it.toString(Charsets.UTF_8)) }
                    }.getOrNull()
                    main.post {
                        if (pendingNAT !== request) return@post
                        request.phone = report
                        request.phoneDone = true
                        if (request.agent != null) finishNAT()
                    }
                }, "codexrm-nat-diagnostic").start()
            }
            "diagnose_result" -> if (request.started) {
                request.agent = signal.optJSONObject("natResult") ?: return
                if (request.phoneDone) finishNAT()
            }
        }
    }

    private fun setNATCooldown(signal: JSONObject, fallbackMs: Long) {
        val waitMs = signal.optLong("natRetryAfterMs", fallbackMs).coerceIn(1, 14_000)
        natRetryAtMs = SystemClock.elapsedRealtime() + waitMs
    }

    private fun finishNAT(reason: String? = null) {
        val request = pendingNAT ?: return
        pendingNAT = null
        main.removeCallbacks(request.timeout)
        request.callback(NatDiagnosticResult(request.phone, request.agent, reason, request.usesP2PStun, natRetryAtMs))
    }

    private data class DirectSource(val handle: Long, val prefix: String, val lan: Boolean)

    @Suppress("DEPRECATION")
    private fun collectDirectSources(): List<DirectSource> {
        val manager = connectivity ?: return emptyList()
        val default = manager.activeNetwork
        if (default != null && manager.getNetworkCapabilities(default)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) {
            return emptyList()
        }
        val networks = (listOfNotNull(default) + manager.allNetworks.sortedBy { it.networkHandle }).distinct()
        val addresses = networks.mapNotNull { network ->
            val caps = manager.getNetworkCapabilities(network) ?: return@mapNotNull null
            val lan = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            val cell = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) || (!lan && !cell) ||
                (cell && (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
                    (network != default && !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))))) return@mapNotNull null
            manager.getLinkProperties(network)?.linkAddresses.orEmpty().asSequence()
                .filter { link ->
                    val address = link.address
                    !address.isAnyLocalAddress && !address.isLoopbackAddress && !address.isLinkLocalAddress && !address.isMulticastAddress &&
                        (address is Inet4Address || (address is Inet6Address && address.address[0].toInt() and 0xe0 == 0x20))
                }
                .sortedBy { it.address.hostAddress }
                .groupBy { it.address is Inet6Address }.values.map { family ->
                    family.take(2).map { link ->
                        val ip = link.address.hostAddress.orEmpty().substringBefore('%')
                        DirectSource(network.networkHandle, "$ip/${link.prefixLength}",
                            lan && link.address is Inet4Address && link.address.isSiteLocalAddress && link.prefixLength in 1..31)
                    }
                }
        }.flatten()
        return ((addresses.mapNotNull { it.getOrNull(0) }) + addresses.mapNotNull { it.getOrNull(1) }).take(12)
    }

    private fun tryDirect() {
        if (relayOnly) return
        val now = SystemClock.elapsedRealtime()
        if (!active || !online || !directAvailable || direct != null || startTimeout != null || !NativeP2P.available) return
        main.removeCallbacks(retryDirect)
        val delay = directRetry.delay(now) ?: return
        if (delay > 0) {
            main.postDelayed(retryDirect, delay)
            return
        }
        if (!sendSignal("begin")) {
            scheduleReconnect()
            return
        }
        directRetry.requested(now)
        reportDirectStatus(DirectStatus.Negotiating)
        val timeout = Runnable {
            startTimeout = null
            retryAfterDirectFailure("等待 RM 开始直连协商超时。")
        }
        startTimeout = timeout
        main.postDelayed(timeout, DIRECT_START_TIMEOUT_MS)
    }

    private fun initialDirectStatus() = DirectStatus.Relay(
        if (!NativeP2P.available) "此应用无法加载 P2P 原生库。"
        else "当前连接未收到 RM 的 P2P 能力通知。请检查 RM 和 Agent 是否支持直连，以及 RM 是否正确接收两端的源地址。"
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

    private fun cancelDirectTimers() {
        clearStartTimeout()
        main.removeCallbacks(retryDirect)
    }

    private fun resetDirectStatus() {
        cancelDirectTimers()
        reportDirectStatus(initialDirectStatus())
    }

    private fun retryAfterDirectFailure(reason: String, wasConnected: Boolean = false) {
        if (!active || !online) return
        directRetry.failed(SystemClock.elapsedRealtime(), wasConnected)
        reportDirectStatus(DirectStatus.Relay("${reason}当前通过 RM 中继，稍后自动重试直连。"))
        tryDirect()
    }

    private fun suspendDirectForMapping(signalPeer: Boolean) {
        directRetry.suspendForMapping()
        cancelDirectTimers()
        dropDirect(signalPeer = signalPeer)
        reportDirectStatus(DirectStatus.Relay("双方的公网 UDP 映射难以预测，本轮未能建立直连。当前使用 RM 中继，网络或 Agent 连接变化后将重新检测。"))
    }

    private fun nativeFailure(reason: String): String = when (reason) {
        "prepare" -> "手机无法准备 UDP 直连候选地址。"
        "force_relay" -> "已跳过直连打洞，改用 RM 中继。"
        "peer_offer_timeout" -> "等待 Agent 的直连候选地址超时。"
        "no_peer_candidate" -> "没有可用的 Agent UDP 直连候选地址。"
        "punch_timeout" -> "UDP 打洞超时，双方未能建立直连路径。"
        "handshake" -> "直连握手失败或超时。"
        "activation_timeout" -> "等待 Agent 确认启用直连超时。"
        "auto_loss" -> "直连丢包过高，已切换到 RM 中继。"
        "manual_switch" -> "已按要求切换网络，当前使用 RM 中继。"
        else -> "QUIC 连接已断开。"
    }

    private fun directSignal(signal: JSONObject) {
        if (signal.optInt("version") != 1) return
        val id = signal.string("id")
        when (signal.string("kind")) {
            "available" -> {
                directAvailable = true
                // RM sends this when a P2P-capable Agent connects or when the RM socket is new.
                directRetry.onAgentAvailable()
                tryDirect()
            }
            "busy" -> if (online && startTimeout != null) {
                clearStartTimeout()
                retryAfterDirectFailure("RM 的直连会话已满。")
            }
            "start" -> {
                if (!online || !directAvailable || id.length != 36 || direct != null || !NativeP2P.available) return
                cancelDirectTimers()
                directRetry.started(SystemClock.elapsedRealtime())
                directLan = false
                directNetworkHandle = 0L
                val sources = collectDirectSources()
                directSources = sources
                signal.put("localSources", JSONArray(sources.map {
                    JSONObject().put("network", it.handle.toString()).put("prefix", it.prefix).put("lan", it.lan)
                }))
                val transport = DirectTransport.open(signal) ?: run {
                    sendSignal("stop", id)
                    retryAfterDirectFailure("手机无法启动 P2P 直连。")
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
                            message?.string("type") == "native_direct_failure" -> {
                                val reason = message.string("reason")
                                if (reason == DUAL_UNPREDICTABLE_NAT) suspendDirectForMapping(signalPeer = true)
                                else dropDirect(nativeFailure(reason))
                            }
                            message?.string("type") == "native_direct_lan" -> {
                                directLan = true
                                directNetworkHandle = message.string("network").toLongOrNull() ?: 0L
                            }
                            !directActive && message?.string("type") == "p2p" && outgoing?.string("id") == id -> {
                                if (device.secureKey.isNotEmpty() && outgoing.string("kind") == "offer") {
                                    outgoing.put("pinMac", signedDirectPin(device, id, "client", outgoing.string("pin")))
                                }
                                if (socket?.send(message.toString()) != true) dropDirect("无法通过 RM 交换直连信令。")
                            }
                            directActive -> handle(text, onDirect = true)
                        }
                    }
                }, { if (direct === transport) dropDirect(if (directActive) "QUIC 连接已断开。" else "手机端直连协商已结束。") })
            }
            "offer" -> if (id == directId) {
                if (device.secureKey.isNotEmpty() && !verifyDirectPin(device, id, signal)) {
                    dropDirect("直连身份验证失败。")
                } else direct?.signal(signal) ?: Unit
            }
            "ready" -> if (id == directId) direct?.signal(signal) ?: Unit
            "active" -> if (id == directId && direct != null && !directActive) {
                directLease = SystemClock.elapsedRealtime() + 12_000
                directActive = true
                directRetry.connected()
                direct?.signal(signal)
                acquireDirectWifiLock()
                reportDirectStatus(DirectStatus.Connected(directLan))
                Log.i("CodexRM-P2P", "Direct transport active (lan=$directLan)")
            }
            "lease" -> if (id == directId && directActive && SystemClock.elapsedRealtime() < directLease) {
                directLease = SystemClock.elapsedRealtime() + 12_000
            }
            "stop" -> if (direct != null && id == directId) {
                val code = signal.string("reason")
                if (!directActive && code == DUAL_UNPREDICTABLE_NAT) {
                    suspendDirectForMapping(signalPeer = false)
                    return
                }
                val reason = when (code) {
                    "client_candidate_unverified" -> "RM 未能验证手机的 UDP 地址。"
                    "agent_candidate_unverified" -> "RM 未能验证 Agent 的 UDP 地址。"
                    "invalid_offer" -> "RM 拒绝了格式无效的直连候选。"
                    else -> if (directActive) "RM 或 Agent 已结束直连会话。" else "RM 或 Agent 已结束直连协商，未提供具体原因。"
                }
                dropDirect(reason, signalPeer = false)
            }
        }
    }

    private fun acquireDirectWifiLock() {
        if (!directLan || directWifiLock != null || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        // Android only applies this mode while the app is foreground and screen on.
        // Do not hold it for cellular, Ethernet, public P2P, or the RM relay.
        runCatching {
            val network = if (directNetworkHandle != 0L) AndroidNetwork.fromNetworkHandle(directNetworkHandle)
                else connectivity?.activeNetwork
            if (connectivity?.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                directWifiLock = appContext.getSystemService(WifiManager::class.java)
                    ?.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "CodexRM:LAN")
                    ?.apply { acquire() }
            }
        }.onFailure { Log.w("CodexRM-P2P", "Wi-Fi low latency mode unavailable", it) }
    }

    private fun dropDirect(failure: String? = null, signalPeer: Boolean = true) {
        val previous = direct ?: return
        val wasActive = directActive
        if (signalPeer) sendSignal("stop", directId)
        directActive = false
        directId = ""
        direct = null
        directWifiLock?.let { lock ->
            runCatching { lock.release() }.onFailure { Log.w("CodexRM-P2P", "Wi-Fi lock release failed", it) }
        }
        directWifiLock = null
        previous.close()
        if (failure != null) retryAfterDirectFailure(failure, wasActive)
        val ids = pending.filterValues { it.direct }.keys.toList()
        ids.forEach { id ->
            pending.remove(id)?.let {
                main.removeCallbacks(it.timeout)
                it.callback.onComplete(null, RpcOutcomeUnknown("直连已中断，请求结果未知，请先同步状态"))
            }
        }
        if (wasActive) {
            Log.i("CodexRM-P2P", "Direct transport closed; WebSocket fallback")
            if (failure != null && online) listener.onDirectFallback()
        }
    }

    private fun sendBusiness(text: String, onDirect: Boolean): Boolean {
        if (isLocal) return local?.send(text) == true
        if (!onDirect) return secureRelay?.send(text) ?: (device.secureKey.isEmpty() && socket?.send(text) == true)
        val transport = direct
        if (transport?.send(text) == true) return true
        main.post { if (directActive && direct === transport) dropDirect("QUIC 无法发送数据。") }
        return false
    }

    fun request(method: String, params: JSONObject = JSONObject(), timeoutMs: Long = 120_000, callback: RpcCallback) {
        val directOnly = method.startsWith("codexrm/attachment/") || method == "codexrm/image/read"
        if (directOnly && !usingDirect) {
            main.post { callback.onComplete(null, IOException("此文件传输需要 P2P 直连")) }
            return
        }
        request(method, params, requireDirect = directOnly, timeoutMs = timeoutMs, callback = callback)
    }

    fun requestDesktopShare(transport: DirectTransport, operation: String, params: JSONObject, callback: RpcCallback) {
        if (activeDirectTransport !== transport) {
            main.post { callback.onComplete(null, IOException(DESKTOP_SHARE_DIRECT_REQUIRED)) }
            return
        }
        request("codexrm/attachment/$operation", params, requireDirect = true, timeoutMs = 120_000, callback = callback)
    }

    fun requestProviderSettings(operation: String, params: JSONObject, requireDirect: Boolean, callback: RpcCallback) {
        request("codexrm/provider/$operation", params, requireDirect && !isLocal, timeoutMs = 35_000, callback)
    }

    fun requestPhoneTransfer(operation: String, params: JSONObject, callback: RpcCallback) {
        request("codexrm/phoneTransfer/$operation", params, requireDirect = true, timeoutMs = 20_000, callback = callback)
    }

    fun requestDirectFile(operation: String, params: JSONObject, callback: RpcCallback) {
        request("codexrm/directFile/$operation", params, requireDirect = true, timeoutMs = 20_000, callback = callback)
    }

    fun measureDirectLatency(callback: (Long?, Exception?) -> Unit) {
        val transport = activeDirectTransport
        if (transport == null) {
            main.post { callback(null, IOException("P2P 直连已断开。")) }
            return
        }
        transport.measureLatency(main) { elapsedNanos ->
            when {
                activeDirectTransport !== transport -> callback(null, IOException("P2P 直连已断开。"))
                elapsedNanos <= 0L -> callback(null, IOException("延迟测试没有收到有效响应，请重试。"))
                else -> callback(((elapsedNanos + 500_000) / 1_000_000).coerceAtLeast(1), null)
            }
        }
    }

    private fun request(method: String, params: JSONObject, requireDirect: Boolean, timeoutMs: Long, callback: RpcCallback) {
        if (requireDirect && !directActive) {
            main.post { callback.onComplete(null, IOException("P2P 直连已断开。")) }
            return
        }
        if (!active || !online || (socket == null && local == null)) {
            main.post { callback.onComplete(null, IOException("设备未连接，请确认本地 Agent 正在运行")) }
            return
        }
        nextId = if (nextId == Int.MAX_VALUE) 1 else nextId + 1
        val id = nextId
        val timeout = Runnable {
            val request = pending.remove(id)
            updateLocalWork()
            request?.callback?.onComplete(null, RpcOutcomeUnknown(if (requireDirect) "P2P 直连请求超时" else "请求超时，请检查本地 Codex 是否正常运行"))
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
        updateLocalWork()
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

    private fun closeSecureRelay() {
        secureRelay?.close()
        secureRelay = null
    }

    private fun scheduleReconnect(networkRestored: Boolean = false) {
        if (!active) return
        main.removeCallbacks(welcomeTimeout)
        online = false
        dropDirect()
        resetDirectStatus()
        generation++
        closeSecureRelay()
        socket?.cancel()
        socket = null
        rejectPending("服务器连接中断")
        finishNAT("服务器连接中断。")
        listener.onState(ConnectionState.RECONNECTING, null)
        reconnect?.let(main::removeCallbacks)
        val delay = if (networkRestored) {
            reconnectAttempt = 0
            0L
        } else {
            val retryDelay = min(30_000L, 1_000L * (1 shl reconnectAttempt))
            reconnectAttempt = min(5, reconnectAttempt + 1)
            retryDelay
        }
        val task = Runnable { open() }
        reconnect = task
        main.postDelayed(task, delay)
    }

    private fun rejectPending(message: String) {
        val requests = pending.values.toList()
        pending.clear()
        localRunningThreads.clear()
        updateLocalWork()
        requests.forEach {
            main.removeCallbacks(it.timeout)
            it.callback.onComplete(null, RpcOutcomeUnknown(message))
        }
    }

    fun stop() {
        if (!active && socket == null) return
        active = false
        online = false
        if (isLocal) {
            local?.close(); local = null
            rejectPending("本机 Codex 已关闭")
            return
        }
        runCatching { connectivity?.unregisterNetworkCallback(networkCallback) }
        runCatching { connectivity?.unregisterNetworkCallback(directNetworksCallback) }
        main.removeCallbacks(refreshDirectSources)
        main.removeCallbacks(renewDirectLease)
        main.removeCallbacks(welcomeTimeout)
        dropDirect()
        cancelDirectTimers()
        generation++
        reconnect?.let(main::removeCallbacks)
        reconnect = null
        rejectPending("连接已关闭")
        finishNAT("连接已关闭。")
        closeSecureRelay()
        socket?.close(1000, "Activity stopped")
        socket = null
    }
}
