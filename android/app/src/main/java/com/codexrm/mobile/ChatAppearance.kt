package com.codexrm.mobile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.ImageDecoder
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.File
import java.io.IOException
import org.json.JSONObject
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

internal enum class AppearanceArea(val key: String, val title: String, val defaultMask: Int) {
    CHAT("chat", "聊天背景", 78), SIDEBAR("sidebar", "侧边栏图片", 85), SETTINGS("settings", "设置背景", 86);

    fun baseColor(): Int = when {
        this == CHAT && Ui.dark -> android.graphics.Color.BLACK
        this == SIDEBAR && Ui.dark -> 0xff212121.toInt()
        this == SIDEBAR -> Ui.SIDEBAR
        else -> Ui.BACKGROUND
    }
}

internal enum class BackgroundSource(val title: String) {
    DEFAULT("默认背景"), LOCAL("本地图片"), RANDOM("随机图片"), GALLERY("AI 拟人图库")
}

internal const val RANDOM_REFRESH_MS = 3 * 24 * 60 * 60 * 1000L

internal fun randomRefreshDelay(lastSuccess: Long, now: Long): Long =
    if (lastSuccess <= 0L || now < lastSuccess) 0L else (RANDOM_REFRESH_MS - (now - lastSuccess)).coerceAtLeast(0L)

internal fun appearanceOpacity(color: Int, percent: Int): Int =
    (color and 0x00ffffff) or (((color ushr 24) * percent + 50) / 100 shl 24)

internal class WallpaperDrawable(private val bitmap: Bitmap, private val base: Int, mask: Int) : Drawable() {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val destination = RectF()
    var mask: Int = mask
        set(value) { field = value; invalidateSelf() }

    override fun draw(canvas: Canvas) {
        val scale = maxOf(bounds.width().toFloat() / bitmap.width, bounds.height().toFloat() / bitmap.height)
        val halfWidth = bitmap.width * scale / 2f
        val halfHeight = bitmap.height * scale / 2f
        val x = bounds.exactCenterX()
        val y = bounds.exactCenterY()
        destination.set(x - halfWidth, y - halfHeight, x + halfWidth, y + halfHeight)
        val save = canvas.save()
        canvas.clipRect(bounds)
        canvas.drawColor(base)
        canvas.drawBitmap(bitmap, null, destination, paint)
        canvas.drawColor(appearanceOpacity(base, mask))
        canvas.restoreToCount(save)
    }

    override fun getOutline(outline: Outline) { outline.setRect(bounds) }
    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit
    @Deprecated("Deprecated in Android") override fun getOpacity(): Int = PixelFormat.OPAQUE
}

/** One app-wide appearance on this Android installation, independent of the paired computer. */
internal class ChatAppearance(context: Context, private val changed: (AppearanceArea?) -> Unit) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("chat_appearance", Context.MODE_PRIVATE)
    private val directory = File(app.filesDir, "chat-backgrounds")
    private val handler = Handler(Looper.getMainLooper())
    private val bitmaps = mutableMapOf<AppearanceArea, Bitmap>()
    private val jobs = mutableMapOf<AppearanceArea, AppearanceImages.Job>()
    private val cachedLoads = mutableMapOf<AppearanceArea, AppearanceImages.Job>()
    private val retryAt = mutableMapOf<AppearanceArea, Long>()
    private val refresh = Runnable { refreshRandom() }
    private var foreground = false
    private var closed = false

    private fun file(area: AppearanceArea) = File(directory, "${area.key}.png")
    private fun key(area: AppearanceArea, property: String) = "${area.key}.$property"

    fun source(area: AppearanceArea): BackgroundSource = BackgroundSource.entries.firstOrNull {
        it.name == prefs.getString(key(area, "source"), null)
    } ?: BackgroundSource.DEFAULT

    fun summary(area: AppearanceArea): String = when {
        jobs.containsKey(area) -> "正在加载图片…"
        source(area) == BackgroundSource.GALLERY -> prefs.getString(key(area, "label"), null) ?: BackgroundSource.GALLERY.title
        source(area) == BackgroundSource.RANDOM -> "随机图片 · 每 3 天更新"
        else -> source(area).title
    }

    fun workPage(area: AppearanceArea): String? = prefs.getString(key(area, "work"), null)?.let { metadata ->
        runCatching { JSONObject(metadata).getString("detailUrl") }.getOrNull()
    }

    fun bitmap(area: AppearanceArea): Bitmap? = bitmaps[area]
    fun drawable(area: AppearanceArea): Drawable = bitmap(area)?.let { WallpaperDrawable(it, area.baseColor(), mask(area)) }
        ?: ColorDrawable(area.baseColor())

    fun mask(area: AppearanceArea): Int = prefs.getInt(key(area, "mask"), area.defaultMask).coerceIn(0, 100)
    fun setMask(area: AppearanceArea, value: Int) {
        prefs.edit().putInt(key(area, "mask"), value.coerceIn(0, 100)).apply()
        changed(area)
    }

    fun bubbleOpacity(): Int = prefs.getInt("bubble_opacity", 100).coerceIn(0, 100)
    fun setBubbleOpacity(value: Int) {
        prefs.edit().putInt("bubble_opacity", value.coerceIn(0, 100)).apply()
        changed(null)
    }

    fun start() {
        foreground = true
        for (area in AppearanceArea.entries) {
            val saved = file(area)
            if (source(area) == BackgroundSource.DEFAULT || bitmap(area) != null || area in cachedLoads || !saved.isFile) continue
            cachedLoads[area] = AppearanceImages.submit({ AppearanceImages.decode(ImageDecoder.createSource(saved), 1920) }) { job, result ->
                if (cachedLoads[area] !== job) return@submit
                cachedLoads.remove(area)
                result.getOrNull()?.let { bitmaps[area] = it; changed(area) }
                refreshRandom()
            }
        }
        refreshRandom()
    }

    fun stop() {
        foreground = false
        handler.removeCallbacks(refresh)
    }

    fun close() {
        closed = true
        stop()
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        cachedLoads.values.forEach { it.cancel() }
        cachedLoads.clear()
    }

    fun reset(area: AppearanceArea) {
        jobs.remove(area)?.cancel()
        cachedLoads.remove(area)?.cancel()
        retryAt.remove(area)
        bitmaps.remove(area)
        prefs.edit().remove(key(area, "source")).remove(key(area, "label"))
            .remove(key(area, "lastSuccess")).remove(key(area, "work")).apply()
        file(area).delete()
        changed(area)
        schedule()
    }

    fun loadLocal(uri: Uri, complete: (AppearanceImages.Job, Result<Bitmap>) -> Unit): AppearanceImages.Job =
        AppearanceImages.submit({
            val input = app.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open image")
            decode(input, 2560)
        }, complete)

    fun saveCrop(area: AppearanceArea, image: Bitmap, region: RectF, complete: (Boolean) -> Unit) =
        replace(area, BackgroundSource.LOCAL, complete) {
            cropWallpaper(image, region)
        }

    fun random(area: AppearanceArea, complete: ((Boolean) -> Unit)? = null) =
        replace(area, BackgroundSource.RANDOM, complete) {
            val response = json(AppearanceImages.RANDOM)
            if (response.optInt("code") != 200) throw IOException("Random API failed")
            download(AppearanceImages.randomImageUrls(response.getString("imgurl")))
        }

    private fun AppearanceImages.Job.download(urls: List<String>): Bitmap {
        for (url in urls) {
            try {
                return read(url, AppearanceImages.IMAGE_LIMIT) { decode(it, 1920) }
            } catch (error: IOException) {
                if (cancelled || url == urls.last()) throw error
            }
        }
        throw IOException("Missing background image")
    }

    private fun decode(input: java.io.InputStream, maxEdge: Int): Bitmap = input.use {
        val temporary = File.createTempFile("image-", ".tmp", app.cacheDir)
        try {
            temporary.outputStream().use { output -> AppearanceImages.copyLimited(input, output, AppearanceImages.IMAGE_LIMIT) }
            AppearanceImages.decode(ImageDecoder.createSource(temporary), maxEdge)
        } finally {
            temporary.delete()
        }
    }

    private fun replace(
        area: AppearanceArea,
        source: BackgroundSource,
        complete: ((Boolean) -> Unit)?,
        load: AppearanceImages.Job.() -> Bitmap,
    ) {
        if (closed) return
        jobs.remove(area)?.cancel()
        jobs[area] = AppearanceImages.submit({
            directory.mkdirs()
            val bitmap = load()
            val stored = File.createTempFile("wallpaper-", ".tmp", directory)
            try {
                stored.outputStream().use { if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) throw IOException("Cannot save background") }
                stored to bitmap
            } catch (error: Exception) {
                stored.delete()
                throw error
            }
        }) { job, result ->
            val staged = result.getOrNull()
            if (jobs[area] !== job) {
                staged?.first?.delete()
                return@submit
            }
            jobs.remove(area)
            val saved = staged != null && runCatching {
                Files.move(staged.first.toPath(), file(area).toPath(), REPLACE_EXISTING, ATOMIC_MOVE)
            }.isSuccess
            if (saved && staged != null) {
                cachedLoads.remove(area)?.cancel()
                bitmaps[area] = staged.second
                prefs.edit().putString(key(area, "source"), source.name)
                    .remove(key(area, "label")).remove(key(area, "work"))
                    .putLong(key(area, "lastSuccess"), if (source == BackgroundSource.RANDOM) System.currentTimeMillis() else 0L)
                    .apply()
                retryAt.remove(area)
            } else {
                staged?.first?.delete()
                if (source(area) == BackgroundSource.RANDOM) retryAt[area] = SystemClock.elapsedRealtime() + 30 * 60 * 1000L
            }
            changed(area)
            complete?.invoke(saved)
            schedule()
        }
        changed(area)
        schedule()
    }

    private fun delay(area: AppearanceArea): Long {
        if (area in jobs || area in cachedLoads || source(area) != BackgroundSource.RANDOM) return Long.MAX_VALUE
        val ageDelay = if (bitmap(area) == null) 0L else
            randomRefreshDelay(prefs.getLong(key(area, "lastSuccess"), 0), System.currentTimeMillis())
        return maxOf(ageDelay, (retryAt[area] ?: 0) - SystemClock.elapsedRealtime())
    }

    private fun refreshRandom() {
        if (!foreground) return
        for (area in AppearanceArea.entries) if (delay(area) == 0L) random(area)
        schedule()
    }

    private fun schedule() {
        handler.removeCallbacks(refresh)
        if (!foreground) return
        val next = AppearanceArea.entries.minOf(::delay)
        if (next != Long.MAX_VALUE) handler.postDelayed(refresh, next)
    }
}
