package com.github.digitallyrefined.androidipcamera.helpers

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.Surface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Opt-in PHOTO_ZSL(5) / VIDEO_REFERENCE(3) A-B for LG-H872 Camera2 main 0.
 * One PRIVATE camera SurfaceTexture -> existing GLES/H264/RTSP encoder. This path
 * cannot simultaneously serve CameraX ImageAnalysis, snapshot or on-phone Preview.
 * The service selects CameraX whenever those use cases are required.
 */
internal class LgG6Main0DirectCapture(
    private val context: Context, private val sourceSize: Size,
    private val input: Surface, private val onStatus: (String) -> Unit
) : CaptureBackend {
    companion object {
        private const val TAG = "LgMain0Direct"
        /** Choose an ACTUALLY advertised PRIVATE/SurfaceTexture size. */
        fun chooseSource(context: Context, wanted: Size): Size? = try {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val sizes = cm.getCameraCharacteristics("0")
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(SurfaceTexture::class.java)?.toList().orEmpty()
            val exact = sizes.firstOrNull { it == wanted }
            exact ?: sizes.minByOrNull {
                val aspectPenalty = kotlin.math.abs(
                    it.width.toDouble() / it.height - wanted.width.toDouble() / wanted.height)
                val areaPenalty = kotlin.math.abs(kotlin.math.ln(
                    it.width.toDouble() * it.height / (wanted.width.toDouble() * wanted.height)))
                aspectPenalty * 8.0 + areaPenalty
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cannot enumerate PRIVATE source sizes", e)
            null
        }
    }

    override val width: Int get() = sourceSize.width
    override val height: Int get() = sourceSize.height
    @Volatile override var ready: Boolean = false
        private set
    @Volatile var lastFrameAtMs = 0L
        private set
    @Volatile var failed = false
        private set
    private val thread = HandlerThread("LGG6Camera0Direct").apply { start() }
    private val handler = Handler(thread.looper)
    private val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val characteristics = cm.getCameraCharacteristics("0")
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    @Volatile private var closed = false
    @Volatile private var torch = false
    private var ev = 0
    private var zoom = 1f
    private var focusD: Float? = null
    private var iso: Int? = null
    private var shutterUs: Long? = null
    private var postRawGain = 100
    private var profile = LgG6PreviewProfile.PHOTO_ZSL
    private val resultCallback = object : CameraCaptureSession.CaptureCallback() {
        private var n = 0
        override fun onCaptureCompleted(sess: CameraCaptureSession,
                                        request: CaptureRequest, result: TotalCaptureResult) {
            lastFrameAtMs = SystemClock.elapsedRealtime()
            if (++n % 30 == 0) {
                LgG6Main0Telemetry.report(request, result, profile.wireName)
                Log.i(TAG, "source=${sourceSize.width}x${sourceSize.height}; profile=${profile.wireName}")
            }
        }
    }
    override val hasFlashUnit: Boolean get() =
        characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true

    @SuppressLint("MissingPermission") // permission is checked by StreamingService
    fun start() {
        handler.post {
            if (closed || !LgG6Main0Profile.isDevice()) return@post
            try {
                cm.openCamera("0", object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        if (closed) { camera.close(); return }
                        device = camera
                        configure(camera)
                    }
                    override fun onDisconnected(camera: CameraDevice) {
                        failed = true; ready = false
                        onStatus("Camera0 disconnected"); camera.close(); device = null
                    }
                    override fun onError(camera: CameraDevice, error: Int) {
                        failed = true; ready = false
                        onStatus("Camera0 error code=$error"); camera.close(); device = null
                    }
                }, handler)
            } catch (e: Exception) {
                failed = true
                onStatus("Direct open error: ${e.message}")
            }
        }
    }

    private fun configure(device: CameraDevice) {
        try {
            @Suppress("DEPRECATION")
            device.createCaptureSession(listOf(input), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    if (closed) { s.close(); return }
                    session = s; ready = true
                    onStatus("Direct Camera0 PRIVATE ${sourceSize.width}x${sourceSize.height} configured")
                    submit()
                }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                    failed = true; ready = false
                    onStatus("Direct PRIVATE session configuration failed"); s.close()
                }
            }, handler)
        } catch (e: Exception) {
            failed = true; ready = false
            onStatus("Direct configure exception: ${e.message}")
        }
    }

    private val applyPending = Runnable { if (!closed) submit() }
    private fun mutate(action: () -> Unit) {
        if (!closed) handler.post {
            if (!closed) {
                action()
                // applyStored() sets many camera parameters in rapid succession;
                // submit one combined repeating request, not 8 separate HAL requests.
                handler.removeCallbacks(applyPending)
                handler.postDelayed(applyPending, 40)
            }
        }
    }
    override fun getTorch(): Boolean = torch
    override fun setTorch(on: Boolean) { mutate { torch = on } }
    override fun setExposure(ev: Int) { mutate { this.ev = ev } }
    override fun setZoom(ratio: Float) { mutate { zoom = ratio.coerceIn(1f, 8f) } }
    override fun triggerAutoFocus() { setFocusDiopters(null) }
    override fun setManualFocus(distance: Float) {
        val max = characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        setFocusDiopters(distance.takeIf { it >= 0f }?.coerceIn(0f, 1f)?.times(max))
    }
    override fun setFocusDiopters(diopters: Float?) {
        mutate { focusD = diopters?.takeIf { it.isFinite() }?.coerceIn(0f, 10f) }
    }
    override fun setManualExposure(iso: Int?, shutterUs: Long?) {
        mutate {
            this.iso = if (iso != null && shutterUs != null) iso else null
            this.shutterUs = if (iso != null && shutterUs != null) shutterUs else null
        }
    }
    override fun setLgVendorZoom(zoom: Float?) { /* Vendor key is not advertised on H872 ID0. */ }
    override fun setLgPostRawBoost(percent: Int?) { mutate { postRawGain = percent ?: 100 } }
    override fun setLgFocusPeaking(enabled: Boolean) { /* Not advertised on the tested ID0 HAL. */ }
    override fun setLgPreviewProfile(profile: String) {
        mutate { this.profile = LgG6PreviewProfile.parse(profile) }
    }
    override fun captureStill(onJpeg: (ByteArray?) -> Unit) = onJpeg(null)

    private fun submit() {
        val d = device ?: return
        val s = session ?: return
        try {
            val b = try {
                d.createCaptureRequest(LgG6PhotoPreviewTuning.template(profile))
            } catch (e: IllegalArgumentException) {
                onStatus("Template ${profile.wireName} unsupported; using PREVIEW")
                d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            }
            b.addTarget(input)
            LgG6PhotoPreviewTuning.apply(b, characteristics, profile)
            b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                  CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
            b.set(CaptureRequest.FLASH_MODE, if (torch) CaptureRequest.FLASH_MODE_TORCH
                  else CaptureRequest.FLASH_MODE_OFF)
            val active = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            if (active != null) {
                val crop = LgG6Main0Math.sensorCrop(
                    LgPixelRect(active.left, active.top, active.right, active.bottom),
                    zoom.toDouble(), sourceSize.width, sourceSize.height)
                b.set(CaptureRequest.SCALER_CROP_REGION,
                      Rect(crop.left, crop.top, crop.right, crop.bottom))
            }
            val maxFocus = characteristics.get(
                CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
            if (focusD != null && maxFocus > 0f) {
                b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                b.set(CaptureRequest.LENS_FOCUS_DISTANCE, focusD!!.coerceIn(0f,maxFocus))
            } else b.set(CaptureRequest.CONTROL_AF_MODE,
                if (profile == LgG6PreviewProfile.VIDEO_REFERENCE)
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                else CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            val pref = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
            val fps = (pref.getString("stream_fps","15")?.toIntOrNull() ?: 15).coerceIn(1,30)
            val desiredNs = 1_000_000_000L / fps
            if (iso != null && shutterUs != null) {
                val isoR = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
                val expR = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
                if (isoR != null && expR != null) {
                    val maxFrame = characteristics.get(
                        CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION) ?: Long.MAX_VALUE
                    val exp = (shutterUs!!.coerceIn(1L, 1_000_000L) * 1000L)
                        .coerceIn(expR.lower, minOf(expR.upper, maxFrame))
                    val frameNs = maxOf(desiredNs, exp + 1_000_000L).coerceAtMost(maxFrame)
                    b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                    b.set(CaptureRequest.SENSOR_SENSITIVITY, iso!!.coerceIn(isoR.lower,isoR.upper))
                    b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exp)
                    b.set(CaptureRequest.SENSOR_FRAME_DURATION, frameNs)
                    val boostR = characteristics.get(
                        CameraCharacteristics.CONTROL_POST_RAW_SENSITIVITY_BOOST_RANGE)
                    if (postRawGain > 100 && boostR != null)
                        b.set(CaptureRequest.CONTROL_POST_RAW_SENSITIVITY_BOOST,
                            postRawGain.coerceIn(boostR.lower, boostR.upper))
                }
            } else {
                b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, ev)
                val ranges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                ranges?.firstOrNull { it.lower <= fps && it.upper >= fps }?.let {
                    b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it)
                }
            }
            s.setRepeatingRequest(b.build(), resultCallback, handler)
        } catch (e: Exception) {
            failed = true
            onStatus("Direct capture request failed: ${e.message}")
            Log.e(TAG, "setRepeatingRequest", e)
        }
    }

    override fun stop() {
        if (closed) return
        closed = true
        ready = false
        val done = CountDownLatch(1)
        handler.post {
            try {
                try { session?.stopRepeating() } catch (_: Exception) {}
                try { session?.abortCaptures() } catch (_: Exception) {}
                session?.close(); session = null
                device?.close(); device = null
            } catch (e: Exception) {
                Log.w(TAG, "close: ${e.message}", e)
            } finally {
                done.countDown()
                thread.quitSafely()
            }
        }
        if (!done.await(3, TimeUnit.SECONDS)) Log.w(TAG, "Camera0 direct close timed out")
    }
}
