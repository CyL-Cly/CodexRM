package com.codexrm.mobile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.min

internal fun showAttachmentMarkup(
    context: MainActivity,
    bitmap: Bitmap,
    title: String,
    save: (Bitmap) -> Unit,
): MiuixDialog {
    val panel = Ui.column(context).apply { setPadding(Ui.dp(context, 12), 0, Ui.dp(context, 12), 0) }
    val surface = MarkupSurface(context, bitmap)
    panel.addView(surface, Ui.params(-1, Ui.dp(context, (context.resources.configuration.screenHeightDp - 220).coerceIn(140, 560))))
    val dialog = MiuixDialog.Builder(context)
        .setTitle(title)
        .setView(panel)
        .setNegativeButton("取消", null)
        .setNeutralButton("撤销", null)
        .setPositiveButton("保存标注") { _, _ -> save(surface.flatten()) }
        .show()
    val undo = dialog.getButton(MiuixDialog.NEUTRAL)
    val done = dialog.getButton(MiuixDialog.POSITIVE)
    surface.onChange = { undo.isEnabled = surface.canUndo; done.isEnabled = surface.canUndo }
    undo.setOnClickListener { surface.undo() }
    surface.onChange()
    return dialog
}

internal fun decodeMarkupBitmap(file: java.io.File): Bitmap =
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
        require(!info.isAnimated) { "动图无法标注" }
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        // Retain full-size screenshots unless decoding would consume excessive memory.
        val largest = maxOf(info.size.width, info.size.height)
        if (largest > 3000) {
            val ratio = 3000f / largest
            decoder.setTargetSize((info.size.width * ratio).toInt().coerceAtLeast(1), (info.size.height * ratio).toInt().coerceAtLeast(1))
        }
    }

internal class MarkupSurface(context: Context, private val bitmap: Bitmap) : View(context) {
    private data class Stroke(val path: Path, val x: Float, val y: Float, var moved: Boolean = false)

    private val imageRect = RectF()
    private val strokes = mutableListOf<Stroke>()
    private var active: Stroke? = null
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val strokeWidth = (min(bitmap.width, bitmap.height) * .009f).coerceAtLeast(2f)
    var onChange: () -> Unit = {}
    val canUndo: Boolean get() = strokes.isNotEmpty()

    init { contentDescription = "在图片上绘制标注" }

    fun undo() {
        if (strokes.isNotEmpty()) strokes.removeAt(strokes.lastIndex)
        invalidate()
        onChange()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val scale = min(w.toFloat() / bitmap.width, h.toFloat() / bitmap.height)
        val width = bitmap.width * scale
        val height = bitmap.height * scale
        imageRect.set((w - width) / 2, (h - height) / 2, (w + width) / 2, (h + height) / 2)
    }

    override fun onDraw(canvas: Canvas) {
        val saved = canvas.save()
        canvas.clipRect(imageRect)
        canvas.translate(imageRect.left, imageRect.top)
        canvas.scale(imageRect.width() / bitmap.width, imageRect.height() / bitmap.height)
        drawImage(canvas)
        canvas.restoreToCount(saved)
    }

    fun flatten(): Bitmap = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888).also { drawImage(Canvas(it)) }

    private fun drawImage(canvas: Canvas) {
        canvas.drawBitmap(bitmap, null, RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat()), bitmapPaint)
        for (stroke in strokes) {
            drawStroke(canvas, stroke, Color.WHITE, strokeWidth * 1.65f)
            drawStroke(canvas, stroke, 0xFFE12632.toInt(), strokeWidth)
        }
    }

    private fun drawStroke(canvas: Canvas, stroke: Stroke, color: Int, width: Float) {
        ink.color = color
        ink.strokeWidth = width
        ink.style = if (stroke.moved) Paint.Style.STROKE else Paint.Style.FILL
        if (stroke.moved) canvas.drawPath(stroke.path, ink) else canvas.drawCircle(stroke.x, stroke.y, width / 2, ink)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (imageRect.isEmpty) return false
        val x = ((event.x - imageRect.left) * bitmap.width / imageRect.width()).coerceIn(0f, bitmap.width.toFloat())
        val y = ((event.y - imageRect.top) * bitmap.height / imageRect.height()).coerceIn(0f, bitmap.height.toFloat())
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!imageRect.contains(event.x, event.y)) return false
                active = Stroke(Path().apply { moveTo(x, y) }, x, y).also(strokes::add)
            }
            MotionEvent.ACTION_MOVE -> active?.let { it.path.lineTo(x, y); it.moved = true }
            MotionEvent.ACTION_UP -> {
                active = null
                performClick()
            }
            MotionEvent.ACTION_CANCEL -> {
                active?.let(strokes::remove)
                active = null
            }
        }
        invalidate()
        onChange()
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}