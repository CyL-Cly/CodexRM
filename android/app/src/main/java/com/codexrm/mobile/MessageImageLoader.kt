package com.codexrm.mobile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.LruCache
import okhttp3.Request
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.Executors

internal data class ImageEnvironment(
    val scope: String,
    val cwd: String,
    val client: CodexClient?,
    val localImages: Boolean,
    val relayImages: Boolean = false,
)

internal class MessageImageLoader(context: Context, private val environment: () -> ImageEnvironment) {
    private val disk = ImageFileCache(File(context.filesDir, "message-images"))
    private val main = Handler(Looper.getMainLooper())
    private val memory = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }
    private val pending = LinkedHashMap<String, MutableList<(Result<Bitmap>) -> Unit>>()
    private val queue = ArrayDeque<() -> Unit>()
    private var running = 0
    private var closed = false
    private var nextLoadId = 0L

    fun load(image: MessageImage, complete: (Result<Bitmap>) -> Unit) {
        val env = environment()
        val key = image.cacheKey(env.scope, env.cwd)
        if (image.allowsEarlyCache) memory.get(key)?.let { complete(Result.success(it)); return }
        // A bare local path must still be revalidated. History keys are scoped to a message.
        val requestKey = if (image.allowsEarlyCache) key else "$key\nload:${++nextLoadId}"
        if (image.allowsEarlyCache) pending[requestKey]?.let { it += complete; return }
        pending[requestKey] = mutableListOf(complete)
        queue.addLast {
            fun finish(result: Result<Bitmap>) {
                if (closed) return
                if (image.allowsEarlyCache) result.getOrNull()?.let { memory.put(key, it) }
                val listeners = pending.remove(requestKey).orEmpty()
                running--
                listeners.forEach { it(result) }
                pump()
            }
            worker.execute {
                val cached = if (image.allowsEarlyCache) disk.read(key) else null
                if (cached != null) {
                    val decoded = runCatching { decode(cached) }
                    if (decoded.isSuccess) { main.post { finish(decoded) }; return@execute }
                    disk.remove(key)
                }
                if (image.isRemote) {
                    val result = runCatching {
                        val file = disk.write(key) { output ->
                            if (image.source.startsWith("data:", true)) {
                                val comma = image.source.indexOf(',')
                                if (comma < 0 || !image.source.substring(0, comma).endsWith(";base64", true) || image.source.length - comma > 28 * 1024 * 1024)
                                    throw IOException("图片编码无效")
                                Base64.decode(image.source.substring(comma + 1), Base64.DEFAULT).inputStream().use { copyLimited(it, output) }
                            } else {
                                // Never attach relay credentials to image hosts or redirects.
                                Network.client.newCall(Request.Builder().url(image.source).build()).execute().use { response ->
                                    if (!response.isSuccessful) throw IOException("图片下载失败（${response.code}）")
                                    val body = response.body ?: throw IOException("图片内容为空")
                                    if (body.contentLength() > AttachmentStore.MAX_SIZE) throw IOException("图片超过 20 MB")
                                    body.byteStream().use { copyLimited(it, output) }
                                }
                            }
                        }
                        decode(file)
                    }
                    if (result.isFailure) disk.remove(key)
                    main.post { finish(result) }
                } else main.post {
                    if (closed) return@post
                    readAgent(image, env) { result ->
                        worker.execute {
                            val decoded = result.mapCatching { bytes -> decode(disk.write(key) { it.write(bytes) }) }
                            if (decoded.isFailure) disk.remove(key)
                            main.post { finish(decoded) }
                        }
                    }
                }
            }
        }
        pump()
    }

    // Cache before dispatch, while the draft still owns its private source file.
    // Every send path (new turn, steer and queue) shares this step.
    fun retain(files: List<JSONObject>, store: AttachmentStore, complete: (Exception?) -> Unit) {
        val env = environment()
        worker.execute {
            val error = runCatching {
                for (file in files.filter(MessageImages::isImage)) {
                    val image = MessageImage.fromSource(file.string("path"), file.string("name"), file.string("id")) ?: continue
                    val key = image.cacheKey(env.scope, env.cwd)
                    if (disk.read(key) == null) {
                        // Edited queue entries may reference an Agent upload without a local copy.
                        if (file.string("localPath").isEmpty() && file.string("path").isNotEmpty()) continue
                        val local = store.localFile(file) ?: throw IOException("图片副本已丢失，请重新选择")
                        disk.write(key) { output -> local.inputStream().use { copyLimited(it, output) } }
                    }
                }
            }.exceptionOrNull()
            main.post { if (!closed) complete(error as? Exception) }
        }
    }

    private fun pump() {
        while (!closed && running < 2 && queue.isNotEmpty()) { running++; queue.removeFirst().invoke() }
    }

    private fun readAgent(image: MessageImage, env: ImageEnvironment, complete: (Result<ByteArray>) -> Unit) {
        val client = env.client
        if (client == null) { complete(Result.failure(IOException("设备未连接"))); return }
        if (image.attachmentId.isEmpty() && !env.localImages) { complete(Result.failure(IOException("请更新电脑端 Agent 以显示本地图片"))); return }
        val output = ByteArrayOutputStream()
        var offset = 0L
        var expectedSize = -1L
        var version = ""
        val attachmentRead = if (env.relayImages && !client.usingDirect) "${CHAT_IMAGE_RPC}read" else "codexrm/attachment/read"
        fun chunk() {
            if (closed) return
            val params = JSONObject().put("offset", offset)
            val method = if (image.attachmentId.isNotEmpty()) {
                params.put("id", image.attachmentId); attachmentRead
            } else {
                params.put("path", image.source).put("cwd", env.cwd).put("version", version); "codexrm/image/read"
            }
            client.request(method, params) { result, error ->
                if (closed) return@request
                if (error != null || result == null) { complete(Result.failure(error ?: IOException("图片读取失败"))); return@request }
                val decoded = runCatching {
                    val file = result.objectAt("file")
                    val size = file.optLong("size", -1)
                    if (size <= 0 || size > AttachmentStore.MAX_SIZE || (expectedSize >= 0 && expectedSize != size) ||
                        !file.string("mimeType").startsWith("image/") || result.string("data").length > 360 * 1024)
                        throw IOException("图片内容无效或超过 20 MB")
                    if (image.attachmentId.isNotEmpty() && file.string("id") != image.attachmentId) throw IOException("图片附件不匹配")
                    val bytes = Base64.decode(result.string("data"), Base64.DEFAULT)
                    val next = result.optLong("offset", -1)
                    if (bytes.isEmpty() || next != offset + bytes.size || next > size) throw IOException("图片内容不完整")
                    expectedSize = size
                    if (version.isNotEmpty() && file.string("version") != version) throw IOException("图片已变化，请重试")
                    version = file.string("version")
                    offset = next
                    output.write(bytes)
                }
                if (decoded.isFailure) { complete(Result.failure(decoded.exceptionOrNull()!!)); return@request }
                if (offset == expectedSize) complete(Result.success(output.toByteArray())) else chunk()
            }
        }
        chunk()
    }

    fun originalKey(image: MessageImage): String = environment().let { image.cacheKey(it.scope, it.cwd) }

    fun loadPreview(key: String, complete: (Bitmap) -> Unit) {
        worker.execute {
            val bitmap = runCatching { disk.read(key)?.let { decode(it, maxSide = 3200) } }.getOrNull()
            if (bitmap != null) main.post { if (!closed) complete(bitmap) }
        }
    }

    fun saveOriginal(resolver: android.content.ContentResolver, key: String, name: String, complete: (Result<Unit>) -> Unit) {
        worker.execute {
            val result = runCatching {
                val file = disk.read(key) ?: throw IOException("原图缓存已清理，请重新打开图片")
                saveImageToAlbum(resolver, file, name)
            }
            main.post { if (!closed) complete(result) }
        }
    }

    fun close() { closed = true; pending.clear(); queue.clear(); memory.evictAll() }

    companion object {
        private val worker = Executors.newFixedThreadPool(2)

        private fun copyLimited(input: InputStream, output: OutputStream) {
            val buffer = ByteArray(64 * 1024)
            var size = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                size += count
                if (size > AttachmentStore.MAX_SIZE) throw IOException("图片超过 20 MB")
                output.write(buffer, 0, count)
            }
        }

        private fun decode(file: File, maxSide: Int = 1600): Bitmap {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, options)
            if (options.outWidth <= 0 || options.outHeight <= 0) throw IOException("不支持的图片格式")
            options.inJustDecodeBounds = false
            options.inSampleSize = 1
            while (maxOf(options.outWidth, options.outHeight) / options.inSampleSize > maxSide) options.inSampleSize *= 2
            return BitmapFactory.decodeFile(file.path, options) ?: throw IOException("图片解码失败")
        }
    }
}
