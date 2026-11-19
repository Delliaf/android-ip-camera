package com.github.digitallyrefined.androidipcamera.helpers

import kotlin.math.min

/** Conservative AVC/OMX fallback for devices rejecting a requested frame size.
 *  Called only AFTER normal MediaCodec.configure fails; never silently reduces
 *  a normally working 1080p/4K format or changes the user's camera source size.
 */
internal object LgG6EncoderSizes {
    private fun gcd(x: Int, y: Int): Int {
        var a = x; var b = y
        while (b != 0) { val r = a % b; a = b; b = r }
        return a
    }
    fun fallback16(width: Int, height: Int): Pair<Int, Int> {
        require(width >= 32 && height >= 32)
        if (width % 16 == 0 && height % 16 == 0) return width to height
        val g = gcd(width, height)
        val unitW = width / g
        val unitH = height / g
        val blocks = min(width / (unitW * 16), height / (unitH * 16))
        return if (blocks > 0) unitW * 16 * blocks to unitH * 16 * blocks
        else (width / 16 * 16) to (height / 16 * 16)
    }
}
