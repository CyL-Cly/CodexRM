package com.codexrm.mobile

import android.os.Handler
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch

internal class DirectTransport private constructor(private val handle: Long) {
    fun read(main: Handler, receive: (String) -> Unit, closed: () -> Unit) {
        Thread({
            try {
                while (true) {
                    val data = NativeP2P.poll(handle) ?: break
                    val consumed = CountDownLatch(1)
                    if (!main.post {
                        try { receive(data.toString(Charsets.UTF_8)) } finally { consumed.countDown() }
                    }) break
                    consumed.await()
                }
            } finally {
                close()
                main.post(closed)
            }
        }, "codexrm-direct").start()
    }

    fun signal(signal: JSONObject) = NativeP2P.signal(handle, signal.toString().toByteArray(Charsets.UTF_8))
    fun send(message: String) = NativeP2P.send(handle, message.toByteArray(Charsets.UTF_8))
    fun upload(id: String, file: File, size: Long) = NativeP2P.upload(
        handle, id.toByteArray(Charsets.UTF_8), file.absolutePath.toByteArray(Charsets.UTF_8), size,
    )
    fun uploadProgress() = NativeP2P.uploadProgress(handle)
    fun close() = NativeP2P.close(handle)
    fun startDesktop() = NativeP2P.available && NativeP2P.startDesktop(handle)
    fun stopDesktop() { if (NativeP2P.available) NativeP2P.stopDesktop(handle) }
    fun desktopActive() = NativeP2P.available && NativeP2P.desktopActive(handle)
    fun desktopBytes() = if (NativeP2P.available) NativeP2P.desktopBytes(handle) else 0L
    fun pollDesktop() = if (NativeP2P.available) NativeP2P.pollDesktop(handle) else null
    fun sendDesktop(message: ByteArray) = NativeP2P.available && NativeP2P.sendDesktop(handle, message)

    companion object {
        fun open(start: JSONObject): DirectTransport? {
            val handle = NativeP2P.open(start.toString().toByteArray(Charsets.UTF_8))
            return if (handle == 0L) null else DirectTransport(handle)
        }
    }
}
