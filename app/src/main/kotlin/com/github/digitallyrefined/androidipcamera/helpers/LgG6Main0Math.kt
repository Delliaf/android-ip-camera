package com.github.digitallyrefined.androidipcamera.helpers

import kotlin.math.floor
import kotlin.math.min

/** [left, right) and [top, bottom) in Camera2 active-array sensor coordinates. */
internal data class LgPixelRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/** GPL-free mathematical adaptation of the user-supplied LG G6 camera handoff. */
internal object LgG6Main0Math {
    private fun gcd(a0: Int, b0: Int): Int {
        var a = a0
        var b = b0
        while (b != 0) {
            val r = a % b
            a = b; b = r
        }
        return a
    }
    private fun even(x: Int) = (x / 2) * 2
    private fun evenFloorOffset(x: Double) = (floor(x).toInt().coerceAtLeast(0) / 2) * 2

    /**
     * Sensor ROI with 4:2:0-even coordinates and an exact target aspect ratio.
     * A crop does NOT prove reduced sensor readout, improved resolution, or less ISP work.
     */
    fun sensorCrop(
        active: LgPixelRect, zoom: Double,
        aspectWidth: Int = 16, aspectHeight: Int = 9,
        centerX: Double = .5, centerY: Double = .5
    ): LgPixelRect {
        require(active.width >= 2 && active.height >= 2)
        require(aspectWidth > 0 && aspectHeight > 0)
        require(zoom.isFinite() && zoom in 1.0..8.0)
        require(centerX.isFinite() && centerY.isFinite())
        val div = gcd(aspectWidth, aspectHeight)
        val stepW = (aspectWidth / div) * 2
        val stepH = (aspectHeight / div) * 2
        val blocks = min(active.width / stepW, active.height / stepH)
        require(blocks > 0)
        val baseW = blocks * stepW
        val baseH = blocks * stepH
        val baseX = even(active.left + (active.width - baseW) / 2)
        val baseY = even(active.top + (active.height - baseH) / 2)
        val cropBlocks = floor(blocks / zoom).toInt().coerceAtLeast(1)
        val cropW = cropBlocks * stepW
        val cropH = cropBlocks * stepH
        val left = baseX + evenFloorOffset((baseW - cropW) * centerX.coerceIn(0.0, 1.0))
        val top = baseY + evenFloorOffset((baseH - cropH) * centerY.coerceIn(0.0, 1.0))
        return LgPixelRect(left, top, left + cropW, top + cropH)
    }

    /** Only camera0 on the exact target model is eligible for OEM experiments. */
    fun isTarget(model: String, cameraId: String?): Boolean =
        model.equals("LG-H872", ignoreCase = true) && cameraId == "0"

    /** Returns shutter microseconds respecting the requested FPS and the HAL min/max. */
    fun exposureForFps(requestUs: Long, fps: Int, minNs: Long, maxNs: Long): Long {
        require(minNs > 0 && maxNs >= minNs)
        val periodNs = 1_000_000_000L / fps.coerceIn(1, 60)
        val ceilingNs = minOf(maxNs, (periodNs - 1_000_000L).coerceAtLeast(minNs))
        return (requestUs.coerceIn(1L, 30_000_000L) * 1000L).coerceIn(minNs, ceilingNs)
    }
}
