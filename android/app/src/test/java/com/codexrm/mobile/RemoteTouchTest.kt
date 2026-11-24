package com.codexrm.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.abs

class RemoteTouchTest {
    @Test
    fun centerOfFittedImageIsTheMiddle() {
        val image = RemoteTouch.toImage(100f, 50f, 200, 100, 100, 50, 0)
        val norm = RemoteTouch.normalize(image!!.first, image.second, 100, 50)
        assertEquals(32768, norm.first)
        assertEquals(32768, norm.second)
    }

    @Test
    fun letterboxOutsideTheImageIsIgnored() {
        assertNull(RemoteTouch.toImage(10f, 50f, 200, 100, 100, 100, 0))
        val edge = RemoteTouch.toImage(50f, 0f, 200, 100, 100, 100, 0)
        val norm = RemoteTouch.normalize(edge!!.first, edge.second, 100, 100)
        assertEquals(0, norm.first)
        assertEquals(0, norm.second)
    }

    @Test
    fun quarterTurnRoundTripsThroughTheCenter() {
        val view = RemoteTouch.imageToView(50f, 25f, 100, 200, 100, 50, 1)
        val back = RemoteTouch.toImage(view.first, view.second, 100, 200, 100, 50, 1)
        assertEquals(50f, back!!.first, 0.6f)
        assertEquals(25f, back.second, 0.6f)
    }

    @Test
    fun rotatedCornerMapsToTheSourceCorner() {
        val image = RemoteTouch.toImage(0f, 0f, 100, 200, 100, 50, 1)
        assertEquals(0f, image!!.first, 0.6f)
        assertEquals(50f, image.second, 0.6f)
    }

    @Test
    fun relativeCursorStaysInsideTheDesktop() {
        val next = RemoteTouch.moveCursor(65535, 65535, 40f, 40f, 200, 100)
        assertEquals(65535, next.first)
        assertEquals(65535, next.second)
        val moved = RemoteTouch.moveCursor(0, 0, 200f, 100f, 200, 100)
        assertEquals(true, moved.first in 1..65535 && moved.second in 1..65535)
    }

    @Test
    fun pointerMessageMatchesTheDirectProtocol() {
        val bytes = RemoteProtocol.pointer(RemoteProtocol.DOWN.toInt(), 1, 0, 65535, RemoteProtocol.LEFT)
        val expected = byteArrayOf(2, 0, 0, 0, 7, 1, 1, 0, 0, 0xff.toByte(), 0xff.toByte(), 1)
        assertEquals(expected.toList(), bytes.toList())
        val mode = RemoteProtocol.mode(true)
        assertEquals(listOf<Byte>(4, 0, 0, 0, 1, 1), mode.toList())
        assertEquals(0, abs(RemoteProtocol.chord(0x5b, 0x44).size - 4))
    }
}
