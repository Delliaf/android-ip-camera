package com.github.digitallyrefined.androidipcamera.helpers

import org.junit.Assert.*
import org.junit.Test

/** Regression: a legacy /?zoom=... GET MUST NOT return dashboard HTML. */
class WebControlRouteTest {
    @Test fun rootWithLegacyFocusZoomAndExposureIsAControlRequest() {
        assertTrue(WebControlRoute.isLegacyControl("/", mapOf("focus_distance" to "4.20", "ts" to "1800")))
        assertTrue(WebControlRoute.isLegacyControl("/", mapOf("zoom" to "4.5")))
        assertTrue(WebControlRoute.isLegacyControl("/", mapOf("manual_iso" to "400")))
        assertTrue(WebControlRoute.isLegacyControl("/", mapOf("resetCamera" to "0")))
    }

    @Test fun rootWithCacheBustingOrNoQueryStillServesDashboard() {
        assertFalse(WebControlRoute.isLegacyControl("/", emptyMap()))
        assertFalse(WebControlRoute.isLegacyControl("/", mapOf("v" to "8")))
        assertFalse(WebControlRoute.isLegacyControl("/", mapOf("v" to "9", "ts" to "1790")))
        assertFalse(WebControlRoute.isLegacyControl("/", mapOf("cacheBust" to "1", "_" to "2")))
    }

    @Test fun mediaMetricsAndInfoQueriesAreNeverTreatedAsCommands() {
        for (path in listOf("/metrics.json", "/info.json", "/video/snapshot",
            "/video/mjpeg", "/video/h264", "/files/test.jpg")) {
            assertFalse(WebControlRoute.isLegacyControl(path, mapOf("zoom" to "4.5")))
        }
    }

    @Test fun legacyControlsDoNotForwardTimestampOrCacheBusters() {
        assertEquals(mapOf("focus_distance" to "3.5"),
            WebControlRoute.filterLegacyControls(mapOf(
                "focus_distance" to "3.5", "ts" to "1", "v" to "8", "_" to "no")))
    }

    @Test fun explicitPathHasDistinctAddressAndInputGuards() {
        assertEquals("/control/set", WebControlRoute.EXPLICIT_PATH)
        assertTrue(WebControlRoute.validKey("lg_main0_focus_diopters"))
        assertTrue(WebControlRoute.validValue("-3.50"))
        assertFalse(WebControlRoute.validKey(""))
        assertFalse(WebControlRoute.validKey("focus\r\nOther-Header"))
        assertFalse(WebControlRoute.validKey("a/b"))
        assertFalse(WebControlRoute.validValue("value\r\nInjected: yes"))
        assertFalse(WebControlRoute.validValue("z".repeat(513)))
    }
}
