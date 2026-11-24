package com.codexrm.mobile

import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

internal object RemoteTouch {
    fun scale(viewW: Int, viewH: Int, imgW: Int, imgH: Int, quarter: Int): Float {
        if (viewW <= 0 || viewH <= 0 || imgW <= 0 || imgH <= 0) return 1f
        val turns = turns(quarter)
        val rotW = if (turns % 2 == 0) imgW.toFloat() else imgH.toFloat()
        val rotH = if (turns % 2 == 0) imgH.toFloat() else imgW.toFloat()
        return min(viewW / rotW, viewH / rotH)
    }

    fun toImage(x: Float, y: Float, viewW: Int, viewH: Int, imgW: Int, imgH: Int, quarter: Int, clamp: Boolean = false): Pair<Float, Float>? {
        if (viewW <= 0 || viewH <= 0 || imgW <= 0 || imgH <= 0) return null
        val turns = turns(quarter)
        val fitted = scale(viewW, viewH, imgW, imgH, turns)
        val drawW = imgW * fitted
        val drawH = imgH * fitted
        val rad = -turns * Math.PI / 2.0
        val dx = x - viewW / 2.0
        val dy = y - viewH / 2.0
        var ux = dx * cos(rad) - dy * sin(rad)
        var uy = dx * sin(rad) + dy * cos(rad)
        val left = -drawW / 2.0
        val top = -drawH / 2.0
        val right = left + drawW
        val bottom = top + drawH
        val outside = ux < left || uy < top || ux > right || uy > bottom
        if (outside && !clamp) return null
        if (outside) {
            ux = ux.coerceIn(left, right)
            uy = uy.coerceIn(top, bottom)
        }
        return ((ux - left) / drawW * imgW).toFloat() to ((uy - top) / drawH * imgH).toFloat()
    }

    fun normalize(ix: Float, iy: Float, imgW: Int, imgH: Int): Pair<Int, Int> {
        val x = (ix / imgW.coerceAtLeast(1) * 65535f).roundToInt().coerceIn(0, 65535)
        val y = (iy / imgH.coerceAtLeast(1) * 65535f).roundToInt().coerceIn(0, 65535)
        return x to y
    }

    fun imageToView(ix: Float, iy: Float, viewW: Int, viewH: Int, imgW: Int, imgH: Int, quarter: Int): Pair<Float, Float> {
        val turns = turns(quarter)
        val fitted = scale(viewW, viewH, imgW, imgH, turns)
        val drawW = imgW * fitted
        val drawH = imgH * fitted
        val lx = -drawW / 2.0 + ix / imgW.coerceAtLeast(1) * drawW
        val ly = -drawH / 2.0 + iy / imgH.coerceAtLeast(1) * drawH
        val rad = turns * Math.PI / 2.0
        val x = lx * cos(rad) - ly * sin(rad) + viewW / 2.0
        val y = lx * sin(rad) + ly * cos(rad) + viewH / 2.0
        return x.toFloat() to y.toFloat()
    }

    fun moveCursor(x: Int, y: Int, dx: Float, dy: Float, viewW: Int, viewH: Int): Pair<Int, Int> {
        val nx = (x + dx / viewW.coerceAtLeast(1) * 65535f * 1.35f).roundToInt().coerceIn(0, 65535)
        val ny = (y + dy / viewH.coerceAtLeast(1) * 65535f * 1.35f).roundToInt().coerceIn(0, 65535)
        return nx to ny
    }

    private fun turns(quarter: Int) = ((quarter % 4) + 4) % 4
}
