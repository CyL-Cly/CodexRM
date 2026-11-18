package com.codexrm.mobile

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors

internal class AttachmentStore(context: Context) {
    private val context = context.applicationContext
    private val prefs = context.getSharedPreferences("attachment_drafts", Context.MODE_PRIVATE)
    private val directory = File(context.filesDir, "attachment-drafts").apply { mkdirs() }
    private val main = Handler(Looper.getMainLooper())

    fun load(key: String): MutableList<JSONObject> = try {
        val array = JSONArray(prefs.getString(key, "[]"))
        (0 until array.length()).mapNotNull { array.optJSONObject(it) }.toMutableList()
    } catch (_: Exception) { mutableListOf() }

    fun save(key: String, files: List<JSONObject>) {
        if (files.isEmpty()) prefs.edit().remove(key).apply()
        else prefs.edit().putString(key, JSONArray(files).toString()).apply()
    }

    fun localFile(file: JSONObject): File? {
        val path = file.string("localPath")
        if (path.isEmpty()) return null
        val local = File(path).canonicalFile
        return local.takeIf { it.parentFile == directory.canonicalFile && it.isFile }
    }

    fun remove(key: String, file: JSONObject) {
        localFile(file)?.delete()
        save(key, load(key).filter { it.string("draftId") != file.string("draftId") })
    }

    // Copy document-provider streams off the UI thread. The private copy keeps
    // a selection usable after the picker closes, a restart or a lost URI grant.
    fun stage(key: String, uris: List<Uri>, complete: (String?) -> Unit) {
        worker.execute {
            var error: String? = null
            for (uri in uris) {
                var local: File? = null
                try {
                    val existing = load(key)
                    if (existing.size >= 10) throw IOException("每条消息最多添加 10 个附件")
                    var name = uri.lastPathSegment.orEmpty().substringAfterLast('/').ifEmpty { "附件" }
                    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (nameIndex >= 0) name = cursor.getString(nameIndex).orEmpty().ifEmpty { name }
                            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                            if (sizeIndex >= 0 && !cursor.isNull(sizeIndex) && cursor.getLong(sizeIndex) > MAX_SIZE) throw IOException("$name 超过单个附件 20 MB 的限制")
                        }
                    }
                    val id = UUID.randomUUID().toString()
                    val destination = File(directory, id)
                    local = destination
                    var size = 0L
                    val total = existing.sumOf { it.optLong("size") }
                    val source = context.contentResolver.openInputStream(uri) ?: throw IOException("无法读取 $name")
                    source.use { input ->
                        destination.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                size += count
                                if (size > MAX_SIZE) throw IOException("$name 超过单个附件 20 MB 的限制")
                                if (total + size > MAX_TOTAL) throw IOException("每条消息的附件总大小不能超过 50 MB")
                                output.write(buffer, 0, count)
                            }
                        }
                    }
                    existing += JSONObject().put("draftId", id).put("name", name.take(200)).put("size", size)
                        .put("localPath", destination.absolutePath).put("mimeType", context.contentResolver.getType(uri).orEmpty())
                    save(key, existing)
                    local = null
                } catch (failure: Exception) {
                    local?.delete()
                    error = failure.message ?: "读取附件失败"
                    break
                }
            }
            main.post { complete(error) }
        }
    }

    companion object {
        const val MAX_SIZE = 20L * 1024 * 1024
        const val MAX_TOTAL = 50L * 1024 * 1024
        const val MARKER = "[CodexRM attachment] "
        val IMAGE_TYPES = setOf("image/png", "image/jpeg", "image/gif", "image/webp")
        private val worker = Executors.newSingleThreadExecutor()

        fun sizeLabel(size: Long): String = if (size >= 1024 * 1024) String.format(Locale.US, "%.1f MB", size / 1024.0 / 1024.0)
            else "${maxOf(1L, (size + 1023) / 1024)} KB"

        fun input(text: String, files: List<JSONObject>): JSONArray {
            val input = JSONArray()
            if (text.isNotEmpty()) input.put(JSONObject().put("type", "text").put("text", text).put("text_elements", JSONArray()))
            for (file in files) {
                val metadata = JSONObject()
                for (key in listOf("id", "name", "path", "size", "mimeType")) metadata.put(key, file.opt(key))
                input.put(JSONObject().put("type", "text").put("text", MARKER + metadata.toString()).put("text_elements", JSONArray()))
                if (file.string("mimeType") in IMAGE_TYPES) input.put(JSONObject().put("type", "localImage").put("path", file.string("path")))
            }
            return input
        }
    }
}
