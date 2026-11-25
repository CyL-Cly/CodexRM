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
    fun measureLatency(main: Handler, receive: (Long) -> Unit) {
        Thread({
            val elapsedNanos = NativeP2P.measureLatency(handle)
            main.post { receive(elapsedNanos) }
        }, "codexrm-latency").start()
    }
    fun upload(id: String, file: File, size: Long) = NativeP2P.upload(
        handle, id.toByteArray(Charsets.UTF_8), file.absolutePath.toByteArray(Charsets.UTF_8), size,
    )
    fun uploadProgress() = NativeP2P.uploadProgress(handle)
    fun download(id: String, file: File, size: Long, phoneTransfer: Boolean) = NativeP2P.download(
        handle, id.toByteArray(Charsets.UTF_8), file.absolutePath.toByteArray(Charsets.UTF_8), size, phoneTransfer,
    )
    fun cancelDownload(id: String) = NativeP2P.cancelDownload(handle, id.toByteArray(Charsets.UTF_8))
    fun close() = NativeP2P.close(handle)

    companion object {
        fun open(start: JSONObject): DirectTransport? {
            val handle = NativeP2P.open(start.toString().toByteArray(Charsets.UTF_8))
            return if (handle == 0L) null else DirectTransport(handle)
        }
    }
}
