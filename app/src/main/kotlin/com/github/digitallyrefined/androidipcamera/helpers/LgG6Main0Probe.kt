package com.github.digitallyrefined.androidipcamera.helpers

import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.Build
import android.os.SystemClock
import android.util.Log

/**
 * Read-only vendor metadata and live Camera2 readback for the main rear LG-H872 camera.
 *
 * No root, hidden APIs, system libraries or vendor APK are used. Never installs a
 * proprietary CaptureRequest option by itself. Enable only with explicit diagnostics.
 */
internal object LgG6Main0Probe {
    const val VENDOR_ZOOM = "com.lge.camera.zoom.ratio" // request Float, from LGCameraApp DEX
    const val VENDOR_PEAKING = "com.lge.camera.focus_peaking" // request Integer
    const val VENDOR_RATIO_LIST = "com.lge.camera.zoom.ratio_value" // characteristic IntArray
    const val QTI_SENSOR_MODES = "org.quic.camera2.sensormode.info.SensorModeTable"
    private const val TAG = "LGG6Main0Probe"

    fun isTarget(cameraId: String?) = LgG6Main0Math.isTarget(Build.MODEL, cameraId)

    fun logAdvertisedKeys(context: Context, cameraId: String?) {
        if (!isTarget(cameraId)) return
        try {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val chars = manager.getCameraCharacteristics("0")
            val requestNames = chars.availableCaptureRequestKeys.map { it.name }.toSet()
            val characteristicNames = chars.keys.map { it.name }.toSet()
            val resultNames = chars.availableCaptureResultKeys.map { it.name }.toSet()
            for (name in listOf(VENDOR_ZOOM, VENDOR_PEAKING)) {
                Log.i(TAG, "camera0 request $name = ${requestNames.contains(name)}")
            }
            for (name in listOf(VENDOR_RATIO_LIST, QTI_SENSOR_MODES)) {
                Log.i(TAG, "camera0 characteristic $name = ${characteristicNames.contains(name)}")
            }
            Log.i(TAG, "requestKeys=${requestNames.size} resultKeys=${resultNames.size}")
            Log.i(TAG, "manualSensor=${chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) == true}")
            Log.i(TAG, "standardIso=${chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)} maxAnalog=${chars.get(CameraCharacteristics.SENSOR_MAX_ANALOG_SENSITIVITY)}")
            Log.i(TAG, "focusDiopters=${chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)} postRawBoost=${chars.get(CameraCharacteristics.CONTROL_POST_RAW_SENSITIVITY_BOOST_RANGE)}")
        } catch (e: Exception) { Log.w(TAG, "probe failed", e) }
    }

    data class Sample(
        val iso: Int?, val shutterNs: Long?, val frameNs: Long?,
        val focusDiopters: Float?, val actualCrop: String, val requestedCrop: String,
        val postRawBoostPercent: Int?, val sampledAtMs: Long
    )

    @Volatile var latest: Sample? = null
        private set

    fun clear() { latest = null }

    /** Attach to a SINGLE CameraX use case, not to both Preview and ImageAnalysis. */
    class TelemetryCallback : CameraCaptureSession.CaptureCallback() {
        private var frames = 0
        override fun onCaptureCompleted(
            session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult
        ) {
            frames++
            if (frames % 15 != 0) return
            val sample = Sample(
                result.get(CaptureResult.SENSOR_SENSITIVITY),
                result.get(CaptureResult.SENSOR_EXPOSURE_TIME),
                result.get(CaptureResult.SENSOR_FRAME_DURATION),
                result.get(CaptureResult.LENS_FOCUS_DISTANCE),
                result.get(CaptureResult.SCALER_CROP_REGION)?.toShortString() ?: "unknown",
                request.get(CaptureRequest.SCALER_CROP_REGION)?.toShortString() ?: "unset",
                result.get(CaptureResult.CONTROL_POST_RAW_SENSITIVITY_BOOST),
                SystemClock.elapsedRealtime()
            )
            latest = sample
            if (frames % 60 == 0)
                Log.i("LGG6Main0Result", "ISO=${sample.iso} shutterNs=${sample.shutterNs} frameNs=${sample.frameNs} focusD=${sample.focusDiopters} crop=${sample.actualCrop} requested=${sample.requestedCrop} postRawPercent=${sample.postRawBoostPercent}")
        }
    }
}
