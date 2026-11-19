package com.github.digitallyrefined.androidipcamera.helpers

import android.os.Build

/**
 * Opt-in LG H872 Camera2 camera ID 0 profile. Never apply OEM controls to
 * wide-angle/auxiliary lenses or non-LG devices.
 */
internal object LgG6Main0Profile {
    const val CAMERA_ID = "0"
    const val PREF_ZOOM_MODE = "lg_main0_zoom_mode"
    const val PREF_ZOOM = "lg_main0_zoom"
    const val PREF_FOCUS_MODE = "lg_main0_focus_mode"
    const val PREF_FOCUS_DIOPTERS = "lg_main0_focus_diopters"
    const val PREF_POST_RAW_BOOST = "lg_main0_postraw_boost"
    const val PREF_PEAKING = "lg_main0_peaking"
    const val PREF_TELEMETRY = "lg_main0_telemetry"
    const val VENDOR_ZOOM = "com.lge.camera.zoom.ratio"
    const val VENDOR_PEAKING = "com.lge.camera.focus_peaking"

    fun isDevice(): Boolean = Build.MODEL.equals("LG-H872", ignoreCase = true)
    fun isTarget(id: String?): Boolean = isDevice() && id == CAMERA_ID
    fun zoomMode(value: String?): String = when (value) {
        "gpu", "vendor" -> value
        else -> "isp"
    }
}
