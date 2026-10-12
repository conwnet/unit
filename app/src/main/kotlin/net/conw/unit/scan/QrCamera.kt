package net.conw.unit.scan

import android.annotation.SuppressLint
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Size
import android.view.Surface
import android.view.TextureView
import kotlin.math.max

/** Each preview owns one camera thread. Late callbacks close their camera after stop(). */
internal class QrCamera(
    private val preview: TextureView,
    private val result: (String) -> Unit,
    private val error: () -> Unit,
) {
    private val manager = preview.context.getSystemService(CameraManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var capture: Capture? = null
    private var requested = false

    fun start() {
        requested = true
        if (capture == null && preview.isAvailable) capture = Capture().also { it.start() }
    }

    fun stop() {
        requested = false
        capture?.close()
    }

    private inner class Capture {
        private val thread = HandlerThread("unit-qr").apply { start() }
        private val handler = Handler(thread.looper)
        private val decoder = QrDecoder()
        @Volatile private var closing = false
        private var opening = false
        private var ended = false
        private var camera: CameraDevice? = null
        private var session: CameraCaptureSession? = null
        private var reader: ImageReader? = null
        private var surface: Surface? = null
        private var lastFrame = 0L

        fun start() {
            val texture = preview.surfaceTexture ?: return close()
            handler.post {
                if (closing) return@post
                try {
                    open(texture)
                } catch (_: Exception) {
                    opening = false
                    failed()
                }
            }
        }

        @SuppressLint(
            "MissingPermission"
        ) // The activity obtains CAMERA before starting this preview.
        private fun open(texture: SurfaceTexture) {
            val ids = manager.cameraIdList
            val id =
                ids.firstOrNull {
                    manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) ==
                        CameraCharacteristics.LENS_FACING_BACK
                } ?: ids.firstOrNull() ?: return failed()
            val info = manager.getCameraCharacteristics(id)
            val map =
                requireNotNull(info.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP))
            val previews = map.getOutputSizes(SurfaceTexture::class.java).toSet()
            val sizes = map.getOutputSizes(ImageFormat.YUV_420_888).filter { it in previews }
            val size =
                sizes
                    .filter { it.width <= 1280 && it.height <= 720 }
                    .maxByOrNull { it.width * it.height }
                    ?: sizes.minByOrNull { it.width * it.height }
                    ?: return failed()
            texture.setDefaultBufferSize(size.width, size.height)
            surface = Surface(texture)
            main.post { if (capture === this && !closing) transform(info, size) }
            reader =
                ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2).apply {
                    setOnImageAvailableListener(
                        { frames ->
                            if (closing) return@setOnImageAvailableListener
                            frames.acquireLatestImage()?.use { image ->
                                val now = SystemClock.elapsedRealtime()
                                if (now - lastFrame < 200) return@use
                                lastFrame = now
                                val plane = image.planes[0]
                                val text =
                                    decoder.decode(
                                        plane.buffer,
                                        image.width,
                                        image.height,
                                        plane.rowStride,
                                        plane.pixelStride,
                                    )
                                if (text != null)
                                    main.post {
                                        if (capture === this@Capture && !closing) {
                                            stop()
                                            result(text)
                                        }
                                    }
                            }
                        },
                        handler,
                    )
                }
            opening = true
            manager.openCamera(
                id,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(device: CameraDevice) {
                        opening = false
                        camera = device
                        if (closing) {
                            device.close()
                            return
                        }
                        try {
                            configure(device, info)
                        } catch (_: Exception) {
                            failed()
                        }
                    }

                    override fun onDisconnected(device: CameraDevice) {
                        opening = false
                        camera = device
                        device.close()
                        failed()
                    }

                    override fun onError(device: CameraDevice, code: Int) = onDisconnected(device)

                    override fun onClosed(device: CameraDevice) {
                        if (camera === device) camera = null
                        if (closing) finished()
                    }
                },
                handler,
            )
        }

        private fun configure(device: CameraDevice, info: CameraCharacteristics) {
            val targets = listOf(requireNotNull(surface), requireNotNull(reader).surface)
            val callback =
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(next: CameraCaptureSession) {
                        if (closing) {
                            next.close()
                            return
                        }
                        session = next
                        try {
                            val request =
                                device
                                    .createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                                    .apply {
                                        targets.forEach { addTarget(it) }
                                        val modes =
                                            info.get(
                                                CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES
                                            ) ?: intArrayOf()
                                        if (
                                            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE in
                                                modes
                                        )
                                            set(
                                                CaptureRequest.CONTROL_AF_MODE,
                                                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
                                            )
                                    }
                                    .build()
                            next.setRepeatingRequest(request, null, handler)
                        } catch (_: Exception) {
                            failed()
                        }
                    }

                    override fun onConfigureFailed(next: CameraCaptureSession) {
                        next.close()
                        failed()
                    }
                }
            device.createCaptureSession(
                SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    targets.map(::OutputConfiguration),
                    { handler.post(it) },
                    callback,
                )
            )
        }

        private fun transform(info: CameraCharacteristics, size: Size) {
            val front =
                info.get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_FRONT
            val display = (preview.display?.rotation ?: Surface.ROTATION_0) * 90
            val sensor = info.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            val rotation = (sensor - (if (front) display else -display) + 360) % 360
            val width = preview.width.toFloat()
            val height = preview.height.toFloat()
            if (width == 0f || height == 0f) return
            val bufferWidth = if (rotation % 180 == 0) size.width else size.height
            val bufferHeight = if (rotation % 180 == 0) size.height else size.width
            val scale = max(width / bufferWidth, height / bufferHeight)
            val x = width / 2
            val y = height / 2
            preview.setTransform(
                Matrix().apply {
                    val rotated = sensor % 180 != 0
                    setScale(
                        (if (rotated) size.height else size.width) / width,
                        (if (rotated) size.width else size.height) / height,
                        x,
                        y,
                    )
                    // TextureView already applies the sensor orientation.
                    postRotate(-display.toFloat(), x, y)
                    postScale(scale, scale, x, y)
                    if (front) postScale(-1f, 1f, x, y)
                }
            )
        }

        private fun failed() {
            main.post {
                if (capture === this && !closing) {
                    stop()
                    error()
                }
            }
        }

        fun close() {
            if (closing) return
            closing = true
            handler.post {
                session?.close()
                camera?.close()
                reader?.close()
                surface?.release()
                if (camera == null && !opening) finished()
            }
        }

        private fun finished() {
            if (ended) return
            ended = true
            thread.quitSafely()
            main.post {
                if (capture === this) {
                    capture = null
                    if (requested) this@QrCamera.start()
                }
            }
        }
    }
}
