package com.codexrm.mobile

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import kotlin.math.min

internal class IconView(context: Context, private var icon: String, color: Int = Ui.MUTED) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color; style = Paint.Style.STROKE; strokeWidth = 1.65f
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()

    fun setColor(color: Int) { paint.color = color; invalidate() }
    fun setIcon(value: String) { if (icon != value) { icon = value; invalidate() } }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val scale = min(width, height) / 24f
        canvas.save()
        canvas.translate((width - 24 * scale) / 2, (height - 24 * scale) / 2)
        canvas.scale(scale, scale)
        paint.strokeWidth = 1.65f
        path.rewind()
        when (icon) {
            "terminal" -> { move(5,6); line(11,12); line(5,18); move(14,18); line(19,18) }
            "edit" -> { move(12,20); line(4,20); line(4,12); move(14,4); line(20,10); move(8,16); line(10,10); line(18,2); line(22,6); line(14,14); close() }
            "search" -> { circle(11,11,7); move(16,16); line(21,21) }
            "plus" -> { move(12,5); line(12,19); move(5,12); line(19,12) }
            "refresh" -> { move(20,7); line(20,12); line(15,12); move(4,17); line(4,12); line(9,12); move(6,6); cubic(9,2,16,3,19,8); move(18,18); cubic(15,22,8,21,5,16) }
            "update" -> { move(12,3); line(12,15); move(7,10); line(12,15); line(17,10); move(4,20); line(20,20) }
            "monitor" -> { rect(3,4,18,13,1); move(8,21); line(16,21); move(12,17); line(12,21) }
            "zap" -> { move(13,2); line(3,14); line(11,14); line(10,22); line(21,10); line(13,10); close() }
            "relay" -> { rect(9,3,6,6,1); rect(3,15,6,6,1); rect(15,15,6,6,1); move(12,9); line(12,12); move(6,15); line(6,12); line(18,12); line(18,15) }
            "settings" -> { circle(12,12,3); move(9,3); line(15,3); line(16,6); line(19,7); line(21,12); line(19,17); line(16,18); line(15,21); line(9,21); line(8,18); line(5,17); line(3,12); line(5,7); line(8,6); close() }
            "close" -> { move(6,6); line(18,18); move(6,18); line(18,6) }
            "panel" -> { rect(3,4,18,16,2); move(9,4); line(9,20); move(6,8); line(7,9); move(6,12); line(7,13) }
            "sun" -> { circle(12,12,4); move(12,2); line(12,4); move(12,20); line(12,22); move(2,12); line(4,12); move(20,12); line(22,12); move(5,5); line(6,6); move(18,18); line(19,19); move(19,5); line(18,6); move(6,18); line(5,19) }
            "moon" -> { move(20,15); cubic(16,21,8,18,8,10); cubic(8,7,10,4,14,3); cubic(11,10,15,15,20,15); close() }
            "chevron" -> { move(8,10); line(12,14); line(16,10) }
            "check" -> { move(5,12); line(10,17); line(19,7) }
            "right" -> { move(9,6); line(15,12); line(9,18) }
            "more" -> { circle(5,12,.65f); circle(12,12,.65f); circle(19,12,.65f) }
            "folder" -> { move(3,6); line(10,6); line(12,8); line(21,8); line(21,20); line(3,20); close() }
            "file" -> { move(6,3); line(14,3); line(19,8); line(19,21); line(6,21); close(); move(14,3); line(14,8); line(19,8); move(9,13); line(16,13); move(9,17); line(15,17) }
            "arrow" -> { move(12,20); line(12,4); move(6,10); line(12,4); line(18,10) }
            "arrow-right" -> { move(4,12); line(20,12); move(14,6); line(20,12); line(14,18) }
            "stop" -> { rect(7,7,10,10,1) }
            "lock" -> { rect(6,10,12,11,1); move(8,10); line(8,7); cubic(8,2,16,2,16,7); line(16,10) }
            "copy" -> { rect(8,8,12,12,2); move(16,8); line(16,4); line(4,4); line(4,16); line(8,16) }
            "attach" -> { move(8,13); line(15,6); cubic(19,2,23,7,19,11); line(10,20); cubic(4,25,-1,17,4,12); line(13,3) }
            else -> { move(8,6); line(2,12); line(8,18); move(16,6); line(22,12); line(16,18); move(14,3); line(10,21) }
        }
        canvas.drawPath(path, paint)
        canvas.restore()
    }

    private fun move(x: Number, y: Number) = path.moveTo(x.toFloat(), y.toFloat())
    private fun line(x: Number, y: Number) = path.lineTo(x.toFloat(), y.toFloat())
    private fun cubic(x1: Number, y1: Number, x2: Number, y2: Number, x: Number, y: Number) = path.cubicTo(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat(), x.toFloat(), y.toFloat())
    private fun close() = path.close()
    private fun circle(x: Number, y: Number, r: Number) {
        val cx = x.toFloat(); val cy = y.toFloat(); val radius = r.toFloat()
        path.addCircle(cx, cy, radius, Path.Direction.CW); path.moveTo(cx + radius, cy)
    }
    private fun rect(x: Int, y: Int, w: Int, h: Int, r: Int) = path.addRoundRect(x.toFloat(), y.toFloat(), (x+w).toFloat(), (y+h).toFloat(), r.toFloat(), r.toFloat(), Path.Direction.CW)
}
