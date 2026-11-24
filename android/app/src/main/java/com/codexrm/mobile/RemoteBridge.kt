package com.codexrm.mobile

import android.os.Handler
import android.os.Looper

internal object RemoteBridge {
    @Volatile var transport: DirectTransport? = null
    @Volatile var deviceName: String = "\u7535\u8111"
    @Volatile var online: Boolean = false
    @Volatile var direct: Boolean = false
    @Volatile var listener: (() -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())

    fun update(client: CodexClient?, device: Device?) {
        transport = client?.directTransport()
        direct = client?.usingDirect == true && transport != null
        online = client != null && device != null
        deviceName = device?.serverName?.ifBlank { "\u7535\u8111" } ?: "\u7535\u8111"
        listener?.let { main.post(it) }
    }

    fun clear() {
        transport = null
        direct = false
        online = false
        listener?.let { main.post(it) }
    }
}
