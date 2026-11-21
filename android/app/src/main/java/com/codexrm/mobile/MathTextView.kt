package com.codexrm.mobile

import android.content.Context
import android.text.NoCopySpan
import android.text.Spannable
import android.text.Spanned
import android.text.style.UpdateAppearance
import android.widget.TextView

/** Maps touch coordinates to the drawn viewport of an overflowing span. */
internal class MathTextView(context: Context) : TextView(context) {
    private val overflowRedraw = object : UpdateAppearance, NoCopySpan {}

    var formulaWidth = 0
        private set

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        formulaWidth = MeasureSpec.getSize(widthMeasureSpec) - totalPaddingLeft - totalPaddingRight
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    fun horizontalOverflowAt(x: Float, y: Float): HorizontalOverflow? {
        val content = text as? Spanned ?: return null
        val layoutX = x - totalPaddingLeft + scrollX
        val layoutY = y - totalPaddingTop + scrollY
        return content.getSpans(0, content.length, HorizontalOverflow::class.java).firstOrNull {
            it.scrollRange > 0 && it.viewport.contains(layoutX, layoutY)
        }
    }

    fun panOverflow(span: HorizontalOverflow, distance: Float) {
        if (distance == 0f) return
        val content = text as? Spannable
        val start = content?.getSpanStart(span) ?: -1
        span.panBy(distance)
        if (content == null || start < 0) {
            invalidate()
            return
        }
        var redrawStart = start
        var redrawEnd = content.getSpanEnd(span)
        if (span is MathTableRow) {
            // Table rows share the same offset, so invalidate all rows in this table.
            for (row in content.getSpans(0, content.length, MathTableRow::class.java)) {
                if (row.table === span.table) {
                    redrawStart = minOf(redrawStart, content.getSpanStart(row))
                    redrawEnd = maxOf(redrawEnd, content.getSpanEnd(row))
                }
            }
        }
        // TextView caches hardware display lists for selectable text. Notify its span watcher
        // so the affected lines redraw without triggering text layout or clearing selection.
        content.setSpan(overflowRedraw, redrawStart, redrawEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    fun containsOverflow(span: HorizontalOverflow): Boolean = ((text as? Spanned)?.getSpanStart(span) ?: -1) >= 0
}
