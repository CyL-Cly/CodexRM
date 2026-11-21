package com.codexrm.mobile

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Size
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.NotFoundException
import com.google.zxing.ReaderException
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.nio.ByteBuffer
import kotlin.math.abs

internal class ScannerActivity : Activity(), TextureView.SurfaceTextureListener {
    companion object { const val EXTRA_TEXT = "scan_text" }

    private lateinit var texture: TextureView
    private lateinit var status: android.widget.TextView
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var background: Handler? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var previewSurface: Surface? = null
    private var cameraId: String? = null
    private var torch = false
    private var hasFlash = false
    private var resumed = false
    private var opening = false
    private var cameraGeneration = 0
    private var lastDecode = 0L
    @Volatile private var resultSent = false
    private val decoder = QRCodeReader()
    private val hints = mapOf(DecodeHintType.CHARACTER_SET to "UTF-8")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.configureSystemBars(this, dark = true, background = Color.BLACK)
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        Ui.applyWindowInsets(root)
        texture = TextureView(this)
        texture.surfaceTextureListener = this
        root.addView(texture, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(ScanOverlay(this))
        val top = Ui.column(this, false).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(context, 12), Ui.dp(context, 36), Ui.dp(context, 12), 0)
        }
        top.addView(Ui.button(this, "取消") { finish() })
        val torchButton = Ui.button(this, "手电筒") { toggleTorch() }
        top.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        root.addView(top)
        root.addView(torchButton, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
            topMargin = Ui.dp(this@ScannerActivity, 36)
            rightMargin = Ui.dp(this@ScannerActivity, 12)
        })
        status = Ui.text(this, "将电脑上的配对二维码放入框内", 15f).apply {
            gravity = Gravity.CENTER
            setPadding(24, 12, 24, 12)
        }
        root.addView(status, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
            bottomMargin = Ui.dp(this@ScannerActivity, 64)
        })
        setContentView(root)
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 41)
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        if (texture.isAvailable && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera(texture.surfaceTexture!!)
        }
    }

    override fun onPause() { resumed = false; closeCamera(); super.onPause() }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != 41) return
        if (grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "没有相机权限，可返回使用手动配对", Toast.LENGTH_LONG).show()
            finish()
        } else if (texture.isAvailable) startCamera(texture.surfaceTexture!!)
    }

    override fun onSurfaceTextureAvailable(surface: android.graphics.SurfaceTexture, width: Int, height: Int) {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera(surface)
    }
    override fun onSurfaceTextureSizeChanged(surface: android.graphics.SurfaceTexture, width: Int, height: Int) = Unit
    override fun onSurfaceTextureUpdated(surface: android.graphics.SurfaceTexture) = Unit
    override fun onSurfaceTextureDestroyed(surface: android.graphics.SurfaceTexture): Boolean { closeCamera(); return true }

    @SuppressLint("MissingPermission")
    private fun startCamera(surfaceTexture: android.graphics.SurfaceTexture) {
        if (!resumed || camera != null || opening || resultSent) return
        opening = true
        val currentGeneration = cameraGeneration
        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            val id = manager.cameraIdList.firstOrNull {
                manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: manager.cameraIdList.firstOrNull() ?: throw IllegalStateException("没有可用相机")
            val characteristics = manager.getCameraCharacteristics(id)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?: throw IllegalStateException("相机不支持预览")
            val previewSizes = map.getOutputSizes(android.graphics.SurfaceTexture::class.java)?.toSet().orEmpty()
            val sizes = map.getOutputSizes(android.graphics.ImageFormat.YUV_420_888)
                ?.filter { it in previewSizes && it.width.toLong() * it.height <= 1920L * 1080L }
                .orEmpty()
            val size = sizes.minByOrNull { abs(it.width * it.height - 1280 * 720) } ?: Size(640, 480)
            cameraId = id
            hasFlash = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            val worker = HandlerThread("codexrm-camera").also { it.start() }
            thread = worker
            background = Handler(worker.looper)
            reader = ImageReader.newInstance(size.width, size.height, android.graphics.ImageFormat.YUV_420_888, 2).also { imageReader ->
                imageReader.setOnImageAvailableListener({ source ->
                    val image = source.acquireLatestImage() ?: return@setOnImageAvailableListener
                    image.use {
                        val now = SystemClock.elapsedRealtime()
                        if (resultSent || now - lastDecode < 200) return@use
                        lastDecode = now
                        val plane = it.planes.firstOrNull() ?: return@use
                        val sourceY = YPlaneSource(plane.buffer, plane.rowStride, plane.pixelStride, it.width, it.height)
                        val text = try {
                            decoder.decode(BinaryBitmap(GlobalHistogramBinarizer(sourceY)), hints).text
                        } catch (_: ReaderException) { null } finally { decoder.reset() }
                        if (!text.isNullOrEmpty() && !resultSent) {
                            resultSent = true
                            runOnUiThread {
                                setResult(RESULT_OK, Intent().putExtra(EXTRA_TEXT, text))
                                finish()
                            }
                        }
                    }
                }, background)
            }
            surfaceTexture.setDefaultBufferSize(size.width, size.height)
            previewSurface = Surface(surfaceTexture)
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    if (!resumed || currentGeneration != cameraGeneration || resultSent || isFinishing) {
                        opening = false
                        device.close()
                        return
                    }
                    opening = false
                    camera = device
                    openSession(device, currentGeneration)
                }
                override fun onDisconnected(device: CameraDevice) { device.close(); if (camera === device) camera = null }
                override fun onError(device: CameraDevice, error: Int) {
                    device.close()
                    opening = false
                    if (camera === device) camera = null
                    if (currentGeneration == cameraGeneration && resumed) runOnUiThread { status.text = "相机打开失败（$error），请返回手动配对" }
                }
            }, mainHandler)
        } catch (error: Exception) {
            status.text = "无法启动扫码：${error.message ?: "相机不可用"}"
            closeCamera()
        }
    }

    @Suppress("DEPRECATION")
    private fun openSession(device: CameraDevice, currentGeneration: Int) {
        val imageReader = reader ?: return
        val preview = previewSurface ?: return
        if (!resumed || currentGeneration != cameraGeneration) { device.close(); return }
        try {
            device.createCaptureSession(listOf(preview, imageReader.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(captureSession: CameraCaptureSession) {
                    if (!resumed || currentGeneration != cameraGeneration || camera !== device || reader !== imageReader || previewSurface !== preview) {
                        captureSession.close()
                        return
                    }
                    try {
                        session = captureSession
                        val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                            addTarget(preview)
                            addTarget(imageReader.surface)
                            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                        }.build()
                        captureSession.setRepeatingRequest(request, null, background)
                    } catch (_: IllegalStateException) {
                        captureSession.close()
                        if (session === captureSession) session = null
                    }
                }
                override fun onConfigureFailed(captureSession: CameraCaptureSession) {
                    runOnUiThread { status.text = "相机预览配置失败" }
                }
            }, mainHandler)
        } catch (error: Exception) {
            runOnUiThread { status.text = "相机预览失败：${error.message}" }
        }
    }

    private fun toggleTorch() {
        val id = cameraId ?: return
        if (!hasFlash) { Toast.makeText(this, "此相机没有闪光灯", Toast.LENGTH_SHORT).show(); return }
        try {
            torch = !torch
            (getSystemService(Context.CAMERA_SERVICE) as CameraManager).setTorchMode(id, torch)
        } catch (error: Exception) { Toast.makeText(this, error.message ?: "无法打开手电筒", Toast.LENGTH_SHORT).show() }
    }

    private fun closeCamera() {
        cameraGeneration++
        opening = false
        session?.close(); session = null
        camera?.close(); camera = null
        previewSurface?.release(); previewSurface = null
        reader?.close(); reader = null
        thread?.quitSafely(); thread = null
        background = null
        if (torch) {
            cameraId?.let { runCatching { (getSystemService(Context.CAMERA_SERVICE) as CameraManager).setTorchMode(it, false) } }
            torch = false
        }
    }

    private class ScanOverlay(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val side = width * 0.72f
            val left = (width - side) / 2
            val top = (height - side) / 2.5f
            val frame = RectF(left, top, left + side, top + side)
            paint.color = 0x88000000.toInt()
            canvas.drawRect(0f, 0f, width.toFloat(), frame.top, paint)
            canvas.drawRect(0f, frame.bottom, width.toFloat(), height.toFloat(), paint)
            canvas.drawRect(0f, frame.top, frame.left, frame.bottom, paint)
            canvas.drawRect(frame.right, frame.top, width.toFloat(), frame.bottom, paint)
            paint.color = Ui.ACCENT
            paint.strokeWidth = Ui.dp(context, 4).toFloat()
            paint.style = Paint.Style.STROKE
            canvas.drawRoundRect(frame, 18f, 18f, paint)
        }
    }
}

private class YPlaneSource(
    private val buffer: ByteBuffer,
    private val rowStride: Int,
    private val pixelStride: Int,
    width: Int,
    height: Int,
) : LuminanceSource(width, height) {
    override fun getRow(y: Int, row: ByteArray?): ByteArray {
        val output = if (row != null && row.size >= width) row else ByteArray(width)
        val copy = buffer.duplicate()
        val start = y * rowStride
        if (pixelStride == 1) {
            copy.position(start)
            copy.get(output, 0, width)
        } else {
            for (x in 0 until width) output[x] = copy.get(start + x * pixelStride)
        }
        return output
    }

    override fun getMatrix(): ByteArray {
        val matrix = ByteArray(width * height)
        for (y in 0 until height) {
            val row = getRow(y, null)
            System.arraycopy(row, 0, matrix, y * width, width)
        }
        return matrix
    }
}
