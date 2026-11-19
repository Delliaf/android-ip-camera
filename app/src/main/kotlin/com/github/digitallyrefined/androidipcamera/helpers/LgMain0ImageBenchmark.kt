package com.github.digitallyrefined.androidipcamera.helpers

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.util.Size
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Explicit user-initiated, offline Camera2 benchmark for stock LG-H872 MAIN ID0.
 *
 * It owns the camera exclusively (MainActivity releases StreamingService first).
 * Test images are the TRUE 4.5x effective FoV at native information scale:
 *   ISP -> camera crop + downsample; CPU -> unzoomed YUV full source + software ROI.
 * No artificial 12MP claim after the crop. No root, uploads or vendor .so calls.
 */
internal class LgMain0ImageBenchmark(private val context: Context) {
    companion object { private const val TAG = "LGMain0Benchmark" }
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val chars = cameraManager.getCameraCharacteristics("0")
    private val cameraThread = HandlerThread("LG0-Benchmark-Camera").apply { start() }
    private val handler = Handler(cameraThread.looper)
    private val pendingImages = ArrayBlockingQueue<Image>(2)
    private val cancelled = AtomicBoolean(false)
    private val monitor = DevicePerformanceMonitor(context)
    @Volatile private var collecting = false
    @Volatile private var lastResult: TotalCaptureResult? = null
    private val resultLock = Any()
    private val resultsByTimestamp = LinkedHashMap<Long, TotalCaptureResult>()
    private data class CapturedSample(val frame: LgBenchmarkFrame, val timestampNs: Long)
    @Volatile private var device: CameraDevice? = null
    @Volatile private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null

    data class Outcome(val archive: File, val completed: Int, val attempted: Int,
                       val stopReason: String, val imageSize: Size)

    fun cancel() { cancelled.set(true) }
    fun close() {
        cancelled.set(true)
        collecting = false
        try { session?.stopRepeating() } catch (_: Exception) {}
        try { session?.close() } catch (_: Exception) {}
        session = null
        try { reader?.close() } catch (_: Exception) {}
        reader = null
        try { device?.close() } catch (_: Exception) {}
        device = null
        while (true) {
            val img = pendingImages.poll() ?: break
            try { img.close() } catch (_: Exception) {}
        }
        cameraThread.quitSafely()
    }

    private val imageListener = ImageReader.OnImageAvailableListener { source ->
        val image = try { source.acquireLatestImage() } catch (_: Exception) { null } ?: return@OnImageAvailableListener
        if (!collecting || cancelled.get() || !pendingImages.offer(image)) image.close()
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(sess: CameraCaptureSession,
            request: CaptureRequest, result: TotalCaptureResult) {
            lastResult = result
            result.get(CaptureResult.SENSOR_TIMESTAMP)?.let { timestamp ->
                synchronized(resultLock) {
                    resultsByTimestamp[timestamp] = result
                    while (resultsByTimestamp.size > 80)
                        resultsByTimestamp.remove(resultsByTimestamp.keys.first())
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun openCamera(): CameraDevice {
        require(context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
        if (!LgG6Main0Profile.isDevice()) error("Supported only on LG-H872 Camera2 main ID0")
        val latch = CountDownLatch(1)
        var failure: String? = null
        cameraManager.openCamera("0", object : CameraDevice.StateCallback() {
            override fun onOpened(c: CameraDevice) {
                if (cancelled.get()) c.close() else device = c
                latch.countDown()
            }
            override fun onDisconnected(c: CameraDevice) {
                c.close(); failure = "Camera0 disconnected"; latch.countDown()
            }
            override fun onError(c: CameraDevice, code: Int) {
                c.close(); failure = "Camera0 open error code=$code"; latch.countDown()
            }
        }, handler)
        if (!latch.await(12, TimeUnit.SECONDS)) error("Camera0 open timeout; stop other camera apps")
        return device ?: error(failure ?: "Camera0 not opened")
    }

    private fun makeSession(camera: CameraDevice, size: Size): Boolean {
        session?.close(); session = null
        reader?.close(); reader = null
        val r = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 3)
        reader = r
        r.setOnImageAvailableListener(imageListener, handler)
        val latch = CountDownLatch(1)
        var ok = false
        @Suppress("DEPRECATION")
        camera.createCaptureSession(listOf(r.surface), object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                if (cancelled.get()) s.close() else { session = s; ok = true }
                latch.countDown()
            }
            override fun onConfigureFailed(s: CameraCaptureSession) {
                s.close(); latch.countDown()
            }
        }, handler)
        latch.await(10, TimeUnit.SECONDS)
        if (!ok) {
            r.close(); reader = null
        }
        return ok
    }

    private fun sizes(): List<Size> {
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: error("Camera0 has no output configuration")
        val sizes = map.getOutputSizes(ImageFormat.YUV_420_888)
            ?.filter { it.width > 0 && it.height > 0 }.orEmpty()
        require(sizes.isNotEmpty()) { "Camera0 exposes no YUV_420_888 output" }
        return sizes.sortedByDescending { it.width.toLong() * it.height }
    }

    private fun requestFor(c: LgBenchmarkCase, size: Size, square: Boolean): CaptureRequest {
        val camera = device ?: error("Camera closed")
        val builder = camera.createCaptureRequest(c.template)
        builder.addTarget(reader?.surface ?: error("YUV reader closed"))
        builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
        val active = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        if (c.pipeline == "isp" && active != null) {
            // Hardware zoom is center only on LG ID0. Use EXACTLY 4.5x once.
            val crop = LgG6Main0Math.sensorCrop(
                LgPixelRect(active.left, active.top, active.right, active.bottom),
                LgBenchmarkPlan.ZOOM, size.width, size.height)
            builder.set(CaptureRequest.SCALER_CROP_REGION,
                Rect(crop.left, crop.top, crop.right, crop.bottom))
        } else if (active != null) {
            // Keep ISP zoom at 1x; CPU crops a 4.5x centered YUV ROI later.
            builder.set(CaptureRequest.SCALER_CROP_REGION, active)
        }
        if (c.nr != null) builder.set(CaptureRequest.NOISE_REDUCTION_MODE, c.nr)
        if (c.edge != null) builder.set(CaptureRequest.EDGE_MODE, c.edge)
        if (c.aberration != null)
            builder.set(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE, c.aberration)
        if (c.awb != null) builder.set(CaptureRequest.CONTROL_AWB_MODE, c.awb)
        val minFocus = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        if (c.focusD != null && minFocus > 0f) {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, c.focusD.coerceIn(0f, minFocus))
        } else builder.set(CaptureRequest.CONTROL_AF_MODE,
            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        if (c.iso != null && c.shutterUs != null) {
            val isoRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            val expRange = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            if (isoRange != null && expRange != null) {
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                builder.set(CaptureRequest.SENSOR_SENSITIVITY,
                    c.iso.coerceIn(isoRange.lower, isoRange.upper))
                val exposure = (c.shutterUs * 1000L).coerceIn(expRange.lower, expRange.upper)
                builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposure)
                val maxFrame = chars.get(CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION)
                    ?: Long.MAX_VALUE
                builder.set(CaptureRequest.SENSOR_FRAME_DURATION,
                    maxOf(66_666_667L, exposure + 1_000_000L).coerceAtMost(maxFrame))
            }
        } else builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        return builder.build()
    }

    private fun flushImages() {
        collecting = false
        while (true) {
            val stale = pendingImages.poll() ?: break
            stale.close()
        }
    }

    private fun captureNormalized(cpu: Boolean, square: Boolean,
                                  timeoutMs: Long = 9000): CapturedSample {
        flushImages()
        collecting = true
        try {
            val image = pendingImages.poll(timeoutMs, TimeUnit.MILLISECONDS)
                ?: error("YUV output timeout; HAL stalled or no compatible stream")
            return image.use {
                CapturedSample(LgBenchmarkImaging.normalize(it, LgBenchmarkPlan.ZOOM, cpu, square),
                    it.timestamp)
            }
        } finally { collecting = false; flushImages() }
    }

    private fun resultFor(timestampNs: Long): Pair<TotalCaptureResult?, Boolean> {
        for (i in 0..8) {
            val exact = synchronized(resultLock) { resultsByTimestamp[timestampNs] }
            if (exact != null) return exact to true
            if (i < 8) Thread.sleep(45)
        }
        return lastResult to false
    }

    private fun resultJson(result: TotalCaptureResult?, request: CaptureRequest): JSONObject =
        JSONObject().apply {
            fun intValue(key: android.hardware.camera2.CaptureResult.Key<Int>): Any =
                result?.get(key) ?: JSONObject.NULL
            fun longValue(key: android.hardware.camera2.CaptureResult.Key<Long>): Any =
                result?.get(key) ?: JSONObject.NULL
            put("sensorIso", intValue(CaptureResult.SENSOR_SENSITIVITY))
            put("shutterNs", longValue(CaptureResult.SENSOR_EXPOSURE_TIME))
            put("frameNs", longValue(CaptureResult.SENSOR_FRAME_DURATION))
            put("noiseReduction", intValue(CaptureResult.NOISE_REDUCTION_MODE))
            put("edgeMode", intValue(CaptureResult.EDGE_MODE))
            put("awb", intValue(CaptureResult.CONTROL_AWB_MODE))
            put("ae", intValue(CaptureResult.CONTROL_AE_MODE))
            put("af", intValue(CaptureResult.CONTROL_AF_MODE))
            put("focusDiopters", result?.get(CaptureResult.LENS_FOCUS_DISTANCE) ?: JSONObject.NULL)
            put("scalerCrop", result?.get(CaptureResult.SCALER_CROP_REGION)?.toShortString()
                ?: JSONObject.NULL)
            put("requestIso", request.get(CaptureRequest.SENSOR_SENSITIVITY) ?: JSONObject.NULL)
            put("requestCrop", request.get(CaptureRequest.SCALER_CROP_REGION)?.toShortString()
                ?: JSONObject.NULL)
            put("captureResultTimestampNs",
                result?.get(CaptureResult.SENSOR_TIMESTAMP) ?: JSONObject.NULL)
            put("readbackNote", "Camera2 SENSOR_TIMESTAMP matched to YUV Image.timestamp where supported; otherwise latest completed CaptureResult (see captureResultTimestampMatched)")
        }

    private fun metricsJson(frame: LgBenchmarkFrame, prev: LgBenchmarkFrame?): JSONObject {
        val m = LgBenchmarkImaging.metrics(frame.gray, frame.width, frame.height, prev?.gray)
        return JSONObject().apply {
            put("meanLuma", m.brightness)
            put("lumaStdDev", m.contrastStdDev)
            put("blackClippingPct", m.blackClippingPercent)
            put("whiteClippingPct", m.whiteClippingPercent)
            put("lumaP01", m.p01); put("lumaP99", m.p99)
            put("edgeEnergy", m.edgeEnergy)
            put("laplacianVariance", m.laplacianVariance)
            put("temporalLumaAbsDiff", m.temporalLumaDifference ?: JSONObject.NULL)
        }
    }

    private fun caseJson(c: LgBenchmarkCase, index: Int): JSONObject =
        JSONObject().apply {
            put("index", index); put("id", c.id); put("pipeline", c.pipeline)
            put("template", c.template); put("zoom", LgBenchmarkPlan.ZOOM)
            put("requestedIso", c.iso ?: JSONObject.NULL)
            put("requestedShutterUs", c.shutterUs ?: JSONObject.NULL)
            put("requestedFocusD", c.focusD ?: JSONObject.NULL)
            put("requestedNR", c.nr ?: JSONObject.NULL)
            put("requestedEdge", c.edge ?: JSONObject.NULL)
            put("requestedAberration", c.aberration ?: JSONObject.NULL)
            put("requestedAWB", c.awb ?: JSONObject.NULL)
        }

    private fun manifest(size: Size, deep: Boolean, square: Boolean,
                         candidates: List<Size>, cases: Int): JSONObject {
        val range = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val exposure = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val active = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        return JSONObject().apply {
            put("model", Build.MODEL); put("androidSdk", Build.VERSION.SDK_INT)
            put("buildDisplay", Build.DISPLAY)
            put("cameraId", "0")
            put("zoom", LgBenchmarkPlan.ZOOM)
            put("cropShape", if (square) "1:1" else "4:3")
            put("captureFormat", "YUV_420_888")
            put("maxNegotiatedYuvSize", "${size.width}x${size.height}")
            put("allAdvertisedYuvSizes", JSONArray(candidates.map { "${it.width}x${it.height}" }))
            put("sensorActiveArray", active?.toShortString() ?: JSONObject.NULL)
            put("sensorOrientationDegrees",
                chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: JSONObject.NULL)
            put("lensMinFocusDiopters",
                chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: JSONObject.NULL)
            put("maximumDigitalZoom",
                chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: JSONObject.NULL)
            put("maxFrameDurationNs",
                chars.get(CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION) ?: JSONObject.NULL)
            put("advertisedNoiseReductionModes", JSONArray(
                (chars.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)
                    ?: intArrayOf()).toList()))
            put("advertisedEdgeModes", JSONArray(
                (chars.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES) ?: intArrayOf()).toList()))
            put("advertisedAberrationModes", JSONArray(
                (chars.get(CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES)
                    ?: intArrayOf()).toList()))
            put("advertisedAwbModes", JSONArray(
                (chars.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES) ?: intArrayOf()).toList()))
            put("manualIsoRange", range?.toString() ?: JSONObject.NULL)
            put("exposureNsRange", exposure?.toString() ?: JSONObject.NULL)
            put("testCount", cases)
            put("deep", deep)
            put("notes", "Fixed 4.5x FoV; ISP camera crop vs CPU post-crop. " +
                "Samples normalized to original source information size, not upscaled encoder pixels. " +
                "Sharpness metrics are scene-sensitive; scores do not prove perceptual quality.")
        }
    }

    /**
     * Must execute on a dedicated background thread. The Activity exports the resulting
     * ZIP via ACTION_CREATE_DOCUMENT; never creates an externally reachable endpoint.
     */
    fun run(deep: Boolean, square: Boolean, progress: (String) -> Unit): Outcome {
        val directory = File(context.getExternalFilesDir(null) ?: context.filesDir, "camera-benchmarks")
        check(directory.exists() || directory.mkdirs()) { "Cannot create benchmark directory" }
        val zipFile = File(directory, "LG-H872-main0-4p5x-${System.currentTimeMillis()}.zip")
        var completed = 0; var attempted = 0; var stopReason = "finished"
        var chosen = Size(0, 0)
        val rows = JSONArray()
        val allSizes = sizes()
        val noise = chars.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)
            ?: intArrayOf()
        val edges = chars.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES) ?: intArrayOf()
        val aber = chars.get(CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES)
            ?: intArrayOf()
        val awb = chars.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES) ?: intArrayOf()
        val focusLimit = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        val isoRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val cases = LgBenchmarkPlan.create(deep, noise, edges, aber, awb,
            isoRange?.lower ?: 50, isoRange?.upper ?: 800, 0f, focusLimit)
        val begunAt = SystemClock.elapsedRealtime()
        ZipOutputStream(FileOutputStream(zipFile).buffered()).use { zip ->
            fun write(path: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(bytes)
                zip.closeEntry()
            }
            try {
                progress("Opening Camera2 main0, inspecting maximum YUV sizes")
                var camera: CameraDevice? = null
                var openError: Exception? = null
                for (trial in 0..1) {
                    try { camera = openCamera(); break }
                    catch (e: Exception) { openError = e; Thread.sleep(700) }
                }
                val opened = camera ?: throw (openError ?: IllegalStateException("Camera0 unavailable"))
                for (size in allSizes) {
                    if (cancelled.get()) break
                    progress("Probing YUV ${size.width}x${size.height}")
                    if (!makeSession(opened, size)) continue
                    // Some OEM HALs accept a high-res session but NEVER emit an
                    // ImageReader frame. Verify actual delivery before selecting it.
                    try {
                        flushImages()
                        val baseline = requestFor(LgBenchmarkCase(1, "cpu"), size, square)
                        session?.setRepeatingRequest(baseline, captureCallback, handler)
                            ?: error("Camera0 session unavailable")
                        collecting = true
                        val probe = pendingImages.poll(6, TimeUnit.SECONDS)
                        collecting = false
                        if (probe != null) {
                            probe.use { img ->
                                progress("Confirmed YUV frame ${img.width}x${img.height}")
                            }
                            chosen = size
                            break
                        }
                        progress("Session accepted ${size.width}x${size.height}, but no YUV frame; trying smaller")
                    } catch (e: Exception) {
                        progress("YUV ${size.width}x${size.height} failed: ${e.message}; trying smaller")
                    } finally {
                        collecting = false
                        flushImages()
                    }
                }
                check(chosen.width > 0) { "No Camera0 YUV size produced a real frame" }
                val source = chosen
                progress("Camera0 negotiated ${source.width}x${source.height}; running ${cases.size} 4.5x cases")
                write("device.json", manifest(source, deep, square, allSizes, cases.size)
                    .toString(2).toByteArray(Charsets.UTF_8))
                for ((index, c) in cases.withIndex()) {
                    if (cancelled.get()) { stopReason = "cancelled_by_user"; break }
                    if (SystemClock.elapsedRealtime() - begunAt > if (deep) 480_000 else 180_000) {
                        stopReason = "time_limit"; break
                    }
                    val temp = monitor.sample()
                    if (temp.temperatureCelsius != null &&
                        ((temp.temperatureSource == "Battery" && temp.temperatureCelsius >= 48.0) ||
                         (temp.temperatureSource != "Battery" && temp.temperatureCelsius >= 80.0))) {
                        stopReason = "thermal_limit_${temp.temperatureSource}_${temp.temperatureCelsius}C"
                        progress("Thermal safety stop: $stopReason"); break
                    }
                    val row = caseJson(c, index)
                    attempted++
                    progress("4.5x [${index+1}/${cases.size}] ${c.pipeline.uppercase(Locale.US)} " +
                        "Camera2 template=${c.template}, NR=${c.nr ?: "auto"}, ISO=${c.iso ?: "auto"}")
                    try {
                        val request = requestFor(c, source, square)
                        flushImages(); lastResult = null
                        session?.setRepeatingRequest(request, captureCallback, handler)
                            ?: error("Camera2 session unavailable")
                        Thread.sleep(if (c.iso != null) 550 else 900)
                        val first = captureNormalized(c.pipeline == "cpu", square)
                        val second = captureNormalized(c.pipeline == "cpu", square)
                        val (matchingResult, timestampMatched) = resultFor(second.timestampNs)
                        row.put("width", second.frame.width); row.put("height", second.frame.height)
                        row.put("jpegBytes", second.frame.jpeg.size)
                        row.put("imageTimestampNs", second.timestampNs)
                        row.put("captureResultTimestampMatched", timestampMatched)
                        row.put("captureResult", resultJson(matchingResult, request))
                        row.put("imageMetrics", metricsJson(second.frame, first.frame))
                        row.put("temperatureC", temp.temperatureCelsius ?: JSONObject.NULL)
                        row.put("temperatureSource", temp.temperatureSource)
                        row.put("cpuPercent", temp.cpuPercent)
                        val fileName = "samples/%03d_%s.jpg".format(Locale.US, index+1, c.id)
                        write(fileName, second.frame.jpeg)
                        row.put("image", fileName)
                        row.put("status", "ok")
                        completed++
                    } catch (e: Exception) {
                        row.put("status", "failed")
                        row.put("error", "${e.javaClass.simpleName}: ${e.message}")
                        Log.w(TAG, "Case ${c.id} failed: ${e.message}", e)
                        // A failing template/parameter should NOT abort the rest.
                    }
                    rows.put(row)
                }
            } catch (e: Exception) {
                stopReason = "fatal_${e.javaClass.simpleName}: ${e.message}"
                Log.e(TAG, "Benchmark failed", e)
                progress(stopReason)
            } finally {
                close()
            }
            write("report.json", JSONObject().apply {
                put("zoom", LgBenchmarkPlan.ZOOM)
                put("requestedCases", cases.size)
                put("attempted", attempted); put("completed", completed)
                put("stopReason", stopReason)
                put("durationMs", SystemClock.elapsedRealtime() - begunAt)
                put("imageSource", if (chosen.width > 0) "${chosen.width}x${chosen.height}" else "unavailable")
                put("cases", rows)
            }.toString(2).toByteArray(Charsets.UTF_8))
            val csv = StringBuilder(
                "index,id,status,pipeline,template,width,height,isoActual,shutterNsActual," +
                "focusDiopters,NRActual,EDGEActual,meanLuma,contrastStdDev,p01,p99," +
                "blackClippingPct,whiteClippingPct,edgeEnergy,laplacianVariance," +
                "temporalLumaAbsDiff,timestampMatched,error").append(10.toChar())
            for (i in 0 until rows.length()) {
                val o = rows.getJSONObject(i)
                val m = o.optJSONObject("imageMetrics")
                val actual = o.optJSONObject("captureResult")
                fun csvField(value: String): String {
                    val quote = 34.toChar().toString()
                    return quote + value.replace(quote, quote + quote) + quote
                }
                val cells = listOf(
                    o.optInt("index").toString(), csvField(o.optString("id")),
                    csvField(o.optString("status")), csvField(o.optString("pipeline")),
                    o.optInt("template").toString(), o.optInt("width").toString(),
                    o.optInt("height").toString(), actual?.optString("sensorIso").orEmpty(),
                    actual?.optString("shutterNs").orEmpty(),
                    actual?.optString("focusDiopters").orEmpty(),
                    actual?.optString("noiseReduction").orEmpty(),
                    actual?.optString("edgeMode").orEmpty(),
                    m?.optString("meanLuma").orEmpty(),
                    m?.optString("lumaStdDev").orEmpty(),
                    m?.optString("lumaP01").orEmpty(),
                    m?.optString("lumaP99").orEmpty(),
                    m?.optString("blackClippingPct").orEmpty(),
                    m?.optString("whiteClippingPct").orEmpty(),
                    m?.optString("edgeEnergy").orEmpty(),
                    m?.optString("laplacianVariance").orEmpty(),
                    m?.optString("temporalLumaAbsDiff").orEmpty(),
                    o.optBoolean("captureResultTimestampMatched").toString(),
                    csvField(o.optString("error"))
                )
                csv.append(cells.joinToString(",")).append(10.toChar())
            }
            write("results.csv", csv.toString().toByteArray(Charsets.UTF_8))
            write("README.txt", (
                "LG-H872 Camera0 4.5x automated image comparison\n" +
                "report.json: actual Camera2 values and scene-dependent luminance metrics.\n" +
                "samples/: processed JPEG frames at matched native-information scale.\n" +
                "results.csv: case index and failures; read report.json for all detailed values.\n" +
                "Camera2 sources are negotiated YUV; YUV max may differ from JPEG max.\n" +
                "A fixed scene, tripod and steady light are REQUIRED for meaningful comparisons.\n" +
                "Sharpness/edge energy may reward oversharpening; compare actual images yourself.\n" +
                "ISO, focus and shutter are bounded representative samples, NOT every real number.\n"
                ).toByteArray(Charsets.UTF_8))
        }
        return Outcome(zipFile, completed, attempted, stopReason, chosen)
    }
}
