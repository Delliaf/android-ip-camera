package com.github.digitallyrefined.androidipcamera.helpers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LgG6AeFpsTest {
    private val lg = listOf(15 to 15, 20 to 20, 2 to 24, 24 to 24, 2 to 30, 30 to 30)
    @Test fun adaptive15FpsChoosesWide2To30RatherThanFixed15() {
        assertEquals(2 to 30, LgG6AeFps.select(lg, 15, true))
    }
    @Test fun fixed15FpsChooses15To15() {
        assertEquals(15 to 15, LgG6AeFps.select(lg, 15, false))
    }
    @Test fun adaptive20FpsStillChoosesWide2To30() {
        assertEquals(2 to 30, LgG6AeFps.select(lg, 20, true))
    }
    @Test fun unavailableFpsReturnsNoRange() {
        assertNull(LgG6AeFps.select(lg, 120, true))
    }
}
