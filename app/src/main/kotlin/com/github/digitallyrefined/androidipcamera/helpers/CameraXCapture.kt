package com.github.digitallyrefined.androidipcamera.helpers

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Global/modern backend (CameraX). The live stream is fed from ImageAnalysis (YUV → encoder) — it
 * reliably honours the requested resolution (Preview-to-a-custom-Surface picks tiny sizes on some
 * legacy HALs, and Preview is screen-capped anyway). Full-res stills come from ImageCapture
 * (co-bound so no per-shot rebind); if the HAL never completes a takePicture, the freshest analysis
 * frame is encoded as a guaranteed fallback. Some HALs fail to configure any session containing a
 * still surface ("Unable to configure camera ... TimeoutException") — that wedge is detected by the
 * service via [hasProducedFrame] and it falls back to the Camera1 backend.
 * On a strong CPU this runs at the camera's frame rate; on weak/legacy chips the YUV→NV12 copy is
 * the bottleneck (~12fps) — which is why the service auto-prefers Camera1 on LEGACY hardware.
 *
 * [onFrame] is called for every analysis frame and MUST close the ImageProxy.
 */
class CameraXCapture(
    private val ctx: Context,
    private val owner: LifecycleOwner,
    private val front: Boolean,
    private val cameraId: String?,
    private val desired: Size,
    private val previewSurfaceProvider: Preview.SurfaceProvider? = null,
    private val encoderSurfaceProvider: Preview.SurfaceProvider? = null,
    private val onEncoderSurfaceFallback: (() -> Unit)? = null,
    private val onFrame: (ImageProxy) -> Unit
) : CaptureBackend {
    @Volatile override var width = desired.width; private set
    @Volatile override var height = desired.height; private set
    @Volatile override var ready = false; private set
    @Volatile var encoderSurfaceBound = false
        private set

    private var provider: ProcessCameraProvider? = null
    private var camera: androidx.camera.core.Camera? = null
    private var imageCapture: ImageCapture? = null
    // Always co-bound with the stream so a still snaps straight from the bound use case (no rebind).
    private var boundSelector: CameraSelector? = null
    private var analysisUseCase: ImageAnalysis? = null
    private var previewUseCase: Preview? = null
    private var encoderUseCase: Preview? = null
    @Volatile private var released = false
    /** True once the analysis stream has delivered at least one frame. The service uses this to spot
     *  a session that bound but never configured (no frames ever arrive) and fall back to Camera1. */
    @Volatile var hasProducedFrame = false
        private set
    /** Elapsed realtime of the last analysis frame, so the service can tell a stalled session from a live one. */
    @Volatile var lastFrameAtMs = 0L
        private set
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var torchEnabled = false
    /** Whether the bound camera can drive the flash at all — auxiliary lenses (ultra-wide, depth)
     *  report FLASH_INFO_AVAILABLE=false on many multi-lens phones and enableTorch() then fails
     *  asynchronously, which a synchronous try/catch never sees. Lazy because it reads
     *  [logicalCameraId], declared below. */
    override val hasFlashUnit: Boolean by lazy {
        try {
            val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val want = if (front) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
            val id = logicalCameraId?.takeIf { it in cm.cameraIdList }
                ?: cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == want }
            id != null && cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } catch (_: Exception) { true }
    }
    // Cached camera controls, re-applied after a rebind (which replaces the camera control).
    @Volatile private var zoomRatio: Float? = null
    @Volatile private var exposureIndex: Int? = null
    // Cached manual focus (0f..1f) so it can be re-applied after an async (re)bind; null = autofocus.
    @Volatile private var manualFocus: Float? = null
    @Volatile private var manualIso: Int? = null
    @Volatile private var manualShutterUs: Long? = null
    @Volatile private var preciseFocusDiopters: Float? = null
    @Volatile private var lgVendorZoom: Float? = null
    @Volatile private var lgPostRawBoost: Int? = null
    @Volatile private var lgFocusPeaking = false
    @Volatile private var lgPhotoProcessing = "off"
    @Volatile private var lgExtendedIso = false
    private val isLgMain0: Boolean get() = LgG6Main0Profile.isTarget(logicalCameraId)
    @Volatile private var lastNv21CachedAtMs = 0L
    private val main = ContextCompat.getMainExecutor(ctx)
    private val analysisExec = Executors.newSingleThreadExecutor()

    // Latest analysis frame in NV21, so a still can fall back to it when the HAL's takePicture()
    // stalls or the camera is mid-restart — a guaranteed JPEG beats a 503.
    private val nv21Lock = Any()
    private var nv21Buffer: ByteArray? = null
    private var nv21RowBuffer: ByteArray? = null
    @Volatile private var latestNv21: ByteArray? = null
    @Volatile private var latestNv21W = 0
    @Volatile private var latestNv21H = 0
    @Volatile private var latestNv21AtMs = 0L

    private val logicalCameraId: String? = cameraId?.substringBefore(':')
    private val physicalCameraId: String? = cameraId
        ?.substringAfter(':', "")
        ?.takeIf { it.isNotBlank() }

    @OptIn(ExperimentalCamera2Interop::class)
    fun start() {
        val future = ProcessCameraProvider.getInstance(ctx)
        future.addListener({
            try {
                val p = future.get(); provider = p
                // ImageAnalysis ignores a lone ResolutionStrategy unless a matching AspectRatioStrategy
                // is set too; PREFER_HIGHER lifts the analysis cap above the 640x480 default.
                val ratio = if (desired.width.toDouble() / desired.height >= 1.5) AspectRatio.RATIO_16_9 else AspectRatio.RATIO_4_3
                val sel = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy(ratio, AspectRatioStrategy.FALLBACK_RULE_AUTO))
                    .setResolutionStrategy(ResolutionStrategy(desired, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                    .setAllowedResolutionMode(ResolutionSelector.PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE)
                    .build()
                // Pin every use case to a fixed rotation. Stream rotation comes only from the
                // per-camera rotate= control: the encoders ignore imageInfo.rotationDegrees and the
                // GL pipe ignores the surface's sensor transform, so this value must never follow
                // the display rotation.
                val aBuilder = ImageAnalysis.Builder()
                    .setResolutionSelector(sel)
                    .setTargetRotation(Surface.ROTATION_0)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                val analysisInterop = Camera2Interop.Extender(aBuilder)
                physicalCameraId?.let { analysisInterop.setPhysicalCameraId(it) }
                if (isLgMain0 && androidx.preference.PreferenceManager
                        .getDefaultSharedPreferences(ctx)
                        .getBoolean(LgG6Main0Profile.PREF_TELEMETRY, false)) {
                    // Attach once to ImageAnalysis only, not to every Preview surface.
                    LgG6Main0VendorProbe.scan(ctx)
                    analysisInterop.setSessionCaptureCallback(LgG6Main0Telemetry())
                }
                // Some legacy HALs (e.g. this phone's rear cam) stall the analysis stream unless an AE
                // target FPS range is set. Use the WIDEST advertised range, not a fixed lock — AE stays
                // fully auto (drops low for light in the dark, up to 30 in good light).
                aeFpsRange()?.let {
                    analysisInterop.setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it)
                    Log.i(TAG, "AE fps range $it (auto within range)")
                }
                analysisUseCase = aBuilder.build()
                    .also { a -> a.setAnalyzer(analysisExec) { img ->
                        width = img.width; height = img.height
                        hasProducedFrame = true
                        lastFrameAtMs = SystemClock.elapsedRealtime()
                        // A GPU-surface stream must not convert full-resolution YUV to
                        // NV21 on EVERY frame solely to keep a rarely used snapshot backup.
                        // Keep a recent fallback frame, sampled at most once per 1.5s.
                        val cacheNow = SystemClock.elapsedRealtime()
                        if (cacheNow - lastNv21CachedAtMs >= 1500L) {
                            try {
                                synchronized(nv21Lock) {
                                    val crop = img.cropRect
                                    val nv21 = convertYUV420toNV21(img, nv21Buffer, nv21RowBuffer)
                                    nv21Buffer = nv21
                                    if (nv21RowBuffer == null || nv21RowBuffer!!.size < img.planes[0].rowStride) {
                                        nv21RowBuffer = ByteArray(img.planes[0].rowStride)
                                    }
                                    latestNv21 = nv21
                                    latestNv21W = crop.width(); latestNv21H = crop.height()
                                    latestNv21AtMs = System.currentTimeMillis()
                                    lastNv21CachedAtMs = cacheNow
                                }
                            } catch (e: Exception) { Log.e(TAG, "frame cache: ${e.message}") }
                        }
                        onFrame(img)
                    } }
                // ImageCapture stays co-bound from start(): a still then snaps straight from the bound
                // use case, avoiding the per-shot unbind/rebind that hangs the session configuration on
                // some HALs. MINIMIZE_LATENCY captures from the repeating stream instead of asking the
                // HAL to reconfigure the session for a full-sensor still surface.
                val imageCaptureBuilder = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setTargetRotation(Surface.ROTATION_0)
                physicalCameraId?.let { Camera2Interop.Extender(imageCaptureBuilder).setPhysicalCameraId(it) }
                imageCapture = imageCaptureBuilder.build()
                previewSurfaceProvider?.let { pv ->
                    val previewBuilder = Preview.Builder().setResolutionSelector(sel)
                        .setTargetRotation(Surface.ROTATION_0)
                    physicalCameraId?.let { Camera2Interop.Extender(previewBuilder).setPhysicalCameraId(it) }
                    previewUseCase = previewBuilder.build().also { it.setSurfaceProvider(pv) }
                }
                encoderSurfaceProvider?.let { pv ->
                    val previewBuilder = Preview.Builder().setResolutionSelector(sel)
                        .setTargetRotation(Surface.ROTATION_0)
                    physicalCameraId?.let { Camera2Interop.Extender(previewBuilder).setPhysicalCameraId(it) }
                    encoderUseCase = previewBuilder.build().also { it.setSurfaceProvider(pv) }
                }
                boundSelector = logicalCameraId?.let { requestedId ->
                    CameraSelector.Builder()
                        .addCameraFilter { cameraInfos ->
                            cameraInfos.filter { Camera2CameraInfo.from(it).cameraId == requestedId }
                        }
                        .build()
                } ?: if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                rebind()
                ready = true
                Log.i(TAG, "bound desired ${desired.width}x${desired.height} front=$front cameraId=${cameraId ?: "default"}")
            } catch (e: Exception) { Log.e(TAG, "start: ${e.message}") }
        }, main)
    }

    /** (Re)bind the streaming use cases on the main thread. ImageCapture is always co-bound so a still
     *  can snap directly from the already-bound use case — never a per-shot unbind/rebind cycle (which
     *  hangs the session configuration on some HALs). */
    private fun rebind() {
        if (released) return
        val p = provider ?: return
        val sel = boundSelector ?: return
        val analysis = analysisUseCase ?: return
        val cases = mutableListOf<androidx.camera.core.UseCase>()
        previewUseCase?.let { cases.add(it) }
        encoderUseCase?.let { cases.add(it) }
        cases.add(analysis)
        imageCapture?.let { cases.add(it) }
        p.unbindAll()
        // On slow HALs (e.g. Exynos 7420) the previous camera close() can take 400ms–6s.
        // bindToLifecycle() may fail if the HAL hasn't finished tearing down. Retry a few
        // times before falling back to degraded surface combinations.
        val maxRetries = 3
        var lastException: Exception? = null
        for (attempt in 1..maxRetries) {
            try {
                camera = p.bindToLifecycle(owner, sel, *cases.toTypedArray())
                encoderSurfaceBound = encoderUseCase != null && cases.any { it === encoderUseCase }
                lastException = null
                break
            } catch (e: Exception) {
                lastException = e
                if (attempt < maxRetries) {
                    Log.w(TAG, "bindToLifecycle failed (attempt $attempt/$maxRetries): ${e.message}; retrying in 500ms...")
                    Thread.sleep(500)
                } else {
                    Log.e(TAG, "bindToLifecycle failed after $maxRetries attempts: ${e.message}")
                }
            }
        }
        if (lastException != null) {
            // Co-binding the encoder surface with a still stream can exceed some HALs' concurrent-stream
            // limit. Rather than lose the camera, shed the encoder surface / screen preview and keep
            // ImageAnalysis + ImageCapture (software YUV stream, full-res stills intact).
            if (encoderUseCase != null || previewUseCase != null) {
                Log.w(TAG, "Attempting fallback: dropping encoder surface / preview...")
                try {
                    encoderUseCase = null
                    previewUseCase = null
                    encoderSurfaceBound = false
                    p.unbindAll()
                    camera = p.bindToLifecycle(owner, sel, analysis, imageCapture ?: throw RuntimeException("ImageCapture not initialized"))
                    onEncoderSurfaceFallback?.invoke()
                } catch (e: Exception) {
                    Log.e(TAG, "fallback rebind failed: ${e.message}")
                    throw e
                }
            } else {
                // Last resort: a lone ImageCapture should always be bindable.
                Log.w(TAG, "Attempting fallback: binding ImageCapture alone...")
                try {
                    p.unbindAll()
                    camera = p.bindToLifecycle(owner, sel, imageCapture ?: throw RuntimeException("ImageCapture not initialized"))
                    encoderSurfaceBound = false
                } catch (e: Exception) {
                    Log.e(TAG, "ImageCapture-alone rebind failed: ${e.message}")
                    throw e
                }
            }
        }
        // Re-apply cached controls, since rebinding replaces the camera control.
        val cc = camera?.cameraControl
        if (torchEnabled && hasFlashUnit) try { cc?.enableTorch(true) } catch (_: Exception) {}
        zoomRatio?.let { applyZoomWithRetry(it) }
        exposureIndex?.let { try { cc?.setExposureCompensationIndex(it) } catch (_: Exception) {} }
        applyAdvancedCaptureRequest()
    }

    /**
     * Apply a zoom ratio, retrying briefly on failure. Right after a (re)bind, CameraX's reported
     * zoom range (from CONTROL_ZOOM_RATIO_RANGE) can momentarily reject an otherwise-valid ratio —
     * this mostly bites sub-1.0x (ultra-wide) ratios, since >=1.0x is almost always within the
     * camera's default range and succeeds on the first try. Without this retry, a saved sub-1.0x
     * zoom would silently fail to restore on app startup.
     */
    private fun applyZoomWithRetry(ratio: Float, attemptsLeft: Int = 10) {
        val cc = camera?.cameraControl ?: return
        try {
            val future = cc.setZoomRatio(ratio)
            future.addListener({
                try {
                    future.get()
                } catch (e: Exception) {
                    if (attemptsLeft > 0) {
                        mainHandler.postDelayed({ applyZoomWithRetry(ratio, attemptsLeft - 1) }, 150)
                    } else {
                        Log.e(TAG, "zoom $ratio failed after retries: ${e.message}")
                    }
                }
            }, main)
        } catch (e: Exception) {
            if (attemptsLeft > 0) {
                mainHandler.postDelayed({ applyZoomWithRetry(ratio, attemptsLeft - 1) }, 150)
            } else {
                Log.e(TAG, "zoom $ratio rejected after retries: ${e.message}")
            }
        }
    }

    override fun captureStill(onJpeg: (ByteArray?) -> Unit) {
        val ic = imageCapture ?: return onJpeg(null)
        if (provider == null || released) return onJpeg(null)
        // ImageCapture stays bound from start(), so a still needs no rebind — per-shot unbind/rebind
        // cycles hang the session configuration on some HALs. Just snap from the bound use case.
        main.execute {
            if (released) return@execute onJpeg(null)
            var finished = false
            fun done(result: ByteArray?) {
                if (finished) return
                finished = true
                mainHandler.removeCallbacksAndMessages(null)
                onJpeg(result)
            }
            // Watchdog: if the HAL never calls back, fall back to the live analysis frame so the
            // snapshot still succeeds.
            mainHandler.postDelayed({
                if (!finished) {
                    Log.w(TAG, "capture timeout — falling back to analysis frame")
                    done(frameFallbackJpeg())
                }
            }, CAPTURE_TIMEOUT_MS)
            val shoot = Runnable {
                if (finished) return@Runnable
                ic.takePicture(main, object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        val b = try {
                            val buf = image.planes[0].buffer
                            ByteArray(buf.remaining()).also { buf.get(it) }
                        } catch (e: Exception) { Log.e(TAG, "read: ${e.message}"); null } finally { image.close() }
                        done(b)
                    }
                    override fun onError(e: ImageCaptureException) {
                        Log.e(TAG, "capture: ${e.message}")
                        done(frameFallbackJpeg())
                    }
                })
            }
            // Wait for the re-applied zoom to actually take effect, else the still would be captured
            // at the reset (1.0x) zoom.
            val zoomFuture = zoomRatio?.let { r -> try { camera?.cameraControl?.setZoomRatio(r) } catch (_: Exception) { applyZoomWithRetry(r); null } }
            if (zoomFuture != null) zoomFuture.addListener(shoot, main) else shoot.run()
        }
    }

    /** JPEG encoded from the latest analysis frame (if still fresh), or null. Guarantees a snapshot
     *  whenever the camera is producing frames. */
    private fun frameFallbackJpeg(): ByteArray? {
        val nv: ByteArray; val w: Int; val h: Int
        synchronized(nv21Lock) {
            val cached = latestNv21 ?: run { Log.w(TAG, "fallback: no cached frame"); return null }
            val age = System.currentTimeMillis() - latestNv21AtMs
            if (age > FRAME_FALLBACK_MAX_AGE_MS) {
                Log.w(TAG, "fallback: cached frame stale (${age}ms)")
                return null
            }
            nv = cached.copyOf()   // copy under the lock so the analyzer can't overwrite mid-encode
            w = latestNv21W; h = latestNv21H
        }
        if (w == 0 || h == 0) return null
        return try {
            val quality = DeviceMemoryHelper.mjpegJpegQuality(ctx)
            convertNV21toJPEG(nv, w, h, quality)
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "frame fallback OOM")
            try { System.gc() } catch (_: Exception) {}
            null
        } catch (e: Exception) {
            Log.e(TAG, "frame fallback: ${e.message}")
            null
        }
    }

    override fun getTorch(): Boolean = torchEnabled
    override fun setTorch(on: Boolean) {
        // Lenses without a flash unit must report OFF even when asked for ON, so the service knows
        // to route the torch through a flash-capable camera instead.
        torchEnabled = on && hasFlashUnit
        if (on && !hasFlashUnit) return
        try {
            val future = camera?.cameraControl?.enableTorch(on) ?: return
            // enableTorch reports failure through the future, not by throwing — observe it or the
            // failure is completely silent and internal state drifts from the real LED.
            future.addListener({
                try {
                    future.get()
                } catch (e: Exception) {
                    Log.w(TAG, "enableTorch($on): ${e.message}")
                    if (on) torchEnabled = false
                }
            }, main)
        } catch (e: Exception) {
            Log.w(TAG, "enableTorch($on): ${e.message}")
            if (on) torchEnabled = false
        }
    }
    override fun setExposure(ev: Int) {
        exposureIndex = ev
        if (manualIso == null) try { camera?.cameraControl?.setExposureCompensationIndex(ev) } catch (_: Exception) {}
    }
    override fun setZoom(ratio: Float) { zoomRatio = ratio; applyZoomWithRetry(ratio) }
    @OptIn(ExperimentalCamera2Interop::class)
    override fun triggerAutoFocus() {
        try {
            val cam = camera ?: return
            // Drop any manual-focus override, else the lingering CONTROL_AF_MODE_OFF
            // defeats the AF scan below.
            manualFocus = null
            preciseFocusDiopters = null
            applyAdvancedCaptureRequest()
            val pt = SurfaceOrientedMeteringPointFactory(1f, 1f).createPoint(0.5f, 0.5f)
            cam.cameraControl.startFocusAndMetering(
                FocusMeteringAction.Builder(pt, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
                    .setAutoCancelDuration(3, TimeUnit.SECONDS).build())
        } catch (e: Exception) { Log.e(TAG, "AF: ${e.message}") }
    }

    /**
     * One request owner for AF, manual sensor and guarded LG ID0 OEM features.
     * Do not call clearCaptureRequestOptions() from focus/zoom: it would erase AE.
     */
    @OptIn(ExperimentalCamera2Interop::class)
    private fun applyAdvancedCaptureRequest() {
        val cam = camera ?: return
        try {
            val info = Camera2CameraInfo.from(cam.cameraInfo)
            val options = CaptureRequestOptions.Builder()
            val minFocusD = info.getCameraCharacteristic(
                CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
            val focusD = preciseFocusDiopters ?: manualFocus?.let { it * minFocusD }
            if (focusD != null && minFocusD > 0f) {
                options.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                options.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE,
                    focusD.coerceIn(0f, minFocusD))
            }
            val iso = manualIso
            val shutterUs = manualShutterUs
            if (iso != null && shutterUs != null) {
                val caps = info.getCameraCharacteristic(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                val isoRange = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
                val expRange = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
                if (caps?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) == true
                    && isoRange != null && expRange != null) {
                    // LG camera0 advertises ISO 50..800. Values above 800 are NOT
                    // authenticated by legacy Camera1 ID3's lg-iso-values string.
                    // Auto Camera0 results can report ISO ~1400 while manual metadata
                    // says <=800. Test manual 1600/3200 ONLY when user explicitly
                    // enables the experimental flag; report requested vs actual ISO.
                    val actualIso = if (isLgMain0 && lgExtendedIso)
                        iso.coerceIn(isoRange.lower, 3200)
                    else iso.coerceIn(isoRange.lower, isoRange.upper)
                    val desiredNs = shutterUs.coerceIn(1L, 1_000_000L) * 1000L
                    val actualNs = desiredNs.coerceIn(expRange.lower, expRange.upper)
                    options.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                    options.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, actualIso)
                    options.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, actualNs)
                    if (isLgMain0) {
                        val fps = androidx.preference.PreferenceManager.getDefaultSharedPreferences(ctx)
                            .getString("stream_fps", "20")?.toIntOrNull()?.coerceIn(1, 60) ?: 20
                        // Preserve the requested shutter by reducing effective FPS when
                        // it is longer than nominal frame time. Never silently cut exposure.
                        val frameNs = maxOf(1_000_000_000L / fps, actualNs + 1_000_000L)
                        options.setCaptureRequestOption(CaptureRequest.SENSOR_FRAME_DURATION, frameNs)
                        val boost = lgPostRawBoost
                        val boostRange = info.getCameraCharacteristic(
                            CameraCharacteristics.CONTROL_POST_RAW_SENSITIVITY_BOOST_RANGE)
                        if (boost != null && boostRange != null)
                            options.setCaptureRequestOption(
                                CaptureRequest.CONTROL_POST_RAW_SENSITIVITY_BOOST,
                                boost.coerceIn(boostRange.lower, boostRange.upper))
                        if (actualIso != iso) Log.i(TAG, "LG0 ISO clamped $iso to $actualIso (Camera2 range)")
                    }
                } else Log.w(TAG, "Manual sensor controls unavailable for Camera2 ID=${logicalCameraId}")
            }
            if (isLgMain0) {
                if (lgPhotoProcessing == "fast" || lgPhotoProcessing == "hq") {
                    val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                    val chars = cm.getCameraCharacteristics(LgG6Main0Profile.CAMERA_ID)
                    val profile = if (lgPhotoProcessing == "hq")
                        LgG6PreviewProfile.PHOTO_HQ else LgG6PreviewProfile.PHOTO_FAST
                    LgG6PhotoPreviewTuning.applyCameraX(options, chars, profile)
                }
                val vendorKeys = LgG6Main0VendorProbe.capabilities(ctx)
                if (lgFocusPeaking && LgG6Main0Profile.VENDOR_PEAKING in vendorKeys) {
                    try {
                        options.setCaptureRequestOption(
                            CaptureRequest.Key(LgG6Main0Profile.VENDOR_PEAKING, Int::class.javaObjectType), 1)
                    } catch (e: Exception) { Log.w(TAG, "LG peaking rejected: ${e.message}") }
                }
                lgVendorZoom?.let { z ->
                    if (LgG6Main0Profile.VENDOR_ZOOM in vendorKeys) {
                        try {
                            val active = info.getCameraCharacteristic(
                                CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                            if (active != null) {
                                val crop = LgG6Main0Math.sensorCrop(
                                    LgPixelRect(active.left, active.top, active.right, active.bottom),
                                    z.toDouble())
                                options.setCaptureRequestOption(CaptureRequest.SCALER_CROP_REGION,
                                    android.graphics.Rect(crop.left, crop.top, crop.right, crop.bottom))
                            }
                            options.setCaptureRequestOption(
                                CaptureRequest.Key(LgG6Main0Profile.VENDOR_ZOOM, Float::class.javaObjectType), z)
                        } catch (e: Exception) { Log.w(TAG, "LG vendor zoom rejected: ${e.message}") }
                    } else Log.w(TAG, "LG vendor zoom not advertised on Camera ID0; disabled")
                }
            }
            Camera2CameraControl.from(cam.cameraControl).captureRequestOptions = options.build()
        } catch (e: Exception) { Log.e(TAG, "Camera2 manual settings: ${e.message}", e) }
    }

    override fun setManualFocus(distance: Float) {
        preciseFocusDiopters = null
        manualFocus = distance.takeIf { it >= 0f }?.coerceIn(0f, 1f)
        applyAdvancedCaptureRequest()
    }

    override fun setFocusDiopters(diopters: Float?) {
        if (!isLgMain0) return
        manualFocus = null
        preciseFocusDiopters = diopters?.takeIf { it.isFinite() }?.coerceIn(0f, 10f)
        applyAdvancedCaptureRequest()
    }

    override fun setManualExposure(iso: Int?, shutterUs: Long?) {
        manualIso = if (iso != null && shutterUs != null) iso.coerceIn(25, 204800) else null
        manualShutterUs = if (iso != null && shutterUs != null) shutterUs.coerceIn(1L, 1_000_000L) else null
        applyAdvancedCaptureRequest()
        if (manualIso == null) exposureIndex?.let {
            try { camera?.cameraControl?.setExposureCompensationIndex(it) } catch (_: Exception) {}
        }
    }

    override fun setLgVendorZoom(zoom: Float?) {
        if (!isLgMain0) return
        lgVendorZoom = zoom?.takeIf { it.isFinite() }?.coerceIn(1f, 8f)
        applyAdvancedCaptureRequest()
    }

    override fun setLgPostRawBoost(percent: Int?) {
        if (!isLgMain0) return
        lgPostRawBoost = percent?.coerceIn(100, 400)
        applyAdvancedCaptureRequest()
    }

    override fun setLgFocusPeaking(enabled: Boolean) {
        if (!isLgMain0) return
        lgFocusPeaking = enabled
        applyAdvancedCaptureRequest()
    }

    override fun setLgPhotoProcessing(mode: String) {
        if (!isLgMain0) return
        lgPhotoProcessing = mode.takeIf { it in listOf("off", "fast", "hq") } ?: "off"
        applyAdvancedCaptureRequest()
    }

    override fun setLgExtendedIso(enabled: Boolean) {
        if (!isLgMain0) return
        lgExtendedIso = enabled
        applyAdvancedCaptureRequest()
    }

    /** The camera's WIDEST advertised AE FPS range — leaves auto-exposure fully free while giving legacy
     *  HALs the explicit range they need to not stall the analysis stream. Not a fixed-fps lock. */
    private fun aeFpsRange(): Range<Int>? = try {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val want = if (front) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
        val id = logicalCameraId?.takeIf { it in cm.cameraIdList }
            ?: cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == want }
            ?: cm.cameraIdList.first()
        cm.getCameraCharacteristics(id).get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?.maxWithOrNull(compareBy({ it.upper - it.lower }, { it.upper }))
    } catch (e: Exception) { Log.e(TAG, "aeRange: ${e.message}"); null }

    override fun stop() {
        ready = false
        released = true               // block any in-flight capture callback from touching the camera
        mainHandler.removeCallbacksAndMessages(null)
        try { provider?.unbindAll() } catch (_: Exception) {}
        try { analysisExec.shutdown() } catch (_: Exception) {}
        provider = null; camera = null; imageCapture = null
    }

    companion object {
        private const val TAG = "CameraXCapture"
        /** Give a full-res takePicture this long before falling back to the analysis frame. */
        private const val CAPTURE_TIMEOUT_MS = 4_000L
        /** Analysis-frame fallback is only served if the cached frame is at most this old. */
        private const val FRAME_FALLBACK_MAX_AGE_MS = 6_000L
    }
}
