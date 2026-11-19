package com.github.digitallyrefined.androidipcamera.helpers

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import androidx.camera.camera2.interop.CaptureRequestOptions

/**
 * The H872 OEM DEX creates PHOTO preview repeating requests with template 5 (ZSL),
 * while recording uses template 3. Template selection is only possible in the direct
 * Camera2 backend; CameraX quality overrides do not imply OEM ZSL behavior.
 */
internal enum class LgG6PreviewProfile(val wireName: String) {
    VIDEO_REFERENCE("video"),
    PHOTO_ZSL("zsl"),
    PHOTO_DEFAULT("default"),
    PHOTO_FAST("fast"),
    PHOTO_HQ("hq");

    companion object {
        fun parse(value: String?): LgG6PreviewProfile =
            values().firstOrNull { it.wireName == value } ?: PHOTO_ZSL
    }
}

internal object LgG6PhotoPreviewTuning {
    fun template(profile: LgG6PreviewProfile): Int = when (profile) {
        LgG6PreviewProfile.VIDEO_REFERENCE -> CameraDevice.TEMPLATE_RECORD
        LgG6PreviewProfile.PHOTO_ZSL -> CameraDevice.TEMPLATE_ZERO_SHUTTER_LAG
        else -> CameraDevice.TEMPLATE_PREVIEW
    }

    private fun supported(options: IntArray?, preferred: Int, fallback: Int): Int? =
        when {
            options == null -> null
            options.contains(preferred) -> preferred
            options.contains(fallback) -> fallback
            else -> null
        }

    private fun modes(ch: CameraCharacteristics, profile: LgG6PreviewProfile,
                      put: (CaptureRequest.Key<Int>, Int) -> Unit) {
        if (profile != LgG6PreviewProfile.PHOTO_FAST && profile != LgG6PreviewProfile.PHOTO_HQ)
            return
        val hq = profile == LgG6PreviewProfile.PHOTO_HQ
        fun set(key: CaptureRequest.Key<Int>, options: IntArray?, fast: Int, high: Int) {
            supported(options, if (hq) high else fast, fast)?.let { put(key, it) }
        }
        set(CaptureRequest.NOISE_REDUCTION_MODE,
            ch.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES),
            CaptureRequest.NOISE_REDUCTION_MODE_FAST,
            CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
        set(CaptureRequest.EDGE_MODE,
            ch.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES),
            CaptureRequest.EDGE_MODE_FAST, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
        set(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE,
            ch.get(CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES),
            CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_FAST,
            CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY)
        if (hq) {
            set(CaptureRequest.SHADING_MODE,
                ch.get(CameraCharacteristics.SHADING_AVAILABLE_MODES),
                CaptureRequest.SHADING_MODE_FAST, CaptureRequest.SHADING_MODE_HIGH_QUALITY)
            set(CaptureRequest.HOT_PIXEL_MODE,
                ch.get(CameraCharacteristics.HOT_PIXEL_AVAILABLE_HOT_PIXEL_MODES),
                CaptureRequest.HOT_PIXEL_MODE_FAST, CaptureRequest.HOT_PIXEL_MODE_HIGH_QUALITY)
            set(CaptureRequest.TONEMAP_MODE,
                ch.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES),
                CaptureRequest.TONEMAP_MODE_FAST, CaptureRequest.TONEMAP_MODE_HIGH_QUALITY)
        }
    }

    fun apply(builder: CaptureRequest.Builder, chars: CameraCharacteristics, profile: LgG6PreviewProfile) {
        if (profile != LgG6PreviewProfile.PHOTO_ZSL &&
            profile != LgG6PreviewProfile.VIDEO_REFERENCE)
            builder.set(CaptureRequest.CONTROL_CAPTURE_INTENT,
                CaptureRequest.CONTROL_CAPTURE_INTENT_PREVIEW)
        modes(chars, profile) { key, value -> builder.set(key, value) }
    }

    /**
     * This is ONLY standard NR/EDGE tuning on CameraX's own request template.
     * Do not force ZSL intent into CameraX, which internally owns the template.
     */
    fun applyCameraX(options: CaptureRequestOptions.Builder,
                     chars: CameraCharacteristics, profile: LgG6PreviewProfile) {
        modes(chars, profile) { key, value -> options.setCaptureRequestOption(key, value) }
    }

    fun readback(result: CaptureResult): String =
        "ISO=${result.get(CaptureResult.SENSOR_SENSITIVITY)} " +
        "shutterNs=${result.get(CaptureResult.SENSOR_EXPOSURE_TIME)} " +
        "frameNs=${result.get(CaptureResult.SENSOR_FRAME_DURATION)} " +
        "focusD=${result.get(CaptureResult.LENS_FOCUS_DISTANCE)} " +
        "crop=${result.get(CaptureResult.SCALER_CROP_REGION)} " +
        "NR=${result.get(CaptureResult.NOISE_REDUCTION_MODE)} " +
        "EDGE=${result.get(CaptureResult.EDGE_MODE)} " +
        "ABERRATION=${result.get(CaptureResult.COLOR_CORRECTION_ABERRATION_MODE)}"
}
