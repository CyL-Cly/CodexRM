package com.codexrm.mobile

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.os.Handler
import android.os.Looper
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

internal object AppearanceImages {
    const val GALLERY = "https://xn--pssy23gqgbz2d718b.com"
    const val API = "$GALLERY/open-api/v1/"
    const val RANDOM = "https://www.dmoe.cc/random.php?return=json"
    const val IMAGE_LIMIT = 24L * 1024 * 1024
    private val client = OkHttpClient.Builder()
        .callTimeout(20, TimeUnit.SECONDS)
        .followSslRedirects(false)
        .build()
    private val workers = Executors.newFixedThreadPool(4)
    private val main = Handler(Looper.getMainLooper())

    fun randomImageUrls(url: String): List<String> {
        val proxy = url.toHttpUrlOrNull() ?: return listOf(url)
        if (proxy.host != "image.baidu.com" || proxy.encodedPath != "/search/down") return listOf(url)
        val original = proxy.queryParameter("url")?.toHttpUrlOrNull()?.takeIf {
            it.isHttps && it.host.endsWith(".sinaimg.cn") && it.username.isEmpty() && it.password.isEmpty()
        }
        // This proxy can return HTTP 200 with an empty body even when the original is available.
        return listOfNotNull(original?.toString(), url)
    }

    class Job {
        @Volatile var cancelled = false
            private set
        private var call: Call? = null

        @Synchronized fun cancel() {
            cancelled = true
            call?.cancel()
        }

        @Synchronized private fun start(request: Request): Call {
            if (cancelled) throw IOException("Cancelled")
            return client.newCall(request).also { call = it }
        }

        fun <T> read(url: String, limit: Long, consume: (InputStream) -> T): T {
            val address = url.toHttpUrlOrNull()?.takeIf { it.isHttps && it.username.isEmpty() && it.password.isEmpty() }
                ?: throw IOException("Invalid image service URL")
            val active = start(Request.Builder().url(address).build())
            try {
                return active.execute().use { response ->
                    val body = response.body ?: throw IOException("Missing response body")
                    if (!response.isSuccessful || body.contentLength() > limit) throw IOException("HTTP ${response.code}")
                    body.byteStream().use(consume)
                }
            } finally {
                synchronized(this) { call = null }
            }
        }

        fun json(url: String): JSONObject = read(url, 8L * 1024 * 1024) { input ->
            val output = java.io.ByteArrayOutputStream()
            copyLimited(input, output, 8L * 1024 * 1024)
            JSONObject(output.toString(Charsets.UTF_8.name()))
        }
    }

    // Completion always runs on the main thread, including for cancelled jobs that created a temp file.
    fun <T> submit(work: Job.() -> T, complete: (Job, Result<T>) -> Unit): Job = Job().also { job ->
        workers.execute {
            val result = try {
                if (job.cancelled) throw IOException("Cancelled")
                Result.success(job.work())
            } catch (error: Exception) {
                Result.failure(error)
            }
            main.post { complete(job, result) }
        }
    }

    fun copyLimited(input: InputStream, output: java.io.OutputStream, limit: Long) {
        val buffer = ByteArray(32 * 1024)
        var total = 0L
        while (true) {
            val size = input.read(buffer)
            if (size == -1) return
            total += size
            if (total > limit) throw IOException("Image exceeds size limit")
            output.write(buffer, 0, size)
        }
    }

    fun decode(source: ImageDecoder.Source, maxEdge: Int): Bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        val width = info.size.width
        val height = info.size.height
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        val longest = maxOf(width, height)
        if (longest > maxEdge) {
            val ratio = maxEdge.toDouble() / longest
            decoder.setTargetSize((width * ratio).roundToInt().coerceAtLeast(1), (height * ratio).roundToInt().coerceAtLeast(1))
        }
    }
}
