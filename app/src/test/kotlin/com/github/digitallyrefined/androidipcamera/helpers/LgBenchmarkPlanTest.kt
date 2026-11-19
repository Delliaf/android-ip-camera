package com.github.digitallyrefined.androidipcamera.helpers

import org.junit.Assert.*
import org.junit.Test

class LgBenchmarkPlanTest {
    @Test fun quickMatrixHasAllThreeTemplatesAndBothPipelines() {
        val cases = LgBenchmarkPlan.create(false, intArrayOf(0,1,2),
            intArrayOf(0,1,2), intArrayOf(0,1), intArrayOf(1,2),
            50,800,0f,10f)
        assertTrue(cases.size <= LgBenchmarkPlan.MAX_QUICK_CASES)
        assertEquals(setOf(1,3,5), cases.map { it.template }.toSet())
        assertEquals(setOf("isp","cpu"), cases.map { it.pipeline }.toSet())
        assertEquals(cases.size, cases.map { it.id }.toSet().size)
        assertEquals(4.5, LgBenchmarkPlan.ZOOM, 0.00001)
    }

    @Test fun deepMatrixRespectsActualIsoLimitsAndUniqueCases() {
        val cases = LgBenchmarkPlan.create(true, intArrayOf(0,1,2,3,4),
            intArrayOf(0,1,2,3), intArrayOf(0,1,2),
            intArrayOf(1,2,3,5,6), 50, 800, 0f, 10f)
        assertTrue(cases.size <= LgBenchmarkPlan.MAX_DEEP_CASES)
        assertTrue(cases.any { it.iso == 800 })
        assertTrue(cases.none { it.iso != null && it.iso !in 50..800 })
        assertTrue(cases.any { it.focusD == 0f })
        assertTrue(cases.any { it.focusD == 10f })
        assertEquals(cases.size, cases.map { it.id }.toSet().size)
    }

    @Test fun noAdvertisedProcessingModesStillHasBaselineRuns() {
        val cases = LgBenchmarkPlan.create(false, intArrayOf(), intArrayOf(),
            intArrayOf(), intArrayOf(),50,800,0f,0f)
        assertEquals(6, cases.size)
    }

    @Test fun noiseQualityOfUniformSurfaceIsZeroAndLuminanceCorrect() {
        val w = 64; val h = 48
        val uniform = ByteArray(w * h) { 100.toByte() }
        val metrics = LgBenchmarkImaging.metrics(uniform, w, h, uniform)
        assertEquals(100.0, metrics.brightness, 0.0001)
        assertEquals(0.0, metrics.contrastStdDev, 0.0001)
        assertEquals(0.0, metrics.laplacianVariance, 0.0001)
        assertEquals(0.0, metrics.temporalLumaDifference!!, 0.0001)
    }

    @Test fun blackClippingAndHighFrequencyAreDetected() {
        val w = 64; val h = 48
        val dark = ByteArray(w*h) { 0 }
        val darkMetrics = LgBenchmarkImaging.metrics(dark, w, h)
        assertEquals(100.0, darkMetrics.blackClippingPercent, 0.0001)
        val checkerboard = ByteArray(w*h) { i ->
            if ((i / w + i % w) % 2 == 0) 0 else 255.toByte()
        }
        val sharp = LgBenchmarkImaging.metrics(checkerboard, w, h)
        assertTrue(sharp.edgeEnergy > 0)
        assertTrue(sharp.laplacianVariance > 0)
    }
}
