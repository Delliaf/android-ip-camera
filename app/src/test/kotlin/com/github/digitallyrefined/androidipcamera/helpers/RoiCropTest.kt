package com.github.digitallyrefined.androidipcamera.helpers

import org.junit.Assert.assertEquals
import org.junit.Test

class RoiCropTest {
    @Test fun fullFrameIsDefault() {
        val w = RoiCrop.window(1f, .5f, .5f)
        assertEquals(0f, w.left, .0001f)
        assertEquals(0f, w.top, .0001f)
        assertEquals(1f, w.width, .0001f)
    }
    @Test fun centered2xIsQuarterOf4k() {
        val w = RoiCrop.window(2f, .5f, .5f)
        assertEquals(.25f, w.left, .0001f)
        assertEquals(.25f, w.top, .0001f)
        assertEquals(.5f, w.width, .0001f)
        assertEquals(.5f, w.height, .0001f)
    }
    @Test fun edgeIsClampedWithoutOutOfBounds() {
        val w = RoiCrop.window(4f, 1f, 0f)
        assertEquals(.75f, w.left, .0001f)
        assertEquals(0f, w.top, .0001f)
    }
    @Test fun nonFiniteInputsReturnSafeWindow() {
        val w = RoiCrop.window(Float.NaN, Float.POSITIVE_INFINITY, Float.NaN)
        assertEquals(0f, w.left, .0001f)
        assertEquals(1f, w.height, .0001f)
    }
}
