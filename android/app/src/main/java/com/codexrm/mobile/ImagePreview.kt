package com.codexrm.mobile

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.IOException

internal fun showImagePreview(context: Context, loader: MessageImageLoader, image: MessageImage, thumbnail: Bitmap? = null) {
    val dialog = PredictiveBackDialog(context, android.R.style.Theme_Material_NoActionBar)
    val key = loader.originalKey(image)
    val picture = ZoomImageView(context).apply {
        contentDescription = image.name
        setOnClickListener { dialog.dismiss() }
    }
    val status = TextView(context).apply {
        gravity = Gravity.CENTER
        setPadding(Ui.dp(context, 24), Ui.dp(context, 12), Ui.dp(context, 24), Ui.dp(context, 12))
        setTextColor(Color.WHITE)
        visibility = View.GONE
    }
    var saving = false
    var actions: MiuixDialog? = null
    picture.setOnLongClickListener {
        if (picture.drawable != null && !saving && actions?.isShowing != true) {
            actions = MiuixDialog.Builder(context).setItems(arrayOf("保存图片")) { _, _ ->
                val save = {
                    saving = true
                    loader.saveOriginal(context.contentResolver, key, image.name) { result ->
                        saving = false
                        val text = result.fold({ "已保存到相册" }, { it.message ?: "保存图片失败" })
                        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
                    }
                }
                if (context is MainActivity) context.withAlbumPermission(save) else save()
            }.setNegativeButton("取消", null).show()
        }
        true
    }
    val root = FrameLayout(context).apply {
        setBackgroundColor(Color.BLACK)
        addView(picture, FrameLayout.LayoutParams(-1, -1))
        addView(status, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        addView(TextView(context).apply {
            text = "×"
            textSize = 30f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = Ui.rounded(0x88000000.toInt(), Ui.dp(context, 24).toFloat())
            contentDescription = "关闭图片预览"
            setOnClickListener { dialog.dismiss() }
        }, FrameLayout.LayoutParams(Ui.dp(context, 48), Ui.dp(context, 48), Gravity.TOP or Gravity.END).apply {
            topMargin = Ui.dp(context, 32)
            marginEnd = Ui.dp(context, 12)
        })
    }
    dialog.setBackContent(root)
    dialog.setOnDismissListener { actions?.dismiss() }
    dialog.show()
    dialog.window?.apply {
        setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        setLayout(-1, -1)
        if (Build.VERSION.SDK_INT >= 30) {
            insetsController?.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insetsController?.hide(WindowInsets.Type.systemBars())
        } else {
            @Suppress("DEPRECATION")
            decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
    }
    fun display(bitmap: Bitmap) {
        status.visibility = View.GONE
        picture.replaceBitmap(bitmap)
        loader.loadPreview(key) { original -> if (dialog.isShowing) picture.replaceBitmap(original) }
    }
    fun load() {
        status.visibility = View.VISIBLE
        status.text = "正在加载图片…"
        status.setOnClickListener(null)
        status.isClickable = false
        loader.load(image) { result ->
            if (!dialog.isShowing) return@load
            result.fold(::display) { error ->
                status.text = "${error.message ?: "图片加载失败"}\n点击重试"
                status.setOnClickListener { load() }
            }
        }
    }
    if (thumbnail == null) load() else display(thumbnail)
}

private class ZoomImageView(context: Context) : ImageView(context) {
    private val transform = Matrix()
    private var zoom = 1f
    private var left = 0f
    private var top = 0f
    private var multiTouch = false

    init { scaleType = ScaleType.MATRIX }

    private val pinch = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            zoomTo(zoom * detector.scaleFactor, detector.focusX, detector.focusY)
            return true
        }
    }).apply { isQuickScaleEnabled = false }

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(event: MotionEvent) = true
        override fun onSingleTapConfirmed(event: MotionEvent) = !multiTouch && performClick()
        override fun onDoubleTap(event: MotionEvent): Boolean {
            zoomTo(if (zoom > 1f) 1f else 2.5f, event.x, event.y)
            return true
        }
        override fun onLongPress(event: MotionEvent) { if (!multiTouch) performLongClick() }
        override fun onScroll(first: MotionEvent?, event: MotionEvent, dx: Float, dy: Float): Boolean {
            if (!pinch.isInProgress && !multiTouch) {
                left -= dx
                top -= dy
                updateMatrix()
            }
            return true
        }
    })

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) multiTouch = false
        if (event.pointerCount > 1) multiTouch = true
        pinch.onTouchEvent(event)
        gestures.onTouchEvent(event)
        return true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        reset()
    }

    fun replaceBitmap(bitmap: Bitmap) {
        setImageBitmap(bitmap)
        updateMatrix()
    }

    private fun reset() {
        zoom = 1f
        left = 0f
        top = 0f
        updateMatrix()
    }

    private fun zoomTo(target: Float, x: Float, y: Float) {
        val next = target.coerceIn(1f, 6f)
        val ratio = next / zoom
        left = x - (x - left) * ratio
        top = y - (y - top) * ratio
        zoom = next
        updateMatrix()
    }

    private fun updateMatrix() {
        val photo = drawable ?: return
        val imageWidth = photo.intrinsicWidth
        val imageHeight = photo.intrinsicHeight
        if (width <= 0 || height <= 0 || imageWidth <= 0 || imageHeight <= 0) return
        val scale = zoom * minOf(width.toFloat() / imageWidth, height.toFloat() / imageHeight)
        val scaledWidth = imageWidth * scale
        val scaledHeight = imageHeight * scale
        left = if (scaledWidth <= width) (width - scaledWidth) / 2 else left.coerceIn(width - scaledWidth, 0f)
        top = if (scaledHeight <= height) (height - scaledHeight) / 2 else top.coerceIn(height - scaledHeight, 0f)
        transform.setScale(scale, scale)
        transform.postTranslate(left, top)
        imageMatrix = transform
    }
}

internal fun saveImageToAlbum(resolver: ContentResolver, file: File, name: String) {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, options)
    val mime = options.outMimeType?.takeIf { it.startsWith("image/") } ?: throw IOException("不支持的图片格式")
    val extension = when (mime) {
        "image/jpeg" -> "jpg"
        "image/x-icon", "image/vnd.microsoft.icon" -> "ico"
        else -> mime.substringAfter('/')
    }
    val base = name.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.')
        .replace(Regex("[^\\p{L}\\p{N} _-]"), "_").trim().take(50)
    val displayName = "${base.ifEmpty { "CodexRM" }}_${System.currentTimeMillis()}.$extension"
    val scoped = Build.VERSION.SDK_INT >= 29
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
        put(MediaStore.Images.Media.MIME_TYPE, mime)
        if (scoped) {
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/CodexRM")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
    }
    file.inputStream().use { input ->
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("无法在相册创建图片")
        try {
            val output = resolver.openOutputStream(uri) ?: throw IOException("无法写入相册")
            output.use { input.copyTo(it) }
            if (scoped && resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null) == 0)
                throw IOException("无法发布相册图片")
        } catch (error: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw error
        }
    }
}
