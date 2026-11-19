package com.github.digitallyrefined.androidipcamera.helpers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DevicePerformanceMonitorTest {
    @Test fun cpuTickDeltaMeasuresUsageNotLoadAverage() {
        val previous = CpuMath.parse("cpu  100 0 30 200 10 0 0 0 0 0")
        val next = CpuMath.parse("cpu  200 0 50 250 10 0 0 0 0 0")
        assertEquals(70, CpuMath.percent(previous, next))
    }

    @Test fun cpuRequiresTwoSamplesAndRejectsCounterReset() {
        val first = CpuMath.parse("cpu 10 0 10 100 5 0 0 0")
        assertNull(CpuMath.percent(null, first))
        assertNull(CpuMath.percent(first, first))
        assertNull(CpuMath.percent(first, CpuMath.parse("cpu 1 0 1 1 0 0 0 0")))
        assertNull(CpuMath.parse("cpu0 10 0 10 100"))
        assertNull(CpuMath.parse("cpu broken"))
    }

    @Test fun recognizesQualcommH872SensorsWithoutMislabelingBattery() {
        assertEquals("SoC", CpuMath.thermalType("tsens_tz_sensor7\\n"))
        assertEquals("SoC", CpuMath.thermalType("msm-therm"))
        assertEquals("SoC", CpuMath.thermalType("ap-therm"))
        assertEquals("CPU", CpuMath.thermalType("cpu0-usr"))
        assertEquals("CPU", CpuMath.thermalType("cpu-cluster1"))
        assertNull(CpuMath.thermalType("battery"))
        assertNull(CpuMath.thermalType("pa_therm0"))
        assertNull(CpuMath.thermalType("unknown"))
    }

    @Test fun normalizeCommonAndroidThermalFormats() {
        assertEquals(42.5, CpuMath.thermalCelsius("42500\n")!!, 0.0001)
        assertEquals(42.5, CpuMath.thermalCelsius("425")!!, 0.0001)
        assertEquals(42.5, CpuMath.thermalCelsius("42.5")!!, 0.0001)
        assertNull(CpuMath.thermalCelsius("999999"))
        assertNull(CpuMath.thermalCelsius("unreadable"))
    }
}
