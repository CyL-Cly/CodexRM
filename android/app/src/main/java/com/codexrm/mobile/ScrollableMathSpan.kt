package com.codexrm.mobile

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.image.AsyncDrawable
import io.noties.markwon.image.AsyncDrawableSpan
import io.noties.markwon.utils.SpanUtils
import kotlin.math.min

/** Center a display formula when it fits and pan it at natural size when it overflows. */
internal class ScrollableMathSpan(theme: MarkwonTheme, drawable: AsyncDrawable) :
    AsyncDrawableSpan(theme, drawable, ALIGN_BASELINE, false), HorizontalOverflow {
    override val viewport = RectF()
    var offset = 0f
        private set
    override val scrollRange: Float get() = (drawable.bounds.width() - viewport.width()).coerceAtLeast(0f)
    private val indicator = Paint(Paint.ANTI_ALIAS_FLAG)

    fun restoreOffset(value: Float) { offset = value }

    override fun panBy(distance: Float) { offset = (offset + distance).coerceIn(0f, scrollRange) }

    override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        if (!drawable.hasResult()) return super.getSize(paint, text, start, end, fm)
        val width = contentWidth(text).takeIf { it > 0 } ?: drawable.bounds.width()
        val extra = if (drawable.bounds.width() > width) (paint.textSize * .3f).toInt() else 0
        fm?.apply {
            ascent = -drawable.bounds.height()
            descent = extra
            top = ascent
            bottom = descent
        }
        return min(drawable.bounds.width(), width)
    }

    override fun draw(canvas: Canvas, text: CharSequence, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
        drawable.initWithKnownDimensions(SpanUtils.width(canvas, text), paint.textSize)
        if (!drawable.hasResult()) {
            viewport.setEmpty()
            super.draw(canvas, text, start, end, x, top, y, bottom, paint)
            return
        }
        val available = (SpanUtils.width(canvas, text) - x).coerceAtLeast(1f)
        val width = min(drawable.bounds.width().toFloat(), available)
        val left = x + (available - width) / 2f
        val height = drawable.bounds.height()
        val extra = if (drawable.bounds.width() > width) paint.textSize * .3f else 0f
        viewport.set(left, y - height.toFloat(), left + width, y + extra)
        panBy(0f)
        val saved = canvas.save()
        try {
            canvas.clipRect(viewport)
            canvas.translate(viewport.left - offset, viewport.top)
            drawable.draw(canvas)
        } finally {
            canvas.restoreToCount(saved)
        }
        if (scrollRange > 0) {
            val thumbWidth = (width * width / drawable.bounds.width()).coerceAtLeast(paint.textSize)
            val thumbX = viewport.left + (width - thumbWidth) * offset / scrollRange
            indicator.color = Ui.BORDER
            canvas.drawRect(viewport.left, y + extra * .45f, viewport.right, y + extra * .7f, indicator)
            indicator.color = Ui.MUTED
            canvas.drawRoundRect(thumbX, y + extra * .35f, thumbX + thumbWidth, y + extra * .8f, extra, extra, indicator)
        }
    }
}
