package com.github.digitallyrefined.androidipcamera.helpers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 13MP H872 4:3 input mapped onto a 16:9 encoded stream. */
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
