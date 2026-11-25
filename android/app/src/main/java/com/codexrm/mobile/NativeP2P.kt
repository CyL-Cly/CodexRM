package com.codexrm.mobile

internal object NativeP2P {
    val available: Boolean = try {
        System.loadLibrary("codexrm_p2p")
        true
    } catch (_: UnsatisfiedLinkError) {
        false
    }

    external fun secureOpen(config: ByteArray): Long
    external fun secureCall(handle: Long, action: Int, input: ByteArray): ByteArray?
    external fun secureClose(handle: Long)

    external fun open(start: ByteArray): Long
    external fun poll(handle: Long): ByteArray?
    external fun signal(handle: Long, signal: ByteArray)
    external fun send(handle: Long, message: ByteArray): Boolean
    external fun measureLatency(handle: Long): Long
    external fun upload(handle: Long, id: ByteArray, path: ByteArray, size: Long): Boolean
    external fun uploadProgress(handle: Long): Int
    external fun download(handle: Long, id: ByteArray, path: ByteArray, size: Long, phoneTransfer: Boolean): Boolean
    external fun cancelDownload(handle: Long, id: ByteArray)
    external fun diagnoseNat(endpoints: ByteArray): ByteArray?
    external fun close(handle: Long)
}
