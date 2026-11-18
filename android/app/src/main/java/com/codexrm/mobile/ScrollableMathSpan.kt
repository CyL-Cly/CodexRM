package com.codexrm.mobile

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.Spanned
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.core.spans.TextViewSpan
import io.noties.markwon.image.AsyncDrawable
import io.noties.markwon.image.AsyncDrawableSpan
import io.noties.markwon.utils.SpanUtils
import kotlin.math.min

/** A formula keeps its natural size inside its own horizontally pannable viewport. */
internal class ScrollableMathSpan(theme: MarkwonTheme, drawable: AsyncDrawable) :
    AsyncDrawableSpan(theme, drawable, ALIGN_BASELINE, false) {
    val viewport = RectF()
    var offset = 0f
        private set
    val scrollRange: Float get() = (drawable.bounds.width() - viewport.width()).coerceAtLeast(0f)
    private val indicator = Paint(Paint.ANTI_ALIAS_FLAG)

    fun panBy(distance: Float) { offset = (offset + distance).coerceIn(0f, scrollRange) }

    private fun availableWidth(text: CharSequence): Int {
        val view = (text as? Spanned)?.let { TextViewSpan.textViewOf(it) }
        return if (view != null && view.width > 0) {
            view.width - view.totalPaddingLeft - view.totalPaddingRight
        } else drawable.lastKnownCanvasWidth.coerceAtLeast(1)
    }

    override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        if (!drawable.hasResult()) return super.getSize(paint, text, start, end, fm)
        val width = availableWidth(text)
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
        val width = min(drawable.bounds.width().toFloat(), (SpanUtils.width(canvas, text) - x).coerceAtLeast(1f))
        val height = drawable.bounds.height()
        val extra = if (drawable.bounds.width() > width) paint.textSize * .3f else 0f
        viewport.set(x, y - height.toFloat(), x + width, y + extra)
        panBy(0f)
        val saved = canvas.save()
        try {
            canvas.clipRect(viewport)
            canvas.translate(x - offset, y - height.toFloat())
            drawable.draw(canvas)
        } finally {
            canvas.restoreToCount(saved)
        }
        if (scrollRange > 0) {
            val thumbWidth = (width * width / drawable.bounds.width()).coerceAtLeast(paint.textSize)
            val thumbX = x + (width - thumbWidth) * offset / scrollRange
            indicator.color = Ui.BORDER
            canvas.drawRect(x, y + extra * .45f, x + width, y + extra * .7f, indicator)
            indicator.color = Ui.MUTED
            canvas.drawRoundRect(thumbX, y + extra * .35f, thumbX + thumbWidth, y + extra * .8f, extra, extra, indicator)
        }
    }
}
