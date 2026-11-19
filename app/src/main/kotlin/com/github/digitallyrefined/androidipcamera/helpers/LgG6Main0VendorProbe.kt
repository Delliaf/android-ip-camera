package com.github.digitallyrefined.androidipcamera.helpers

import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.SystemClock
import android.util.Log

/** Read-only diagnostic checks: no root, HAL injection or proprietary .so files. */
internal object LgG6Main0VendorProbe {
    private const val TAG = "LGG6Camera0Probe"

    fun capabilities(context: Context): Set<String> {
        if (!LgG6Main0Profile.isDevice()) return emptySet()
        return try {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val chars = manager.getCameraCharacteristics(LgG6Main0Profile.CAMERA_ID)
            chars.availableCaptureRequestKeys.map { it.name }.toSet()
        } catch (e: Exception) {
            Log.w(TAG, "Cannot obtain camera0 request keys", e)
            emptySet()
        }
    }

    fun scan(context: Context) {
        if (!LgG6Main0Profile.isDevice()) return
        val available = capabilities(context)
        Log.i(TAG, "camera0 request vendor zoom=" +
            available.contains(LgG6Main0Profile.VENDOR_ZOOM) +
            " peaking=" + available.contains(LgG6Main0Profile.VENDOR_PEAKING))
        try {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val chars = manager.getCameraCharacteristics(LgG6Main0Profile.CAMERA_ID)
            val keys = chars.keys.map { it.name }.toSet()
            val results = chars.availableCaptureResultKeys.map { it.name }.toSet()
            Log.i(TAG, "camera0 zoom.ratio_value=" +
                keys.contains("com.lge.camera.zoom.ratio_value") +
                " sensor-mode-table=" +
                keys.contains("org.quic.camera2.sensormode.info.SensorModeTable") +
                " result-key-count=" + results.size)
        } catch (e: Exception) { Log.w(TAG, "Cannot read camera0 characteristics", e) }
    }
}

/** One callback, every 30th result. A real CaptureResult, not requested CameraX metadata. */
internal class LgG6Main0Telemetry : CameraCaptureSession.CaptureCallback() {
    companion object {
        @Volatile var lastSummary: String = "No Camera2 result received"
            private set
        @Volatile var lastUpdatedMs = 0L
            private set
        fun report(request: CaptureRequest, result: TotalCaptureResult, profile: String = "CameraX") {
            val aeMode = request.get(CaptureRequest.CONTROL_AE_MODE)
            val aeOn = aeMode != CaptureRequest.CONTROL_AE_MODE_OFF
            val requestedISO = request.get(CaptureRequest.SENSOR_SENSITIVITY)
            val requestedShutter = request.get(CaptureRequest.SENSOR_EXPOSURE_TIME)
            val summary = "profile=$profile AE=" + (if (aeOn) "AUTO" else "MANUAL") +
                " aeState=" + result.get(CaptureResult.CONTROL_AE_STATE) +
                " aeComp=" + request.get(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION) +
                " aeFpsReq=" + request.get(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE) +
                " aeFpsActual=" + result.get(CaptureResult.CONTROL_AE_TARGET_FPS_RANGE) +
                " " + LgG6PhotoPreviewTuning.readback(result) +
                " requestISO=" + (if (aeOn) "ignored(template=$requestedISO)" else "$requestedISO") +
                " requestShutterNs=" + (if (aeOn) "ignored(template=$requestedShutter)" else "$requestedShutter") +
                " requestCrop=" + request.get(CaptureRequest.SCALER_CROP_REGION) +
                " requestNR=" + request.get(CaptureRequest.NOISE_REDUCTION_MODE) +
                " requestEDGE=" + request.get(CaptureRequest.EDGE_MODE) +
                " postRawBoost=" + result.get(CaptureResult.CONTROL_POST_RAW_SENSITIVITY_BOOST)
            lastSummary = summary
            lastUpdatedMs = SystemClock.elapsedRealtime()
            Log.i("LGG6Camera0Result", summary)
        }
    }
    private var frames = 0
    override fun onCaptureCompleted(
        session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult
    ) {
        frames++
        if (frames % 30 != 0) return
        report(request, result)
    }
}
