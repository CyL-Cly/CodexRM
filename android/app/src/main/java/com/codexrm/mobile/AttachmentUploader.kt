package com.codexrm.mobile

import android.util.Base64
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream

internal const val CHAT_IMAGE_RPC = "codexrm/chatImage/"

internal class AttachmentUploader(
    private val store: AttachmentStore,
    private val client: CodexClient,
    private val relayImages: Boolean,
) {
    fun upload(key: String, files: List<JSONObject>, progress: (JSONObject, Int) -> Unit, complete: (Exception?) -> Unit) {
        fun next(index: Int) {
            if (index == files.size) { complete(null); return }
            val file = files[index]
            if (file.string("path").isNotEmpty()) { next(index + 1); return }
            client.uploadAttachment(
                JSONObject().put("name", file.string("name")).put("size", file.optLong("size")),
                { store.localFile(file)?.inputStream() ?: throw IOException("附件已丢失，请重新选择") },
                { percent -> progress(file, percent) },
                relayImage = relayImages && AttachmentStore.isRelayImage(file.string("name"), file.string("mimeType")),
            ) { result, error ->
                if (error != null || result == null) { complete(error ?: IOException("附件上传失败")); return@uploadAttachment }
                for (field in listOf("id", "name", "size", "path", "mimeType")) file.put(field, result.opt(field))
                store.save(key, store.load(key).map { if (it.string("draftId") == file.string("draftId")) file else it })
                next(index + 1)
            }
        }
        next(0)
    }
}

internal fun CodexClient.uploadAttachment(
    metadata: JSONObject,
    open: () -> InputStream,
    progress: (Int) -> Unit,
    begun: JSONObject? = null,
    desktopTransport: DirectTransport? = null,
    desktopControl: DesktopUploadControl? = null,
    relayImage: Boolean = false,
    complete: RpcCallback,
) {
    val size = metadata.optLong("size")
    var stream: InputStream? = null
    var id = ""
    var offset = 0L
    var chunkSize = 256 * 1024
    fun requestPart(operation: String, params: JSONObject, callback: RpcCallback) {
        when {
            desktopTransport != null -> requestDesktopShare(desktopTransport, operation, params, callback)
            relayImage -> request("$CHAT_IMAGE_RPC$operation", params, callback = callback)
            else -> request("codexrm/attachment/$operation", params, callback = callback)
        }
    }
    fun fail(error: Exception) {
        runCatching { stream?.close() }
        if (id.isNotEmpty()) requestPart("abort", JSONObject().put("id", id)) { _, _ -> }
        complete.onComplete(null, error)
    }
    fun chunk() {
        if (desktopControl?.cancelled == true) { fail(IOException("发送已取消")); return }
        if (offset == size) {
            runCatching { stream?.close() }
            stream = null
            desktopControl?.committing()
            requestPart("finish", JSONObject().put("id", id)) { result, error ->
                if (error != null || result == null) fail(error ?: IOException("附件上传失败"))
                else {
                    progress(100)
                    complete.onComplete(result, null)
                }
            }
            return
        }
        val buffer = ByteArray(minOf(chunkSize.toLong(), size - offset).toInt())
        val count = try { stream?.read(buffer) ?: -1 } catch (error: Exception) { fail(error); return }
        if (count <= 0) { fail(IOException("附件内容不完整，请重新选择")); return }
        val params = JSONObject().put("id", id).put("offset", offset)
            .put("data", Base64.encodeToString(buffer, 0, count, Base64.NO_WRAP))
        requestPart("chunk", params) { result, error ->
            if (error != null || result == null) { fail(error ?: IOException("附件上传失败")); return@requestPart }
            if (result.optLong("offset", -1) != offset + count) { fail(IOException("附件上传位置不匹配")); return@requestPart }
            offset += count
            progress((offset * 100 / maxOf(1L, size)).toInt())
            chunk()
        }
    }
    fun begin(result: JSONObject) {
        id = result.string("id")
        chunkSize = result.optInt("chunkSize", 256 * 1024).coerceIn(1, 256 * 1024)
        try { stream = open() } catch (failure: Exception) { fail(failure); return }
        chunk()
    }
    progress(0)
    if (begun != null) begin(begun)
    else requestPart("begin", metadata) { result, error ->
        if (error != null || result == null) fail(error ?: IOException("附件上传失败"))
        else begin(result)
    }
}
