package com.codexrm.mobile

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors

internal data class SharedContent(val text: String, val files: List<Uri>)

internal class DesktopShare(context: Context, private val client: CodexClient) {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    fun send(content: SharedContent, progress: (String) -> Unit, complete: (Int, Exception?) -> Unit) {
        client.withDesktopShareTransport { transport ->
            if (transport == null) complete(0, IOException(DESKTOP_SHARE_DIRECT_REQUIRED))
            else send(content, transport, progress, complete)
        }
    }

    fun send(content: SharedContent, transport: DirectTransport, progress: (String) -> Unit, complete: (Int, Exception?) -> Unit) {
        val sendText = content.text.isNotBlank()
        val count = content.files.size + if (sendText) 1 else 0
        val sender = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
        fun next(index: Int) {
            if (index == count) { complete(index, null); return }
            progress("正在读取文件 ${index + 1}/$count")
            worker.execute {
                val prepared = runCatching {
                    val temp = File.createTempFile("desktop-share-", ".part", context.cacheDir)
                    try {
                        val uri = content.files.getOrNull(index)
                        val name = if (uri == null) {
                            temp.writeText(content.text)
                            "分享内容.txt"
                        } else copy(uri, temp)
                        name to temp
                    } catch (error: Exception) {
                        temp.delete()
                        throw error
                    }
                }
                main.post {
                    prepared.fold({ (name, file) ->
                        val params = JSONObject().put("name", name).put("size", file.length())
                            .put("destination", "downloads").put("sender", sender)
                        client.uploadDesktopFile(transport, params, file, { percent ->
                            progress("${index + 1}/$count · $name · $percent%")
                        }) { _, error ->
                            file.delete()
                            if (error == null) next(index + 1) else complete(index, error)
                        }
                    }, { complete(index, it as? Exception ?: IOException(it)) })
                }
            }
        }
        next(0)
    }

    private fun copy(uri: Uri, file: File): String {
        var name = uri.lastPathSegment.orEmpty().substringAfterLast('/').ifBlank { "分享文件" }
        val resolver = context.contentResolver
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst()) {
                name = it.getString(0)?.ifBlank { name } ?: name
                if (!it.isNull(1) && it.getLong(1) > MAX_SIZE) throw IOException("$name 超过 512 MB")
            }
        }
        val source = resolver.openInputStream(uri) ?: throw IOException("无法读取 $name")
        source.use { input ->
            file.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var written = 0L
                while (true) {
                    val bytes = input.read(buffer)
                    if (bytes < 0) break
                    written += bytes
                    if (written > MAX_SIZE) throw IOException("$name 超过 512 MB")
                    output.write(buffer, 0, bytes)
                }
            }
        }
        return name
    }

    private companion object {
        const val MAX_SIZE = 512L * 1024 * 1024
        val worker = Executors.newSingleThreadExecutor()
    }
}
