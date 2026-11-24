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
    external fun upload(handle: Long, id: ByteArray, path: ByteArray, size: Long): Boolean
    external fun uploadProgress(handle: Long): Int
    external fun diagnoseNat(endpoints: ByteArray): ByteArray?
    external fun close(handle: Long)
    external fun startDesktop(handle: Long): Boolean
    external fun stopDesktop(handle: Long)
    external fun desktopActive(handle: Long): Boolean
    external fun desktopBytes(handle: Long): Long
    external fun pollDesktop(handle: Long): ByteArray?
    external fun sendDesktop(handle: Long, message: ByteArray): Boolean
}
