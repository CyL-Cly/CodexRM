package com.codexrm.mobile

import android.content.Context
import android.graphics.Canvas
import android.view.View
import android.widget.ListView

/** Keep the reader on the same message and text across transcript updates and reflow. */
internal class ChatListView(context: Context) : ListView(context) {
    data class Anchor(val id: String, val rowInset: Int, val textOffset: Int?, val lineInset: Int, val groupHeader: Boolean = false)
    data class ReadingPosition(val anchor: Anchor?) // A null anchor means the reader was at the end.
    private data class PlacedAnchor(val visible: Anchor, val reading: Anchor)

    private var laidOutWidth = 0
    private var laidOutHeight = 0
    private var placedAnchor: PlacedAnchor? = null
    private var lastDrawnAnchor: Anchor? = null
    private var pendingPosition: ReadingPosition? = null

    fun readingPosition(): ReadingPosition? {
        pendingPosition?.let { return it }
        val messages = adapter as? MessageAdapter ?: return null
        val last = getChildAt(childCount - 1) ?: return null
        if (lastVisiblePosition == count - 1 && last.bottom <= height - listPaddingBottom) {
            return ReadingPosition(null)
        }
        val anchor = capture(messages) ?: return null
        return ReadingPosition(placedAnchor?.takeIf { it.visible == anchor }?.reading ?: anchor)
    }

    fun restoreReadingPosition(position: ReadingPosition?) {
        pendingPosition = position
        if (position != null) requestLayout()
    }

    fun scrollToMessage(position: Int) {
        restoreReadingPosition(null)
        setSelectionFromTop(position, 0)
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        lastDrawnAnchor = (adapter as? MessageAdapter)?.let(::capture)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val adapter = adapter as? MessageAdapter
        val resized = laidOutWidth > 0 && laidOutWidth != right - left && adapter != null
        val last = if (resized) getChildAt(childCount - 1) else null
        val followEnd = last != null && lastVisiblePosition == count - 1 &&
            last.bottom <= laidOutHeight - listPaddingBottom
        val current = if (resized && !followEnd) capture(adapter) else null
        // Configuration changes can invalidate the TextView's line layout before ListView is measured.
        val visible = if (current != null && current.textOffset == null) {
            lastDrawnAnchor?.takeIf { it.id == current.id && it.rowInset == current.rowInset } ?: current
        } else current
        // The wider line may start before the original character. Retain that character for
        // the return rotation as long as the visible text has not moved in the meantime.
        val pending = pendingPosition
        pendingPosition = null
        val anchor = if (pending != null) pending.anchor else placedAnchor?.takeIf { it.visible == visible }?.reading ?: visible
        if (resized || pending != null) smoothScrollBy(0, 0)

        super.onLayout(changed, left, top, right, bottom)
        laidOutWidth = right - left
        laidOutHeight = bottom - top
        if ((!resized && pending == null) || adapter == null) return
        placedAnchor = null
        val keepEnd = if (pending != null) pending.anchor == null else followEnd
        if (keepEnd && adapter.count > 0) {
            val position = adapter.count - 1
            val row = layoutRow(position) ?: return
            place(position, height - listPaddingTop - listPaddingBottom - row.height)
        } else if (anchor != null) {
            // A tool at the page boundary can become the header of a newly formed group.
            val position = adapter.positionOf(anchor.id, anchor.groupHeader).takeIf { it >= 0 }
                ?: adapter.positionOf(anchor.id)
            if (position < 0) return
            val row = layoutRow(position) ?: return
            val inset = resolveInset(adapter, row, anchor)
            place(position, -inset)
            placedAnchor = capture(adapter)?.takeIf { it.id == anchor.id && it.groupHeader == anchor.groupHeader }?.let { PlacedAnchor(it, anchor) }
        }
    }

    private fun capture(adapter: MessageAdapter): Anchor? {
        // With clipToPadding=false, a row can still be attached above the reading area.
        val index = (0 until childCount).firstOrNull { getChildAt(it).bottom > listPaddingTop } ?: return null
        val row = getChildAt(index)
        val message = adapter.boundMessage(row) ?: return null
        val groupHeader = adapter.boundGroupHeader(row)
        val rowInset = listPaddingTop - row.top
        val body = adapter.messageBody(row)?.takeIf { it.visibility == View.VISIBLE }
        val layout = body?.layout
        if (layout != null) {
            val textInset = rowInset - body.top - body.totalPaddingTop + body.scrollY
            if (textInset in 0 until layout.height) {
                val line = layout.getLineForVertical(textInset)
                return Anchor(message.id, rowInset, layout.getLineStart(line), textInset - layout.getLineTop(line), groupHeader)
            }
        }
        return Anchor(message.id, rowInset, null, 0, groupHeader)
    }

    private fun resolveInset(adapter: MessageAdapter, row: View, anchor: Anchor): Int {
        val body = adapter.messageBody(row)?.takeIf { it.visibility == View.VISIBLE }
        val layout = body?.layout
        if (layout != null && anchor.textOffset != null) {
            val line = layout.getLineForOffset(anchor.textOffset.coerceAtMost(layout.text.length))
            val lineTop = layout.getLineTop(line)
            val lineHeight = layout.getLineBottom(line) - lineTop
            return body.top + body.totalPaddingTop - body.scrollY + lineTop +
                anchor.lineInset.coerceAtMost((lineHeight - 1).coerceAtLeast(0))
        }
        return anchor.rowInset.coerceAtMost((row.height - 1).coerceAtLeast(0))
    }

    private fun layoutRow(position: Int): View? {
        rowAt(position)?.let { return it }
        place(position, 0)
        return rowAt(position)
    }

    private fun rowAt(position: Int): View? = getChildAt(position - firstVisiblePosition)

    private fun place(position: Int, offset: Int) {
        setSelectionFromTop(position, offset)
        layoutChildren()
    }
}
