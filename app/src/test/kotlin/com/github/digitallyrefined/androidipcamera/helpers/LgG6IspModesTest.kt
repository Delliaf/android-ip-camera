package com.github.digitallyrefined.androidipcamera.helpers

import org.junit.Assert.*
import org.junit.Test

class LgG6IspModesTest {
    private val advertisedNoise = intArrayOf(0, 1, 2, 3, 4)
    private val advertisedEdge = intArrayOf(0, 1, 2, 3)

    @Test fun advertisesAllLgh872NoiseReductionModesIndependently() {
        assertNull(LgG6IspModes.noise("auto", advertisedNoise))
        assertEquals(0, LgG6IspModes.noise("off", advertisedNoise))
        assertEquals(1, LgG6IspModes.noise("fast", advertisedNoise))
        assertEquals(2, LgG6IspModes.noise("hq", advertisedNoise))
        assertEquals(3, LgG6IspModes.noise("minimal", advertisedNoise))
        assertEquals(4, LgG6IspModes.noise("zsl", advertisedNoise))
    }

    @Test fun edgeEnhancementIsIndependentOfNoiseReduction() {
        assertNull(LgG6IspModes.edge("auto", advertisedEdge))
        assertEquals(0, LgG6IspModes.edge("off", advertisedEdge))
        assertEquals(1, LgG6IspModes.edge("fast", advertisedEdge))
        assertEquals(2, LgG6IspModes.edge("hq", advertisedEdge))
        assertEquals(3, LgG6IspModes.edge("zsl", advertisedEdge))
        assertNull(LgG6IspModes.edge("minimal", advertisedEdge))
    }

    @Test fun unsupportedModeNeverSilentlyUsesAnotherMode() {
        assertNull(LgG6IspModes.noise("hq", intArrayOf(0, 1)))
        assertNull(LgG6IspModes.noise("off", null))
        assertNull(LgG6IspModes.edge("zsl", intArrayOf(0, 1)))
        assertNull(LgG6IspModes.edge("unknown", advertisedEdge))
        assertNull(LgG6IspModes.noise("unknown", advertisedNoise))
    }

    @Test fun invalidControlStringsAreRejected() {
        for (value in listOf("auto", "off", "fast", "hq", "minimal", "zsl"))
            assertTrue(LgG6IspModes.validNoise(value))
        for (value in listOf("auto", "off", "fast", "hq", "zsl"))
            assertTrue(LgG6IspModes.validEdge(value))
        assertFalse(LgG6IspModes.validEdge("minimal"))
        assertFalse(LgG6IspModes.validNoise("clearall"))
        assertFalse(LgG6IspModes.validNoise(""))
        assertFalse(LgG6IspModes.validEdge("OFF"))
    }
}
