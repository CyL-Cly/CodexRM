package com.codexrm.mobile

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.image.AsyncDrawable
import io.noties.markwon.image.AsyncDrawableSpan
import io.noties.markwon.utils.SpanUtils
import ru.noties.jlatexmath.JLatexMathDrawable
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Keep a formula's TeX baseline aligned with surrounding text, including extra line spacing. */
internal class InlineMathSpan(theme: MarkwonTheme, drawable: AsyncDrawable) :
    AsyncDrawableSpan(theme, drawable, ALIGN_BASELINE, false), HorizontalOverflow {
    override val viewport = RectF()
    private var offset = 0f
    override val scrollRange: Float get() = (drawable.bounds.width() - viewport.width()).coerceAtLeast(0f)

    override fun panBy(distance: Float) { offset = (offset + distance).coerceIn(0f, scrollRange) }

    override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        if (!drawable.hasResult()) return super.getSize(paint, text, start, end, fm)
        val height = drawable.bounds.height()
        val baseline = baseline(height)
        if (fm != null) {
            val metrics = paint.fontMetricsInt
            fm.ascent = min(metrics.ascent, -ceil(baseline).toInt())
            fm.descent = max(metrics.descent, ceil(height - baseline).toInt())
            fm.top = min(metrics.top, fm.ascent)
            fm.bottom = max(metrics.bottom, fm.descent)
        }
        val width = drawable.bounds.width()
        return min(width, contentWidth(text).takeIf { it > 0 } ?: width)
    }

    override fun draw(canvas: Canvas, text: CharSequence, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
        drawable.initWithKnownDimensions(SpanUtils.width(canvas, text), paint.textSize)
        if (!drawable.hasResult()) {
            super.draw(canvas, text, start, end, x, top, y, bottom, paint)
            return
        }
        val bounds = drawable.bounds
        val hostWidth = contentWidth(text)
        val width = if (hostWidth > 0) min(bounds.width().toFloat(), (hostWidth - x).coerceAtLeast(1f)) else bounds.width().toFloat()
        val formulaTop = y - baseline(bounds.height())
        viewport.set(x, formulaTop, x + width, formulaTop + bounds.height())
        panBy(0f)
        val saved = canvas.save()
        try {
            canvas.clipRect(viewport)
            canvas.translate(x - offset, formulaTop)
            drawable.draw(canvas)
        } finally {
            canvas.restoreToCount(saved)
        }
    }

    private fun baseline(height: Int): Float =
        height * ((drawable.result as? JLatexMathDrawable)?.icon()?.baseLine ?: 1f)
}
