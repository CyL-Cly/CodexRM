package com.codexrm.mobile

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

internal data class RemoteFile(val workspace: String, val threadId: String, val path: String, val computer: Boolean = false) {
    fun params(): JSONObject = JSONObject().put("workspace", workspace).put("threadId", threadId).put("path", path).apply {
        if (computer) put("scope", "computer")
    }
}

internal data class StagedDownload(val name: String, val file: File)

internal class DirectFileDownload(
    private val client: CodexClient,
    private val phoneTransfer: Boolean = false,
) {
    private data class StreamRead(val transport: DirectTransport, val id: String)

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var cancelled = false
    @Volatile private var streamRead: StreamRead? = null

    fun cancel() {
        cancelled = true
        val read = streamRead ?: return
        read.transport.cancelDownload(read.id)
        // Also close the Agent handle in case the native worker had not yet
        // registered its cancellation callback when Cancel was pressed.
        main.post { closeRead(read.transport, read.id) }
    }

    fun start(cache: File, source: RemoteFile, progress: (Long, Long) -> Unit, complete: (Result<StagedDownload>) -> Unit) {
        start(cache, source.params(), progress, complete = complete)
    }

    fun start(cache: File, source: JSONObject, progress: (Long, Long) -> Unit, reportCancellation: Boolean = false, complete: (Result<StagedDownload>) -> Unit) {
        val transport = client.directTransport()
        thread(name = "codexrm-file-download") {
            var id = ""
            var temp: File? = null
            val outcome = runCatching {
                val direct = transport ?: throw IOException("P2P 直连已断开。")
                val parameters = JSONObject(source.toString()).put("downloadStream", true)
                val header = request(direct, "open", parameters)
                id = header.string("id")
                val name = header.string("name")
                val size = header.optLong("size", -1)
                if (id.isEmpty() || name.isBlank() || '/' in name || '\\' in name || size !in 0L..(512L shl 20)) {
                    throw IOException("文件信息无效")
                }
                if (size > cache.usableSpace) throw IOException("手机存储空间不足")
                val destination = File.createTempFile("codexrm-direct-", ".part", cache).also { temp = it }
                main.post { if (!cancelled) progress(0, size) }
                if (header.optBoolean("downloadStream")) {
                    downloadStream(direct, id, destination, size, progress)
                } else {
                    // An older Agent ignores the negotiation field and sends the legacy chunks.
                    downloadChunks(direct, id, destination, size, progress)
                }
                id = "" // Both modes close their reader at end of file.
                main.post { if (!cancelled) progress(size, size) }
                StagedDownload(name, destination)
            }
            val direct = transport
            if (id.isNotEmpty() && direct != null) {
                val handle = id
                if (reportCancellation) runCatching {
                    request(direct, "close", JSONObject().put("id", handle), allowCancelled = true)
                } else main.post { closeRead(direct, handle) }
            }
            if (outcome.isFailure) temp?.delete()
            main.post {
                if (cancelled) {
                    outcome.getOrNull()?.file?.delete()
                    if (reportCancellation) complete(Result.failure(IOException("接收已取消")))
                } else complete(outcome)
            }
        }
    }

    private fun downloadStream(transport: DirectTransport, id: String, destination: File, size: Long, progress: (Long, Long) -> Unit) {
        val read = StreamRead(transport, id)
        streamRead = read
        val poll = object : Runnable {
            private var last = 0L
            override fun run() {
                if (cancelled || streamRead !== read) return
                val count = destination.length().coerceAtMost(size)
                if (count > last) {
                    last = count
                    progress(count, size)
                }
                main.postDelayed(this, 150)
            }
        }
        main.postDelayed(poll, 150)
        try {
            if (cancelled || !transport.download(id, destination, size, phoneTransfer)) {
                throw IOException(if (cancelled) "下载已取消" else "P2P 文件下载中断，请重试")
            }
        } finally {
            streamRead = null
            main.removeCallbacks(poll)
        }
    }

    private fun downloadChunks(transport: DirectTransport, id: String, destination: File, size: Long, progress: (Long, Long) -> Unit) {
        var offset = 0L
        var lastReport = SystemClock.elapsedRealtime()
        destination.outputStream().buffered().use { output ->
            do {
                val slice = request(transport, "read", JSONObject().put("id", id).put("offset", offset))
                val encoded = slice.opt("data") as? String ?: throw IOException("缺少文件数据")
                if (encoded.length > 4 * ((256 * 1024 + 2) / 3)) throw IOException("文件分块无效")
                val data = Base64.decode(encoded, Base64.DEFAULT)
                val next = slice.optLong("offset", -1)
                if (next != offset + data.size || next > size || (data.isEmpty() && size != 0L)) throw IOException("文件分块无效")
                output.write(data)
                offset = next
                val now = SystemClock.elapsedRealtime()
                if (now - lastReport >= 150 && offset < size) {
                    lastReport = now
                    val received = offset
                    main.post { if (!cancelled) progress(received, size) }
                }
            } while (offset < size)
        }
    }

    private fun closeRead(transport: DirectTransport, id: String) {
        if (client.directTransport() === transport) rpc("close", JSONObject().put("id", id)) { _, _ -> }
    }

    private fun rpc(operation: String, params: JSONObject, callback: RpcCallback) {
        if (phoneTransfer) client.requestPhoneTransfer(operation, params, callback)
        else client.requestDirectFile(operation, params, callback)
    }

    private fun request(transport: DirectTransport, operation: String, params: JSONObject, allowCancelled: Boolean = false): JSONObject {
        val response = CompletableFuture<JSONObject>()
        main.post {
            if (cancelled && !allowCancelled) response.completeExceptionally(IOException("下载已取消"))
            else if (client.directTransport() !== transport) response.completeExceptionally(IOException("P2P 直连已断开。"))
            else rpc(operation, params) { value, error ->
                if (error != null) response.completeExceptionally(error)
                else if (value == null) response.completeExceptionally(IOException("没有收到文件响应"))
                else response.complete(value)
            }
        }
        try { return response.get(23, TimeUnit.SECONDS) }
        catch (error: ExecutionException) { throw error.cause ?: error }
    }
}
