package com.github.digitallyrefined.androidipcamera.helpers

/**
 * Normalized rectangular ROI preserving the source aspect ratio.
 * Selecting zoom 2x takes one quarter of the input pixels before encoder scaling.
 */
internal data class RoiWindow(val left: Float, val top: Float, val width: Float, val height: Float)

internal object RoiCrop {
    /**
     * Crop the ORIGINAL sensor texture to match the encoded picture aspect before zoom.
     * This prevents 4:3 photo-source input (e.g. 4160x3120) being letterboxed
     * into a 16:9 H.264 output (e.g. 960x540). Zoom applies ONCE to the fill rectangle.
     * For 90/270 degree stream rotation the effective output ratio is inverted.
     */
    fun windowForOutput(
        zoom: Float, centerX: Float, centerY: Float,
        sourceWidth: Int, sourceHeight: Int, outputWidth: Int, outputHeight: Int,
        rotation: Int = 0
    ): RoiWindow {
        if (sourceWidth <= 0 || sourceHeight <= 0 || outputWidth <= 0 || outputHeight <= 0)
            return window(zoom, centerX, centerY)
        val quarterTurn = ((rotation % 360) + 360) % 360 == 90 ||
                          ((rotation % 360) + 360) % 360 == 270
        val outAspect = if (quarterTurn) outputHeight.toFloat() / outputWidth
                        else outputWidth.toFloat() / outputHeight
        val srcAspect = sourceWidth.toFloat() / sourceHeight
        val z = zoom.takeIf { it.isFinite() }?.coerceIn(1f, 8f) ?: 1f
        val baseW = if (srcAspect > outAspect) outAspect / srcAspect else 1f
        val baseH = if (srcAspect < outAspect) srcAspect / outAspect else 1f
        val w = baseW / z
        val h = baseH / z
        val x = centerX.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: .5f
        val y = centerY.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: .5f
        return RoiWindow((x - w/2f).coerceIn(0f, 1f - w),
                         (y - h/2f).coerceIn(0f, 1f - h), w, h)
    }

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
