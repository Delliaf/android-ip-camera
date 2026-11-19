package com.github.digitallyrefined.androidipcamera.helpers

import org.junit.Assert.*
import org.junit.Test

class LgG6Main0MathTest {
    private val sensor = LgPixelRect(8, 8, 4160, 3120)

    @Test fun exactSourceRoiFourTimes() {
        val r = LgG6Main0Math.sensorCrop(sensor, 4.0)
        assertEquals(1024, r.width)
        assertEquals(576, r.height)
        assertEquals(0, r.left % 2)
        assertEquals(0, r.top % 2)
        assertEquals(16 * r.height, 9 * r.width)
    }
    @Test fun edgeClamping() {
        val r = LgG6Main0Math.sensorCrop(sensor, 4.0, centerX = 1.0, centerY = 0.0)
        assertTrue(r.left >= sensor.left && r.right <= sensor.right)
        assertTrue(r.top >= sensor.top && r.bottom <= sensor.bottom)
    }
    @Test fun modelCameraGuard() {
        assertTrue(LgG6Main0Math.isTarget("LG-H872", "0"))
        assertFalse(LgG6Main0Math.isTarget("LG-H872", "3"))
        assertFalse(LgG6Main0Math.isTarget("LG-H870", "0"))
    }
    @Test fun shutterClampedForFps() {
        assertEquals(19_000_000L, LgG6Main0Math.exposureForFps(100_000L, 50, 20651, 33_000_000_000L))
        assertEquals(20_651L, LgG6Main0Math.exposureForFps(1L, 20, 20651, 33_000_000_000L))
    }
}
