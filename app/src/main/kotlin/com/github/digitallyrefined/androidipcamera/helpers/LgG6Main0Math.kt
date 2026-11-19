package com.github.digitallyrefined.androidipcamera.helpers

import kotlin.math.floor
import kotlin.math.min

/** [left,top,right,bottom) in Camera2 active-array pixels, not encoded output. */
internal data class LgPixelRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left
    val height get() = bottom - top
}

/** A 16:9 sensor crop rounded to chroma-aligned coordinates for A/B experiments. */
internal object LgG6Main0Math {
    private fun gcd(x: Int, y: Int): Int {
        var a = x; var b = y
        while (b != 0) { val r = a % b; a = b; b = r }
        return a
    }
    private fun even(x: Int) = x / 2 * 2
    private fun offset(x: Double) = even(floor(x).toInt().coerceAtLeast(0))
    fun sensorCrop(
        active: LgPixelRect, zoom: Double, aspectWidth: Int = 16, aspectHeight: Int = 9,
        centerX: Double = 0.5, centerY: Double = 0.5
    ): LgPixelRect {
        require(active.width >= 2 && active.height >= 2)
        require(aspectWidth > 0 && aspectHeight > 0)
        require(zoom.isFinite() && zoom in 1.0..8.0)
        require(centerX.isFinite() && centerY.isFinite())
        val div = gcd(aspectWidth, aspectHeight)
        val blockW = aspectWidth / div * 2
        val blockH = aspectHeight / div * 2
        val blocks = min(active.width / blockW, active.height / blockH)
        require(blocks >= 1)
        val baseW = blocks * blockW; val baseH = blocks * blockH
        val baseLeft = even(active.left + (active.width - baseW) / 2)
        val baseTop = even(active.top + (active.height - baseH) / 2)
        val zoomBlocks = floor(blocks / zoom).toInt().coerceAtLeast(1)
        val w = zoomBlocks * blockW; val h = zoomBlocks * blockH
        val left = baseLeft + offset((baseW - w) * centerX.coerceIn(0.0, 1.0))
        val top = baseTop + offset((baseH - h) * centerY.coerceIn(0.0, 1.0))
        return LgPixelRect(left, top, left + w, top + h)
    }
}
