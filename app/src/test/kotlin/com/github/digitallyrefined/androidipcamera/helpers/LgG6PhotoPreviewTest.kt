package com.github.digitallyrefined.androidipcamera.helpers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** H872 4:3 full-sensor input with native 4:3, square and optional 16:9 outputs. */
class LgG6PhotoPreviewTest {
    @Test fun oemDexTemplateIds() {
        assertEquals(5, LgG6PhotoPreviewTuning.template(LgG6PreviewProfile.PHOTO_ZSL))
        assertEquals(3, LgG6PhotoPreviewTuning.template(LgG6PreviewProfile.VIDEO_REFERENCE))
        assertEquals(1, LgG6PhotoPreviewTuning.template(LgG6PreviewProfile.PHOTO_DEFAULT))
        assertEquals(1, LgG6PhotoPreviewTuning.template(LgG6PreviewProfile.PHOTO_FAST))
        assertEquals(1, LgG6PhotoPreviewTuning.template(LgG6PreviewProfile.PHOTO_HQ))
    }
    @Test fun profileFallbackIsSafeZsl() {
        assertEquals(LgG6PreviewProfile.PHOTO_ZSL, LgG6PreviewProfile.parse("not-a-profile"))
        assertEquals(LgG6PreviewProfile.VIDEO_REFERENCE, LgG6PreviewProfile.parse("video"))
    }
    @Test fun nativeFourByThreeAtOneXKeepsEverySourcePixel() {
        val roi = RoiCrop.windowForOutput(1f, .5f, .5f, 4160, 3120, 960, 720)
        assertEquals(0f, roi.left, .00001f)
        assertEquals(0f, roi.top, .00001f)
        assertEquals(1f, roi.width, .00001f)
        assertEquals(1f, roi.height, .00001f)
    }

    @Test fun nativeFourByThreeAtFourXPreservesAllNativeCropPixels() {
        val roi = RoiCrop.windowForOutput(4f, .5f, .5f, 4160, 3120, 960, 720)
        assertEquals(.375f, roi.left, .00001f)
        assertEquals(.375f, roi.top, .00001f)
        assertEquals(1040f, roi.width * 4160f, .01f)
        assertEquals(780f, roi.height * 3120f, .01f)
        assertEquals(4f / 3f,
            (roi.width * 4160f) / (roi.height * 3120f), .00001f)
    }

    @Test fun squareAtFourXUsesNative780SquareWithoutUpscaling() {
        val roi = RoiCrop.windowForOutput(4f, .5f, .5f, 4160, 3120, 768, 768)
        assertEquals(.40625f, roi.left, .00001f)
        assertEquals(.375f, roi.top, .00001f)
        assertEquals(780f, roi.width * 4160f, .01f)
        assertEquals(780f, roi.height * 3120f, .01f)
    }

    @Test fun squarePanStaysInsideSensorEvenAtEdges() {
        for (x in listOf(0f, 1f)) for (y in listOf(0f, 1f)) {
            val r = RoiCrop.windowForOutput(4f, x, y, 4160, 3120, 768, 768)
            assertTrue(r.left >= 0f && r.top >= 0f)
            assertTrue(r.left + r.width <= 1.00001f)
            assertTrue(r.top + r.height <= 1.00001f)
        }
    }

    @Test fun full13MpSourceIsCroppedTo16By9BeforeZoom() {
        val roi = RoiCrop.windowForOutput(4f, 0.5f, 0.5f, 4160, 3120, 960, 540)
        assertEquals(.25f, roi.width, .00001f)
        assertEquals(.1875f, roi.height, .00001f)
        assertEquals(.375f, roi.left, .00001f)
        assertEquals(.40625f, roi.top, .00001f)
        assertEquals(1040f, roi.width * 4160f, .001f)
        assertEquals(585f, roi.height * 3120f, .001f)
    }
    @Test fun twelveMpCropFitsOutputWithoutLetterboxing() {
        val roi = RoiCrop.windowForOutput(4f, 1f, 0f, 4000, 3000, 960, 540)
        assertEquals(1000f, roi.width * 4000, .005f)
        assertEquals(562.5f, roi.height * 3000, .005f)
        assertTrue(roi.left >= 0f && roi.left + roi.width <= 1f)
        assertTrue(roi.top >= 0f && roi.top + roi.height <= 1f)
    }
    @Test fun noCropStillFills16by9() {
        val roi = RoiCrop.windowForOutput(1f, .5f, .5f, 4160, 3120, 960, 540)
        assertEquals(1f, roi.width, .00001f)
        assertEquals(.75f, roi.height, .00001f)
    }
}
