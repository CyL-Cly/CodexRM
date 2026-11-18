package com.codexrm.mobile

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.max

internal class SidebarSwipeLayout(
    context: Context,
    private val canOpenSidebar: () -> Boolean,
    private val openSidebar: () -> Unit,
) : FrameLayout(context) {
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val swipeDistance = max(Ui.dp(context, 64), touchSlop * 4)
    private var startX = 0f
    private var startY = 0f
    private var tracking = false
    private var consuming = false

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                startY = event.y
                consuming = false
                tracking = canOpenSidebar() && !blocksSwipe(this, startX, startY)
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> tracking = false
        }

        if (tracking && (event.actionMasked == MotionEvent.ACTION_MOVE || event.actionMasked == MotionEvent.ACTION_UP)) {
            val dx = event.x - startX
            val dy = abs(event.y - startY)
            if (event.pointerCount != 1 || !canOpenSidebar() ||
                event.eventTime - event.downTime >= ViewConfiguration.getLongPressTimeout() ||
                dx < -touchSlop || (dy > touchSlop && dy >= abs(dx))) {
                tracking = false
            } else if (dx >= swipeDistance && dx >= dy * 1.5f) {
                tracking = false
                consuming = true
                // Cancel the original target so a swipe cannot also click a link or button.
                val cancel = MotionEvent.obtain(event)
                cancel.action = MotionEvent.ACTION_CANCEL
                super.dispatchTouchEvent(cancel)
                cancel.recycle()
                openSidebar()
            }
        }

        if (consuming) {
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) consuming = false
            return true
        }
        val handled = super.dispatchTouchEvent(event)
        val wasTracking = tracking
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) tracking = false
        return handled || wasTracking
    }

    private fun blocksSwipe(view: View, x: Float, y: Float): Boolean {
        if (view is MathTextView && view.scrollableFormulaAt(x, y) != null) return true
        if (view is EditText || view is HorizontalScrollView || (view is TextView && view.hasSelection())) return true
        if (view is ViewGroup) {
            for (index in view.childCount - 1 downTo 0) {
                val child = view.getChildAt(index)
                if (child.visibility != View.VISIBLE) continue
                val childX = x + view.scrollX - child.x
                val childY = y + view.scrollY - child.y
                if (childX >= 0 && childX < child.width && childY >= 0 && childY < child.height) {
                    return blocksSwipe(child, childX, childY)
                }
            }
        }
        return false
    }
}
