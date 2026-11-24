package com.codexrm.mobile

import android.os.Handler
import android.os.Looper
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

internal class DirectFileDownload(private val client: CodexClient) {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var cancelled = false

    fun cancel() { cancelled = true }

    fun start(cache: File, source: RemoteFile, progress: (Long, Long) -> Unit, complete: (Result<StagedDownload>) -> Unit) {
        thread(name = "codexrm-file-download") {
            var id = ""
            var temp: File? = null
            val outcome = runCatching {
                val header = request("open", source.params())
                id = header.string("id")
                val name = header.string("name")
                val size = header.optLong("size", -1)
                if (id.isEmpty() || name.isBlank() || '/' in name || '\\' in name || size !in 0L..(512L shl 20)) {
                    throw IOException("文件信息无效")
                }
                if (size > cache.usableSpace) throw IOException("手机存储空间不足")
                val destination = File.createTempFile("codexrm-direct-", ".part", cache).also { temp = it }
                var offset = 0L
                main.post { if (!cancelled) progress(0, size) }
                destination.outputStream().buffered().use { output ->
                    do {
                        if (cancelled) throw IOException("下载已取消")
                        val slice = request("read", JSONObject().put("id", id).put("offset", offset))
                        val encoded = slice.opt("data") as? String ?: throw IOException("缺少文件数据")
                        if (encoded.length > 4 * ((256 * 1024 + 2) / 3)) throw IOException("文件分块无效")
                        val data = Base64.decode(encoded, Base64.DEFAULT)
                        val next = slice.optLong("offset", -1)
                        if (next != offset + data.size || next > size || (data.isEmpty() && size != 0L)) throw IOException("文件分块无效")
                        output.write(data)
                        offset = next
                        val received = offset
                        main.post { if (!cancelled) progress(received, size) }
                    } while (offset < size)
                }
                id = "" // Agent closes its handle after the last chunk.
                StagedDownload(name, destination)
            }
            if (id.isNotEmpty()) {
                val handle = id
                main.post { if (client.usingDirect) client.requestDirectFile("close", JSONObject().put("id", handle)) { _, _ -> } }
            }
            if (outcome.isFailure) temp?.delete()
            main.post {
                if (cancelled) outcome.getOrNull()?.file?.delete() else complete(outcome)
            }
        }
    }

    private fun request(operation: String, params: JSONObject): JSONObject {
        val response = CompletableFuture<JSONObject>()
        main.post {
            if (cancelled) response.completeExceptionally(IOException("下载已取消"))
            else client.requestDirectFile(operation, params) { value, error ->
                if (error != null) response.completeExceptionally(error)
                else if (value == null) response.completeExceptionally(IOException("没有收到文件响应"))
                else response.complete(value)
            }
        }
        try { return response.get(23, TimeUnit.SECONDS) }
        catch (error: ExecutionException) { throw error.cause ?: error }
    }
}
