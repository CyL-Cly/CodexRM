package com.codexrm.mobile

import android.content.Context
import android.text.Spanned
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.TextView
import kotlin.math.abs

/** Capture horizontal drags only when they start on an overflowing display formula. */
internal class MathTextView(context: Context) : TextView(context) {
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var formula: ScrollableMathSpan? = null
    private var startX = 0f
    private var startY = 0f
    private var lastX = 0f
    private var dragging = false

    fun scrollableFormulaAt(x: Float, y: Float): ScrollableMathSpan? {
        val content = text as? Spanned ?: return null
        return content.getSpans(0, content.length, ScrollableMathSpan::class.java).firstOrNull {
            it.scrollRange > 0 && it.viewport.contains(x - totalPaddingLeft + scrollX, y - totalPaddingTop + scrollY)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                formula = scrollableFormulaAt(event.x, event.y)
                startX = event.x; lastX = event.x; startY = event.y; dragging = false
                if (formula != null) parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                val target = formula
                if (target != null) {
                    val dx = event.x - startX
                    val dy = event.y - startY
                    if (!dragging && abs(dy) > slop && abs(dy) >= abs(dx)) {
                        formula = null
                        parent?.requestDisallowInterceptTouchEvent(false)
                    } else if (dragging || (abs(dx) > slop && abs(dx) > abs(dy))) {
                        if (!dragging) {
                            val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                            super.onTouchEvent(cancel)
                            cancel.recycle()
                        }
                        target.panBy(lastX - event.x)
                        lastX = event.x
                        dragging = true
                        invalidate()
                        return true
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val handled = dragging
                formula = null; dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                if (handled) return true
            }
        }
        return super.onTouchEvent(event)
    }
}
