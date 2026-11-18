package com.codexrm.mobile

internal object NativeP2P {
    val available: Boolean = try {
        System.loadLibrary("codexrm_p2p")
        true
    } catch (_: UnsatisfiedLinkError) {
        false
    }

    external fun open(start: ByteArray): Long
    external fun poll(handle: Long): ByteArray?
    external fun signal(handle: Long, signal: ByteArray)
    external fun send(handle: Long, message: ByteArray): Boolean
    external fun close(handle: Long)
}
