package com.codexrm.mobile

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.PerspectiveTransform
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer

class ScannerQrDecoderTest {
    private val payload = "https://pair.example.invalid/mobile?code=example-only&name=测试设备"
    private val width = 460
    private val height = 380
    private val decoder = ScannerQrDecoder()

    @Test fun readsFrontalQrCode() {
        val data = frame(floatArrayOf(65f, 30f, 385f, 30f, 385f, 350f, 65f, 350f))
        assertEquals(payload, decoder.decode(ByteBuffer.wrap(data), width, 1, width, height))
    }

    @Test fun correctsPerspectiveAndRotation() {
        val corners = listOf(
            floatArrayOf(95f, 50f, 358f, 75f, 404f, 336f, 47f, 308f),
            floatArrayOf(354f, 42f, 382f, 310f, 95f, 354f, 52f, 75f),
        )
        for (quad in corners) {
            val data = frame(quad)
            assertEquals(payload, decoder.decode(ByteBuffer.wrap(data), width, 1, width, height))
        }
    }

    @Test fun readsTiltedQrUnderUnevenLightWhenGlobalThresholdFails() {
        val data = frame(floatArrayOf(95f, 50f, 358f, 75f, 404f, 336f, 47f, 308f), unevenLight = true)
        val source = PlanarYUVLuminanceSource(data, width, height, 0, 0, width, height, false)
        val globalResult = try { QRCodeReader().decode(BinaryBitmap(GlobalHistogramBinarizer(source)), mapOf(DecodeHintType.TRY_HARDER to true)).text }
            catch (_: ReaderException) { null }
        assertNull(globalResult)

        val rowStride = width * 2 + 14
        val offset = 19
        // No padding after the last pixel of the last row, as allowed by Image.Plane.
        val yPlane = ByteBuffer.allocateDirect(offset + (height - 1) * rowStride + (width - 1) * 2 + 1)
        for (y in 0 until height) for (x in 0 until width) {
            yPlane.put(offset + y * rowStride + x * 2, data[y * width + x])
        }
        yPlane.position(offset)
        assertEquals(payload, decoder.decode(yPlane, rowStride, 2, width, height))
        assertEquals(offset, yPlane.position())
    }

    @Test fun ignoresEmptyFramesAndKeepsScanning() {
        assertNull(decoder.decode(ByteBuffer.wrap(ByteArray(width * height)), width, 1, width, height))
        readsFrontalQrCode()
    }

    private fun frame(quad: FloatArray, unevenLight: Boolean = false): ByteArray {
        val qr = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 0, 0,
            mapOf(EncodeHintType.CHARACTER_SET to "UTF-8", EncodeHintType.QR_VERSION to 5))
        val side = qr.width.toFloat()
        val inverse = PerspectiveTransform.quadrilateralToQuadrilateral(
            quad[0], quad[1], quad[2], quad[3], quad[4], quad[5], quad[6], quad[7],
            0f, 0f, side, 0f, side, side, 0f, side,
        )
        val point = FloatArray(2)
        return ByteArray(width * height) { i ->
            val x = i % width
            point[0] = x + 0.5f
            point[1] = i / width + 0.5f
            inverse.transformPoints(point)
            val dark = point[0] >= 0f && point[0] < side && point[1] >= 0f && point[1] < side &&
                qr[point[0].toInt(), point[1].toInt()]
            // In this gradient, dark pixels on the bright side can be lighter than
            // white pixels on the shadowed side, so a global threshold cannot separate them.
            val ambient = if (unevenLight) 8 + 164 * x / width else 20
            (ambient + if (dark) 0 else if (unevenLight) 66 else 215).toByte()
        }
    }
}
