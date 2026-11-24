package com.codexrm.mobile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.roundToInt

internal class WallpaperCropView(
    context: Context,
    private val image: Bitmap,
    private val targetAspect: () -> Float,
) : View(context) {
    private val frame = RectF()
    private val imageBounds = RectF()
    private val photoPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val shadePaint = Paint().apply { color = 0xb8000000.toInt() }
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = Ui.dp(context, 2).toFloat()
    }
    private var centerX = image.width / 2f
    private var centerY = image.height / 2f
    private var zoom = 1f
    private var scalingSequence = false

    private val pinch = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            zoomAt(zoom * detector.scaleFactor, detector.focusX, detector.focusY)
            return true
        }
    }).apply { isQuickScaleEnabled = false }

    private val drag = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(event: MotionEvent) = true

        override fun onScroll(first: MotionEvent?, event: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (!scalingSequence) {
                val scale = fitScale() * zoom
                if (scale > 0f) moveBy(distanceX / scale, distanceY / scale)
            }
            return true
        }
    })

    init {
        contentDescription = "背景裁剪选区"
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(0xff171717.toInt())
        updateFrame()
        if (frame.isEmpty) return
        val scale = fitScale() * zoom
        imageBounds.set(
            frame.centerX() - centerX * scale, frame.centerY() - centerY * scale,
            frame.centerX() + (image.width - centerX) * scale, frame.centerY() + (image.height - centerY) * scale,
        )
        canvas.drawBitmap(image, null, imageBounds, photoPaint)
        canvas.drawRect(0f, 0f, width.toFloat(), frame.top, shadePaint)
        canvas.drawRect(0f, frame.bottom, width.toFloat(), height.toFloat(), shadePaint)
        canvas.drawRect(0f, frame.top, frame.left, frame.bottom, shadePaint)
        canvas.drawRect(frame.right, frame.top, width.toFloat(), frame.bottom, shadePaint)
        canvas.drawRect(frame, framePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) scalingSequence = false
        if (event.pointerCount > 1) scalingSequence = true
        pinch.onTouchEvent(event)
        drag.onTouchEvent(event)
        return true
    }

    fun reset() {
        zoom = 1f
        centerX = image.width / 2f
        centerY = image.height / 2f
        invalidate()
    }

    fun zoomBy(factor: Float) {
        updateFrame()
        zoomAt(zoom * factor, frame.centerX(), frame.centerY())
    }

    fun selectedRegion(): RectF? {
        updateFrame()
        if (frame.isEmpty) return null
        val scale = fitScale() * zoom
        val halfWidth = frame.width() / (2 * scale)
        val halfHeight = frame.height() / (2 * scale)
        return RectF(centerX - halfWidth, centerY - halfHeight, centerX + halfWidth, centerY + halfHeight)
    }

    private fun updateFrame() {
        val margin = Ui.dp(context, 22)
        val availableWidth = (width - 2 * margin).toFloat()
        val availableHeight = (height - 2 * margin).toFloat()
        if (availableWidth <= 0 || availableHeight <= 0) {
            frame.setEmpty()
            return
        }
        val aspect = targetAspect().takeIf { it.isFinite() && it > 0f } ?: availableWidth / availableHeight
        val frameWidth = minOf(availableWidth, availableHeight * aspect)
        val frameHeight = frameWidth / aspect
        frame.set((width - frameWidth) / 2, (height - frameHeight) / 2, (width + frameWidth) / 2, (height + frameHeight) / 2)
        constrainCenter()
    }

    private fun fitScale(): Float = maxOf(frame.width() / image.width, frame.height() / image.height)

    private fun moveBy(dx: Float, dy: Float) {
        centerX += dx
        centerY += dy
        constrainCenter()
        invalidate()
    }

    private fun zoomAt(target: Float, focusX: Float, focusY: Float) {
        val base = fitScale()
        if (base <= 0f) return
        val next = target.coerceIn(1f, 6f)
        val anchorRatio = (1f / zoom - 1f / next) / base
        zoom = next
        moveBy((focusX - frame.centerX()) * anchorRatio, (focusY - frame.centerY()) * anchorRatio)
    }

    private fun constrainCenter() {
        val scale = fitScale() * zoom
        if (scale <= 0f) return
        val halfWidth = (frame.width() / (2 * scale)).coerceAtMost(image.width / 2f)
        val halfHeight = (frame.height() / (2 * scale)).coerceAtMost(image.height / 2f)
        centerX = centerX.coerceIn(halfWidth, image.width - halfWidth)
        centerY = centerY.coerceIn(halfHeight, image.height - halfHeight)
    }
}

internal fun cropWallpaper(image: Bitmap, region: RectF): Bitmap {
    val scale = minOf(1f, 1920f / maxOf(region.width(), region.height()))
    val width = (region.width() * scale).roundToInt().coerceAtLeast(1)
    val height = (region.height() * scale).roundToInt().coerceAtLeast(1)
    val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val scaleX = width / region.width()
    val scaleY = height / region.height()
    val bounds = RectF(-region.left * scaleX, -region.top * scaleY,
        (image.width - region.left) * scaleX, (image.height - region.top) * scaleY)
    Canvas(output).drawBitmap(image, null, bounds, Paint(Paint.FILTER_BITMAP_FLAG))
    return output
}
