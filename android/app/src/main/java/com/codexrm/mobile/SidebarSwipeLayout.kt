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
    private val startSidebarDrag: () -> Float,
    private val moveSidebar: (Float) -> Unit,
    private val finishSidebarDrag: (Boolean) -> Unit,
) : FrameLayout(context) {
    private enum class Swipe { IDLE, TRACKING, DRAGGING, PANNING, CONSUMING }

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val openDistance = max(Ui.dp(context, 64), touchSlop * 4)
    private var startX = 0f
    private var startY = 0f
    private var startTranslationX = 0f
    private var swipe = Swipe.IDLE
    private var overflowView: MathTextView? = null
    private var overflow: HorizontalOverflow? = null
    private var lastX = 0f

    fun stopSidebarSwipe() {
        when (swipe) {
            Swipe.TRACKING -> swipe = Swipe.IDLE
            Swipe.DRAGGING -> swipe = Swipe.CONSUMING
            else -> Unit
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked
        if (action == MotionEvent.ACTION_DOWN) {
            if (swipe == Swipe.DRAGGING) finishSidebarDrag(false)
            startX = event.x
            startY = event.y
            lastX = startX
            val blocker = swipeBlocker(this, startX, startY)
            overflow = blocker?.span
            overflowView = if (overflow != null) blocker?.view as? MathTextView else null
            swipe = if (canOpenSidebar() && blocker == null) Swipe.TRACKING else Swipe.IDLE
        }

        val dx = event.x - startX
        val dy = abs(event.y - startY)
        val target = overflow
        if (target != null && swipe != Swipe.PANNING) {
            when (action) {
                MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> clearOverflow()
                MotionEvent.ACTION_MOVE -> {
                    if (event.eventTime - event.downTime >= ViewConfiguration.getLongPressTimeout() ||
                        (dy > touchSlop && dy >= abs(dx)) || overflowView?.containsOverflow(target) != true) {
                        clearOverflow()
                    } else if (abs(dx) > touchSlop && abs(dx) > dy) {
                        // ListView can consume DOWN during a fling and never dispatch it to the TextView.
                        cancelChildTouch(event)
                        swipe = Swipe.PANNING
                    }
                }
            }
        }
        if (swipe == Swipe.TRACKING) {
            when (action) {
                MotionEvent.ACTION_POINTER_DOWN -> swipe = Swipe.IDLE
                MotionEvent.ACTION_MOVE -> {
                    if (event.pointerCount != 1 || !canOpenSidebar() ||
                        event.eventTime - event.downTime >= ViewConfiguration.getLongPressTimeout() ||
                        dx < -touchSlop || (dy > touchSlop && dy >= abs(dx))) {
                        swipe = Swipe.IDLE
                    } else if (dx > touchSlop && dx >= dy * 1.5f) {
                        // Cancel the original target so the drag cannot also click a link or button.
                        cancelChildTouch(event)
                        startTranslationX = startSidebarDrag()
                        swipe = Swipe.DRAGGING
                    }
                }
            }
        }

        when (swipe) {
            Swipe.PANNING -> {
                when (action) {
                    MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                        val view = overflowView
                        if (view != null && target != null && view.containsOverflow(target)) {
                            view.panOverflow(target, lastX - event.x)
                            lastX = event.x
                        }
                    }
                }
                when (action) {
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> {
                        clearOverflow()
                        swipe = if (action == MotionEvent.ACTION_POINTER_DOWN) Swipe.CONSUMING else Swipe.IDLE
                    }
                }
                return true
            }
            Swipe.DRAGGING -> {
                when (action) {
                    MotionEvent.ACTION_MOVE -> moveSidebar(startTranslationX + dx - touchSlop)
                    MotionEvent.ACTION_UP -> {
                        moveSidebar(startTranslationX + dx - touchSlop)
                        swipe = Swipe.IDLE
                        finishSidebarDrag(dx >= openDistance)
                    }
                    MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> {
                        swipe = if (action == MotionEvent.ACTION_CANCEL) Swipe.IDLE else Swipe.CONSUMING
                        finishSidebarDrag(false)
                    }
                }
                return true
            }
            Swipe.CONSUMING -> {
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) swipe = Swipe.IDLE
                return true
            }
            else -> {
                val handled = super.dispatchTouchEvent(event)
                val wasTracking = swipe == Swipe.TRACKING
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) swipe = Swipe.IDLE
                return handled || wasTracking || overflow != null
            }
        }
    }

    private fun cancelChildTouch(event: MotionEvent) {
        val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
        super.dispatchTouchEvent(cancel)
        cancel.recycle()
    }

    private fun clearOverflow() {
        overflow = null
        overflowView = null
    }

    private data class SwipeBlocker(val view: View, val span: HorizontalOverflow? = null)

    private fun swipeBlocker(view: View, x: Float, y: Float): SwipeBlocker? {
        if (view is EditText || view is HorizontalScrollView || (view is TextView && view.hasSelection())) return SwipeBlocker(view)
        if (view is MathTextView) view.horizontalOverflowAt(x, y)?.let { return SwipeBlocker(view, it) }
        if (view is ViewGroup) {
            for (index in view.childCount - 1 downTo 0) {
                val child = view.getChildAt(index)
                if (child.visibility != View.VISIBLE) continue
                val childX = x + view.scrollX - child.x
                val childY = y + view.scrollY - child.y
                if (childX >= 0 && childX < child.width && childY >= 0 && childY < child.height) {
                    return swipeBlocker(child, childX, childY)
                }
            }
        }
        return null
    }
}
