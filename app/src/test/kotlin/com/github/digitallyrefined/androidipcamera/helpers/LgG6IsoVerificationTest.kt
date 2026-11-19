package com.github.digitallyrefined.androidipcamera.helpers
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LgG6IsoVerificationTest {
    @Test fun rejectsExtendedIsoWhenActualStaysAt800() {
        assertTrue(LgG6IsoVerification.extendedRequestIgnored(1600, 800, 800))
    }
    @Test fun retainsPartlyAppliedManualIsoAboveAdvertisedMax() {
        assertFalse(LgG6IsoVerification.extendedRequestIgnored(3200, 2728, 800))
    }
    @Test fun noJudgmentOnNormalIsoRange() {
        assertFalse(LgG6IsoVerification.extendedRequestIgnored(800, 650, 800))
    }
}
