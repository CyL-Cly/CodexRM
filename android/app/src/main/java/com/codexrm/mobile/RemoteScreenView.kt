package com.codexrm.mobile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

internal enum class RemotePointing { TOUCH, POINTER, TABLET }

internal fun interface RemotePointerListener {
    fun onPointer(action: Int, id: Int, x: Int, y: Int, buttons: Int)
}

internal class RemoteScreenView(context: Context) : View(context) {
    var pointing = RemotePointing.TOUCH
    var viewOnly = false
    var virtualCursor = true
    var quarter = 0
        set(value) { field = value; invalidate() }
    var listener: RemotePointerListener? = null
    private var frame: Bitmap? = null
    private var cursorX = 32768
    private var cursorY = 32768
    private var anchorX = 0f
    private var anchorY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var moved = false
    private var tracking = false
    private var longFired = false
    private val slop = 12f * resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cursor = Path()
    private val longPress = Runnable {
        if (!tracking || moved || viewOnly) return@Runnable
        longFired = true
        if (pointing == RemotePointing.POINTER) {
            emitNorm(RemoteProtocol.DOWN.toInt(), 0, cursorX, cursorY, RemoteProtocol.RIGHT)
            emitNorm(RemoteProtocol.UP.toInt(), 0, cursorX, cursorY, 0)
        } else {
            emitAbsolute(RemoteProtocol.UP.toInt(), 0, anchorX, anchorY, 0, clamp = true)
            emitAbsolute(RemoteProtocol.DOWN.toInt(), 0, anchorX, anchorY, RemoteProtocol.RIGHT, clamp = true)
        }
    }

    fun setFrame(bitmap: Bitmap) {
        frame = bitmap
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        val bitmap = frame ?: return
        val turns = ((quarter % 4) + 4) % 4
        val fitted = RemoteTouch.scale(width, height, bitmap.width, bitmap.height, turns)
        val drawW = bitmap.width * fitted
        val drawH = bitmap.height * fitted
        canvas.save()
        canvas.translate(width / 2f, height / 2f)
        canvas.rotate(turns * 90f)
        canvas.drawBitmap(bitmap, null, RectF(-drawW / 2f, -drawH / 2f, drawW / 2f, drawH / 2f), paint)
        canvas.restore()
        if (!virtualCursor && pointing != RemotePointing.POINTER) return
        val ix = cursorX / 65535f * bitmap.width
        val iy = cursorY / 65535f * bitmap.height
        val point = RemoteTouch.imageToView(ix, iy, width, height, bitmap.width, bitmap.height, turns)
        cursor.rewind()
        cursor.moveTo(point.first, point.second)
        cursor.lineTo(point.first + 16f * resources.displayMetrics.density, point.second + 12f * resources.displayMetrics.density)
        cursor.lineTo(point.first + 6f * resources.displayMetrics.density, point.second + 12f * resources.displayMetrics.density)
        cursor.close()
        paint.style = Paint.Style.FILL
        paint.color = Color.BLACK
        canvas.drawPath(cursor, paint)
        canvas.save()
        canvas.translate(-1.5f, -1.5f)
        paint.color = Color.WHITE
        canvas.drawPath(cursor, paint)
        canvas.restore()
        paint.style = Paint.Style.FILL
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val bitmap = frame
        if (bitmap == null || viewOnly) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> begin(event, 0)
            MotionEvent.ACTION_POINTER_DOWN -> if (pointing == RemotePointing.TABLET) {
                val index = event.actionIndex
                emitAbsolute(RemoteProtocol.DOWN.toInt(), event.getPointerId(index), event.getX(index), event.getY(index), RemoteProtocol.LEFT, clamp = false)
            }
            MotionEvent.ACTION_MOVE -> move(event)
            MotionEvent.ACTION_UP -> finish(event, 0, cancel = false)
            MotionEvent.ACTION_POINTER_UP -> if (pointing == RemotePointing.TABLET) {
                val index = event.actionIndex
                emitAbsolute(RemoteProtocol.UP.toInt(), event.getPointerId(index), event.getX(index), event.getY(index), 0, clamp = true)
            }
            MotionEvent.ACTION_CANCEL -> finish(event, 0, cancel = true)
        }
        return true
    }

    private fun begin(event: MotionEvent, index: Int) {
        tracking = true
        moved = false
        longFired = false
        anchorX = event.getX(index)
        anchorY = event.getY(index)
        lastX = anchorX
        lastY = anchorY
        when (pointing) {
            RemotePointing.POINTER -> postDelayed(longPress, 480)
            RemotePointing.TOUCH -> {
                emitAbsolute(RemoteProtocol.DOWN.toInt(), 0, anchorX, anchorY, RemoteProtocol.LEFT, clamp = false)
                postDelayed(longPress, 480)
            }
            RemotePointing.TABLET -> emitAbsolute(RemoteProtocol.DOWN.toInt(), event.getPointerId(index), anchorX, anchorY, RemoteProtocol.LEFT, clamp = false)
        }
    }

    private fun move(event: MotionEvent) {
        if (pointing == RemotePointing.POINTER) {
            if (abs(event.x - anchorX) + abs(event.y - anchorY) > slop) {
                moved = true
                removeCallbacks(longPress)
            }
            val next = RemoteTouch.moveCursor(cursorX, cursorY, event.x - lastX, event.y - lastY, width, height)
            cursorX = next.first
            cursorY = next.second
            lastX = event.x
            lastY = event.y
            emitNorm(RemoteProtocol.MOVE.toInt(), 0, cursorX, cursorY, 0)
            invalidate()
            return
        }
        if (pointing == RemotePointing.TABLET) {
            for (index in 0 until event.pointerCount) {
                emitAbsolute(RemoteProtocol.MOVE.toInt(), event.getPointerId(index), event.getX(index), event.getY(index), RemoteProtocol.LEFT, clamp = true)
            }
            return
        }
        if (abs(event.x - anchorX) + abs(event.y - anchorY) > slop) {
            moved = true
            removeCallbacks(longPress)
        }
        if (!longFired) emitAbsolute(RemoteProtocol.MOVE.toInt(), 0, event.x, event.y, RemoteProtocol.LEFT, clamp = true)
    }

    private fun finish(event: MotionEvent, index: Int, cancel: Boolean) {
        removeCallbacks(longPress)
        when (pointing) {
            RemotePointing.POINTER -> {
                if (!cancel && !moved && !longFired) {
                    emitNorm(RemoteProtocol.DOWN.toInt(), 0, cursorX, cursorY, RemoteProtocol.LEFT)
                    emitNorm(RemoteProtocol.UP.toInt(), 0, cursorX, cursorY, 0)
                }
            }
            RemotePointing.TABLET -> {
                for (pointer in 0 until event.pointerCount) {
                    emitAbsolute(RemoteProtocol.UP.toInt(), event.getPointerId(pointer), event.getX(pointer), event.getY(pointer), 0, clamp = true)
                }
            }
            RemotePointing.TOUCH -> emitAbsolute(RemoteProtocol.UP.toInt(), 0, event.getX(index), event.getY(index), 0, clamp = true)
        }
        tracking = false
    }

    private fun emitAbsolute(action: Int, id: Int, x: Float, y: Float, buttons: Int, clamp: Boolean) {
        val bitmap = frame ?: return
        val image = RemoteTouch.toImage(x, y, width, height, bitmap.width, bitmap.height, quarter, clamp) ?: return
        val norm = RemoteTouch.normalize(image.first, image.second, bitmap.width, bitmap.height)
        if (id == 0) {
            cursorX = norm.first
            cursorY = norm.second
            if (virtualCursor) invalidate()
        }
        emitNorm(action, id, norm.first, norm.second, buttons)
    }

    private fun emitNorm(action: Int, id: Int, x: Int, y: Int, buttons: Int) {
        listener?.onPointer(action, id, x, y, buttons)
    }
}
