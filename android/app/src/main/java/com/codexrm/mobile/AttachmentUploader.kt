package com.codexrm.mobile

import android.util.Base64
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream

internal class AttachmentUploader(private val store: AttachmentStore, private val client: CodexClient) {
    fun upload(key: String, files: List<JSONObject>, progress: (String) -> Unit, complete: (Exception?) -> Unit) {
        fun next(index: Int) {
            if (index == files.size) { complete(null); return }
            val file = files[index]
            if (file.string("path").isNotEmpty()) { next(index + 1); return }
            var stream: InputStream? = null
            var id = ""
            var offset = 0L
            fun fail(error: Exception) {
                runCatching { stream?.close() }
                if (id.isNotEmpty()) client.request("codexrm/attachment/abort", JSONObject().put("id", id)) { _, _ -> }
                complete(error)
            }
            var chunkSize = 256 * 1024
            fun chunk() {
                if (offset == file.optLong("size")) {
                    runCatching { stream?.close() }; stream = null
                    client.request("codexrm/attachment/finish", JSONObject().put("id", id)) { result, error ->
                        if (error != null || result == null) { fail(error ?: IOException("附件上传失败")); return@request }
                        for (field in listOf("id", "name", "size", "path", "mimeType")) file.put(field, result.opt(field))
                        val drafts = store.load(key)
                        val replaced = drafts.map { if (it.string("draftId") == file.string("draftId")) file else it }
                        store.save(key, replaced)
                        next(index + 1)
                    }
                    return
                }
                val buffer = ByteArray(minOf(chunkSize.toLong(), file.optLong("size") - offset).toInt())
                val count = try { stream?.read(buffer) ?: -1 } catch (error: Exception) { fail(error); return }
                if (count <= 0) { fail(IOException("附件内容不完整，请重新选择")); return }
                val params = JSONObject().put("id", id).put("offset", offset)
                    .put("data", Base64.encodeToString(buffer, 0, count, Base64.NO_WRAP))
                client.request("codexrm/attachment/chunk", params) { result, error ->
                    if (error != null || result == null) { fail(error ?: IOException("附件上传失败")); return@request }
                    if (result.optLong("offset", -1) != offset + count) { fail(IOException("附件上传位置不匹配")); return@request }
                    offset += count
                    progress("${file.string("name")} · 上传中 ${offset * 100 / maxOf(1L, file.optLong("size"))}%")
                    chunk()
                }
            }
            progress("${file.string("name")} · 正在上传…")
            client.request("codexrm/attachment/begin", JSONObject().put("name", file.string("name")).put("size", file.optLong("size"))) { result, error ->
                if (error != null || result == null) { fail(error ?: IOException("附件上传失败")); return@request }
                id = result.string("id")
                chunkSize = result.optInt("chunkSize", 256 * 1024).coerceIn(1, 256 * 1024)
                try { stream = store.localFile(file)?.inputStream() ?: throw IOException("附件已丢失，请重新选择") }
                catch (failure: Exception) { fail(failure); return@request }
                chunk()
            }
        }
        next(0)
    }
}
