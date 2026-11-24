package com.codexrm.mobile

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.nio.ByteBuffer

/** One instance per camera worker; the Y plane remains valid for this synchronous call. */
internal class ScannerQrDecoder {
    private val reader = QRCodeReader()
    private val hints = mapOf(DecodeHintType.CHARACTER_SET to "UTF-8", DecodeHintType.TRY_HARDER to true)

    fun decode(buffer: ByteBuffer, rowStride: Int, pixelStride: Int, width: Int, height: Int): String? {
        val source = YPlaneSource(buffer, rowStride, pixelStride, width, height)
        // Use finder/alignment patterns and perspective grid sampling, not PURE_BARCODE.
        // Adaptive thresholds handle uneven monitor brightness; retain a global fallback.
        return decode(BinaryBitmap(HybridBinarizer(source)))
            ?: decode(BinaryBitmap(GlobalHistogramBinarizer(source)))
    }

    private fun decode(bitmap: BinaryBitmap): String? = try {
        reader.decode(bitmap, hints).text
    } catch (_: ReaderException) {
        null
    } finally {
        reader.reset()
    }
}

private class YPlaneSource(
    buffer: ByteBuffer,
    private val rowStride: Int,
    private val pixelStride: Int,
    width: Int,
    height: Int,
) : LuminanceSource(width, height) {
    private val data = buffer.slice()
    private val pixels by lazy(LazyThreadSafetyMode.NONE) {
        ByteArray(width * height).also { output ->
            val copy = data.duplicate()
            for (y in 0 until height) copyRow(copy, y, output, y * width)
        }
    }

    override fun getRow(y: Int, row: ByteArray?): ByteArray {
        require(y in 0 until height)
        val output = if (row != null && row.size >= width) row else ByteArray(width)
        copyRow(data.duplicate(), y, output, 0)
        return output
    }

    private fun copyRow(copy: ByteBuffer, y: Int, output: ByteArray, offset: Int) {
        val start = y * rowStride
        if (pixelStride == 1) {
            copy.position(start)
            copy.get(output, offset, width)
        } else {
            for (x in 0 until width) output[offset + x] = copy.get(start + x * pixelStride)
        }
    }

    override fun getMatrix(): ByteArray = pixels
}
