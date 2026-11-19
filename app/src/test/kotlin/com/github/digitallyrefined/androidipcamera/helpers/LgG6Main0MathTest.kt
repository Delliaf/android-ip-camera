package com.github.digitallyrefined.androidipcamera.helpers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LgG6Main0MathTest {
    private val active = LgPixelRect(8, 8, 4160, 3120)

    @Test fun fullFrame16by9Bounds() {
        val crop = LgG6Main0Math.sensorCrop(active, zoom = 1.0)
        assertEquals(4128, crop.width)
        assertEquals(2322, crop.height)
        assertTrue(crop.left >= active.left && crop.right <= active.right)
        assertTrue(crop.top >= active.top && crop.bottom <= active.bottom)
    }

    @Test fun fourXCropKeepsExactEvenAspect() {
        val crop = LgG6Main0Math.sensorCrop(active, zoom = 4.0)
        assertEquals(1024, crop.width)
        assertEquals(576, crop.height)
        assertEquals(0, crop.left % 2)
        assertEquals(0, crop.top % 2)
        assertEquals(crop.width * 9, crop.height * 16)
    }

    @Test fun edgesAreClampedToActiveArray() {
        val a = LgG6Main0Math.sensorCrop(active, zoom = 4.0, centerX = 0.0, centerY = 0.0)
        val b = LgG6Main0Math.sensorCrop(active, zoom = 4.0, centerX = 1.0, centerY = 1.0)
        assertTrue(a.left >= active.left && a.top >= active.top)
        assertTrue(b.right <= active.right && b.bottom <= active.bottom)
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidZoomRejected() {
        LgG6Main0Math.sensorCrop(active, zoom = Double.NaN)
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidActiveArrayRejected() {
        LgG6Main0Math.sensorCrop(LgPixelRect(0,0,0,0), zoom = 2.0)
    }
}
