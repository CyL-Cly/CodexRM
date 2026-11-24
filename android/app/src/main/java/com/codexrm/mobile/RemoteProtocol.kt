package com.codexrm.mobile

internal object RemoteProtocol {
    const val POINTER: Byte = 2
    const val KEY: Byte = 3
    const val MODE: Byte = 4
    const val DOWN: Byte = 1
    const val MOVE: Byte = 2
    const val UP: Byte = 3
    const val KEY_DOWN: Byte = 1
    const val KEY_UP: Byte = 2
    const val KEY_UNICODE: Byte = 3
    const val LEFT: Int = 1
    const val RIGHT: Int = 2
    const val MIDDLE: Int = 4
    const val MOUSE: Byte = 0
    const val TABLET: Byte = 1

    fun pointer(action: Int, id: Int, x: Int, y: Int, buttons: Int): ByteArray {
        val payload = byteArrayOf(
            action.toByte(), id.toByte(),
            (x ushr 8).toByte(), x.toByte(),
            (y ushr 8).toByte(), y.toByte(),
            buttons.toByte(),
        )
        return message(POINTER, payload)
    }

    fun key(action: Int, code: Int): ByteArray = message(KEY, byteArrayOf(action.toByte(), (code ushr 8).toByte(), code.toByte()))

    fun unicode(code: Int): ByteArray = key(KEY_UNICODE.toInt(), code)

    fun mode(tablet: Boolean): ByteArray = message(MODE, byteArrayOf(if (tablet) TABLET else MOUSE))

    fun chord(vararg codes: Int): List<ByteArray> {
        val down = codes.map { key(KEY_DOWN.toInt(), it) }
        val up = codes.reversed().map { key(KEY_UP.toInt(), it) }
        return down + up
    }

    private fun message(type: Byte, payload: ByteArray): ByteArray {
        val out = ByteArray(5 + payload.size)
        val size = payload.size
        out[0] = type
        out[1] = (size ushr 24).toByte()
        out[2] = (size ushr 16).toByte()
        out[3] = (size ushr 8).toByte()
        out[4] = size.toByte()
        payload.copyInto(out, 5)
        return out
    }
}
