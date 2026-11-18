package com.github.digitallyrefined.androidipcamera.helpers

/**
 * Normalized rectangular ROI preserving the source aspect ratio.
 * Selecting zoom 2x takes one quarter of the input pixels before encoder scaling.
 */
internal data class RoiWindow(val left: Float, val top: Float, val width: Float, val height: Float)

internal object RoiCrop {
    fun window(zoom: Float, centerX: Float, centerY: Float): RoiWindow {
        val z = zoom.takeIf { it.isFinite() }?.coerceIn(1f, 8f) ?: 1f
        val w = 1f / z
        val h = 1f / z
        val x = centerX.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0.5f
        val y = centerY.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0.5f
        return RoiWindow((x - w / 2f).coerceIn(0f, 1f - w),
            (y - h / 2f).coerceIn(0f, 1f - h), w, h)
    }
}
