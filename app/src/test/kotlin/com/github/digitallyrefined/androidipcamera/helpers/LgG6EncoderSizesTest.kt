package com.github.digitallyrefined.androidipcamera.helpers

import org.junit.Assert.assertEquals
import org.junit.Test

class LgG6EncoderSizesTest {
    @Test fun commonFourByThree800x600FallbackPreservesAspect() {
        assertEquals(768 to 576, LgG6EncoderSizes.fallback16(800, 600))
    }
    @Test fun workingFourByThree960x720IsUnchanged() {
        assertEquals(960 to 720, LgG6EncoderSizes.fallback16(960, 720))
    }
    @Test fun workingSquare768x768IsUnchanged() {
        assertEquals(768 to 768, LgG6EncoderSizes.fallback16(768, 768))
    }
    @Test fun sixteenByNineFallback() {
        assertEquals(896 to 504, LgG6EncoderSizes.fallback16(960, 540))
    }
    @Test(expected = IllegalArgumentException::class)
    fun invalidDimensions() { LgG6EncoderSizes.fallback16(0, 600) }
}
