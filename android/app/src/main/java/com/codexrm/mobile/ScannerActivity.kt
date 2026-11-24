package com.codexrm.mobile

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.MeteringRectangle
import android.media.ImageReader
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Size
import android.view.Gravity
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil

internal class ScannerActivity : ComponentActivity(), TextureView.SurfaceTextureListener {
    companion object {
        const val EXTRA_TEXT = "scan_text"
        private const val DEFAULT_HINT = "将电脑上的配对二维码放入框内"
        private const val ZOOM_HINT = "将电脑上的配对二维码放入框内，可双指缩放"
        private const val ZOOM_STEP = 1.25f
    }

    private lateinit var texture: TextureView
    private lateinit var status: TextView
    private lateinit var zoomLabel: TextView
    private lateinit var zoomIn: MiuixButtonView
    private lateinit var zoomOut: MiuixButtonView
    private lateinit var zoomControls: View
    private lateinit var torchButton: MiuixButtonView
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var request: CaptureRequest.Builder? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var background: Handler? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var previewSurface: Surface? = null
    private var previewSize: Size? = null
    private var sensorOrientation = 0
    private var activeArray: Rect? = null
    private var hasAfRegion = false
    private var autoFocusMode = CaptureRequest.CONTROL_AF_MODE_OFF
    private var torch = false
    private var hasFlash = false
    private var zoom = 1f
    private var minZoom = 1f
    private var maxZoom = 1f
    private var useZoomRatio = false
    private var resumed = false
    private var opening = false
    @Volatile private var cameraGeneration = 0
    private var resultSent = false

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            texture.surfaceTexture?.let(::startCamera)
        } else {
            Toast.makeText(this, "没有相机权限，可返回使用手动配对", Toast.LENGTH_LONG).show()
            finish()
        }
    }
    private var previewUpdateQueued = false
    private val updatePreview = Runnable {
        previewUpdateQueued = false
        applyPreviewSettings()
    }
    private val autoFocus = object : Runnable {
        override fun run() {
            val captureSession = session ?: return
            val builder = request ?: return
            try {
                // AUTO-only lenses lock after START. Cancel before each new sweep.
                builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL)
                val cancel = builder.build()
                builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
                captureSession.captureBurst(listOf(cancel, builder.build()), null, null)
            } catch (_: Exception) {
                // The session can be closed by the camera service before its callback arrives.
            } finally {
                builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
            }
            mainHandler.postDelayed(this, 1600)
        }
    }

    @SuppressLint("ClickableViewAccessibility") // Discrete zoom buttons are also available.
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.configureSystemBars(this, dark = true, background = Color.BLACK)
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        Ui.applyWindowInsets(root)
        val pinch = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean = canZoom()
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                setZoom(zoom * detector.scaleFactor)
                return true
            }
        }).apply { isQuickScaleEnabled = false }
        texture = TextureView(this).apply {
            surfaceTextureListener = this@ScannerActivity
            setOnTouchListener { _, event -> pinch.onTouchEvent(event); true }
        }
        root.addView(texture, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(ScanOverlay(this), FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(Ui.button(this, "取消") { finish() }, topButtonParams(Gravity.START))
        torchButton = Ui.button(this, "手电筒") {
            if (session != null && hasFlash) {
                torch = !torch
                queuePreviewUpdate()
                updateControls()
            }
        }
        root.addView(torchButton, topButtonParams(Gravity.END))

        val footer = Ui.column(this).apply { gravity = Gravity.CENTER_HORIZONTAL }
        zoomLabel = Ui.text(this, color = Color.WHITE, bold = true).apply {
            gravity = Gravity.CENTER
            minWidth = Ui.dp(context, 66)
            setShadowLayer(4f, 0f, 1f, Color.BLACK)
        }
        zoomOut = Ui.button(this, "−") { setZoom(zoom / ZOOM_STEP) }.apply { contentDescription = "缩小" }
        zoomIn = Ui.button(this, "+") { setZoom(zoom * ZOOM_STEP) }.apply { contentDescription = "放大" }
        zoomControls = Ui.column(this, false).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(zoomOut, Ui.dp(context, 48), Ui.dp(context, 48))
            addView(zoomLabel)
            addView(zoomIn, Ui.dp(context, 48), Ui.dp(context, 48))
        }
        footer.addView(zoomControls, WRAP_CONTENT, WRAP_CONTENT)
        status = Ui.text(this, DEFAULT_HINT, 15f, Color.WHITE).apply {
            gravity = Gravity.CENTER
            setPadding(Ui.dp(context, 16), Ui.dp(context, 12), Ui.dp(context, 16), Ui.dp(context, 12))
            setShadowLayer(4f, 0f, 1f, Color.BLACK)
        }
        footer.addView(status, MATCH_PARENT, WRAP_CONTENT)
        root.addView(footer, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM).apply {
            bottomMargin = Ui.dp(this@ScannerActivity, 24)
        })
        updateControls()
        setContentView(root)
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun topButtonParams(gravity: Int) = FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or gravity).apply {
        topMargin = Ui.dp(this@ScannerActivity, 12)
        marginStart = Ui.dp(this@ScannerActivity, 12)
        marginEnd = marginStart
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            texture.surfaceTexture?.let(::startCamera)
        }
    }

    override fun onPause() { resumed = false; closeCamera(); super.onPause() }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera(surface)
    }
    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = updatePreviewTransform()
    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean { closeCamera(); return true }

    @SuppressLint("MissingPermission")
    private fun startCamera(surfaceTexture: SurfaceTexture) {
        if (!resumed || camera != null || opening || resultSent) return
        status.text = DEFAULT_HINT
        opening = true
        val currentGeneration = cameraGeneration
        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            val ids = manager.cameraIdList
            val id = ids.firstOrNull {
                manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: ids.firstOrNull() ?: error("没有可用相机")
            val characteristics = manager.getCameraCharacteristics(id)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: error("相机不支持预览")
            val previewSizes = map.getOutputSizes(SurfaceTexture::class.java)?.toSet().orEmpty()
            val sizes = map.getOutputSizes(ImageFormat.YUV_420_888)?.filter { it in previewSizes }.orEmpty()
            val size = sizes.filter { it.width.toLong() * it.height <= 1920L * 1080L }
                .minByOrNull { abs(it.width * it.height - 1280 * 720) }
                ?: sizes.minByOrNull { it.width.toLong() * it.height } ?: error("相机不支持扫码预览")
            previewSize = size
            sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            hasFlash = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            val afModes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
            autoFocusMode = listOf(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO, CaptureRequest.CONTROL_AF_MODE_AUTO,
                CaptureRequest.CONTROL_AF_MODE_MACRO).firstOrNull { it in afModes } ?: CaptureRequest.CONTROL_AF_MODE_OFF
            hasAfRegion = autoFocusMode != CaptureRequest.CONTROL_AF_MODE_OFF &&
                (characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0) > 0
            val zoomRange = if (Build.VERSION.SDK_INT >= 30) characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) else null
            useZoomRatio = zoomRange != null
            minZoom = zoomRange?.lower ?: 1f
            maxZoom = zoomRange?.upper ?: (characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f).coerceAtLeast(1f)
            zoom = zoom.coerceIn(minZoom, maxZoom)
            val worker = HandlerThread("codexrm-camera").also { it.start() }
            thread = worker
            background = Handler(worker.looper)
            val decoder = ScannerQrDecoder()
            var lastDecode = 0L
            var delivered = false
            reader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2).also { imageReader ->
                imageReader.setOnImageAvailableListener({ source ->
                    if (currentGeneration != cameraGeneration) return@setOnImageAvailableListener
                    source.acquireLatestImage()?.use { frame ->
                        val now = SystemClock.elapsedRealtime()
                        if (delivered || now - lastDecode < 200) return@use
                        lastDecode = now
                        val plane = frame.planes.firstOrNull() ?: return@use
                        val text = decoder.decode(plane.buffer, plane.rowStride, plane.pixelStride, frame.width, frame.height)
                        if (!text.isNullOrEmpty()) {
                            delivered = true
                            mainHandler.post {
                                if (currentGeneration == cameraGeneration && resumed && !resultSent && !isFinishing) {
                                    resultSent = true
                                    setResult(RESULT_OK, Intent().putExtra(EXTRA_TEXT, text))
                                    finish()
                                }
                            }
                        }
                    }
                }, background)
            }
            surfaceTexture.setDefaultBufferSize(size.width, size.height)
            updatePreviewTransform()
            previewSurface = Surface(surfaceTexture)
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    if (!resumed || currentGeneration != cameraGeneration || resultSent || isFinishing) {
                        device.close()
                        return
                    }
                    opening = false
                    camera = device
                    openSession(device, characteristics, currentGeneration)
                }
                override fun onDisconnected(device: CameraDevice) = cameraError(device, "相机已断开，请返回重试")
                override fun onError(device: CameraDevice, error: Int) = cameraError(device, "相机打开失败（$error），请返回手动配对")
                private fun cameraError(device: CameraDevice, message: String) {
                    if (currentGeneration == cameraGeneration) {
                        if (camera !== device) device.close()
                        failCamera(message)
                    } else device.close()
                }
            }, mainHandler)
        } catch (error: Exception) {
            failCamera("无法启动扫码：${error.message ?: "相机不可用"}")
        }
    }

    private fun updatePreviewTransform() {
        val size = previewSize ?: return
        val width = texture.width.toFloat()
        val height = texture.height.toFloat()
        if (width <= 0 || height <= 0) return
        // TextureView already applies sensor orientation. Undo its nonuniform stretch,
        // then rotate for the display and center-crop without changing the aspect ratio.
        val rotation = (texture.display?.rotation ?: Surface.ROTATION_0) * 90
        val sensorSwapsAxes = sensorOrientation % 180 != 0
        val displaySwapsAxes = rotation % 180 != 0
        val sourceWidth = if (sensorSwapsAxes) size.height.toFloat() else size.width.toFloat()
        val sourceHeight = if (sensorSwapsAxes) size.width.toFloat() else size.height.toFloat()
        val scale = if (displaySwapsAxes) maxOf(height / sourceWidth, width / sourceHeight)
            else maxOf(width / sourceWidth, height / sourceHeight)
        texture.setTransform(Matrix().apply {
            setScale(sourceWidth * scale / width, sourceHeight * scale / height, width / 2, height / 2)
            postRotate(-rotation.toFloat(), width / 2, height / 2)
        })
    }

    @Suppress("DEPRECATION")
    private fun openSession(device: CameraDevice, characteristics: CameraCharacteristics, currentGeneration: Int) {
        val imageReader = reader ?: return
        val preview = previewSurface ?: return
        try {
            device.createCaptureSession(listOf(preview, imageReader.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(captureSession: CameraCaptureSession) {
                    if (currentGeneration != cameraGeneration || isFinishing) {
                        captureSession.close()
                        return
                    }
                    session = captureSession
                    try {
                        request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                            addTarget(preview)
                            addTarget(imageReader.surface)
                            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                            set(CaptureRequest.CONTROL_AF_MODE, autoFocusMode)
                            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                            val distortionModes = characteristics.get(CameraCharacteristics.DISTORTION_CORRECTION_AVAILABLE_MODES)
                            if (distortionModes?.contains(CaptureRequest.DISTORTION_CORRECTION_MODE_FAST) == true) {
                                set(CaptureRequest.DISTORTION_CORRECTION_MODE, CaptureRequest.DISTORTION_CORRECTION_MODE_FAST)
                            }
                            val uncorrected = get(CaptureRequest.DISTORTION_CORRECTION_MODE) == CaptureRequest.DISTORTION_CORRECTION_MODE_OFF
                            activeArray = (if (uncorrected) characteristics.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE) else null)
                                ?: characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                            if (!useZoomRatio && activeArray == null) { minZoom = 1f; maxZoom = 1f; zoom = 1f }
                        }
                        applyPreviewSettings()
                        if (session != null) {
                            updateControls()
                            status.text = if (canZoom()) ZOOM_HINT else DEFAULT_HINT
                            if (autoFocusMode == CaptureRequest.CONTROL_AF_MODE_AUTO || autoFocusMode == CaptureRequest.CONTROL_AF_MODE_MACRO) {
                                autoFocus.run()
                            }
                        }
                    } catch (error: Exception) {
                        failCamera("相机预览失败：${error.message}")
                    }
                }
                override fun onConfigureFailed(captureSession: CameraCaptureSession) {
                    captureSession.close()
                    if (currentGeneration == cameraGeneration) failCamera("相机预览配置失败")
                }
            }, mainHandler)
        } catch (error: Exception) {
            failCamera("相机预览失败：${error.message}")
        }
    }

    private fun canZoom() = session != null && maxZoom > minZoom

    private fun setZoom(ratio: Float) {
        if (!canZoom() || !ratio.isFinite()) return
        val next = ratio.coerceIn(minZoom, maxZoom)
        if (zoom == next) return
        zoom = next
        updateControls()
        queuePreviewUpdate()
    }

    private fun updateControls() {
        zoomControls.visibility = if (canZoom()) View.VISIBLE else View.GONE
        zoomOut.isEnabled = zoom > minZoom
        zoomIn.isEnabled = zoom < maxZoom
        val ratio = String.format(Locale.getDefault(), "%.1f", zoom)
        zoomLabel.text = "$ratio×"
        zoomLabel.contentDescription = "当前缩放 $ratio 倍"
        torchButton.isEnabled = session != null && hasFlash
        torchButton.text = if (torch) "关闭手电筒" else "手电筒"
    }

    private fun queuePreviewUpdate() {
        if (previewUpdateQueued) return
        previewUpdateQueued = true
        mainHandler.postDelayed(updatePreview, 32)
    }

    private fun applyPreviewSettings() {
        val captureSession = session ?: return
        val builder = request ?: return
        try {
            var focusArea = activeArray
            if (Build.VERSION.SDK_INT >= 30 && useZoomRatio) {
                builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom)
                // With zoomRatio, AF regions are already in post-zoom coordinates.
            } else if (focusArea != null) {
                val crop = centeredRect(focusArea, 1f / zoom)
                builder.set(CaptureRequest.SCALER_CROP_REGION, crop)
                focusArea = crop
            }
            if (hasAfRegion && focusArea != null) {
                builder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(MeteringRectangle(centeredRect(focusArea, 0.3f), 1000)))
            }
            if (hasFlash) builder.set(CaptureRequest.FLASH_MODE, if (torch) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
            captureSession.setRepeatingRequest(builder.build(), null, null)
        } catch (error: Exception) {
            failCamera("相机预览失败：${error.message}")
        }
    }

    private fun centeredRect(bounds: Rect, fraction: Float): Rect {
        val width = ceil(bounds.width() * fraction).toInt().coerceIn(1, bounds.width())
        val height = ceil(bounds.height() * fraction).toInt().coerceIn(1, bounds.height())
        val left = bounds.left + (bounds.width() - width) / 2
        val top = bounds.top + (bounds.height() - height) / 2
        return Rect(left, top, left + width, top + height)
    }

    private fun failCamera(message: String) {
        closeCamera()
        status.text = message
    }

    private fun closeCamera() {
        cameraGeneration++
        opening = false
        mainHandler.removeCallbacks(updatePreview)
        previewUpdateQueued = false
        mainHandler.removeCallbacks(autoFocus)
        session?.close(); session = null
        request = null
        camera?.close(); camera = null
        previewSurface?.release(); previewSurface = null
        previewSize = null
        reader?.let { imageReader ->
            imageReader.setOnImageAvailableListener(null, null)
            // Don't invalidate a Y plane while the decoder is still using it.
            if (background?.post { imageReader.close() } != true) imageReader.close()
        }
        reader = null
        thread?.quitSafely(); thread = null
        background = null
        torch = false
        updateControls()
    }

    private class ScanOverlay(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val side = minOf(width * 0.72f, height * 0.50f)
            val left = (width - side) / 2
            val top = (height - side) / 2
            val frame = RectF(left, top, left + side, top + side)
            paint.style = Paint.Style.FILL
            paint.color = 0x88000000.toInt()
            canvas.drawRect(0f, 0f, width.toFloat(), frame.top, paint)
            canvas.drawRect(0f, frame.bottom, width.toFloat(), height.toFloat(), paint)
            canvas.drawRect(0f, frame.top, frame.left, frame.bottom, paint)
            canvas.drawRect(frame.right, frame.top, width.toFloat(), frame.bottom, paint)
            paint.color = Ui.ACCENT
            paint.strokeWidth = Ui.dp(context, 3).toFloat()
            paint.style = Paint.Style.STROKE
            val radius = Ui.dp(context, 8).toFloat()
            canvas.drawRoundRect(frame, radius, radius, paint)
        }
    }
}
