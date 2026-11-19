package com.github.digitallyrefined.androidipcamera.helpers

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Process
import android.os.SystemClock
import java.io.File
import java.util.Locale

/**
 * Lightweight, read-only metrics for /metrics.json and the video status overlay.
 * System CPU load uses two successive /proc/stat samples (NOT load average).
 * Android may block /proc/stat; in that case only this app's CPU usage is shown,
 * with the scope explicitly labeled. No root commands or busy polling.
 */
internal class DevicePerformanceMonitor(private val context: Context) {
    internal data class Sample(
        val cpuPercent: Int,
        val cpuScope: String,   // "system", "app", or "unavailable"
        val temperatureCelsius: Double?,
        val temperatureSource: String // "CPU", "SoC", "Battery", or ""
    )

    private var previousSystem: CpuTicks? = null
    private var previousProcessMs = -1L
    private var previousTimeMs = -1L
    private var lastSampleAtMs = -1L
    private var lastTemperatureAtMs = -1L
    private var cachedTemperature: Pair<Double?, String> = null to ""
    private var lastSample = Sample(-1, "unavailable", null, "")

    @Synchronized
    fun sample(): Sample {
        val now = SystemClock.elapsedRealtime()
        if (lastSampleAtMs >= 0 && now - lastSampleAtMs < 4_000L) return lastSample
        val currentSystem = try {
            File("/proc/stat").bufferedReader().use { CpuMath.parse(it.readLine()) }
        } catch (_: Exception) { null }
        val systemPercent = CpuMath.percent(previousSystem, currentSystem)
        if (currentSystem != null) previousSystem = currentSystem

        val processMs = try { Process.getElapsedCpuTime() } catch (_: Exception) { -1L }
        val appPercent = if (previousProcessMs >= 0 && processMs >= previousProcessMs &&
            previousTimeMs >= 0 && now > previousTimeMs) {
            val elapsed = now - previousTimeMs
            val cpuDelta = processMs - previousProcessMs
            (100.0 * cpuDelta / elapsed / Runtime.getRuntime().availableProcessors()
                .coerceAtLeast(1)).toInt().coerceIn(0, 100)
        } else null
        if (processMs >= 0) previousProcessMs = processMs
        previousTimeMs = now

        if (lastTemperatureAtMs < 0 || now - lastTemperatureAtMs >= 20_000L) {
            cachedTemperature = readThermal() ?: readBatteryTemperature()
            lastTemperatureAtMs = now
        }

        val percent = systemPercent ?: appPercent ?: -1
        val scope = when {
            systemPercent != null -> "system"
            appPercent != null -> "app"
            else -> "unavailable"
        }
        lastSample = Sample(percent, scope, cachedTemperature.first, cachedTemperature.second)
        lastSampleAtMs = now
        return lastSample
    }

    private fun readBatteryTemperature(): Pair<Double?, String> {
        return try {
            // Android reports battery temperature in tenths of Celsius.
            val i = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val tenths = i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
            val degrees = tenths / 10.0
            if (tenths < 0 || degrees !in -20.0..100.0) null to ""
            else degrees to "Battery"
        } catch (_: Exception) { null to "" }
    }

    private fun readThermal(): Pair<Double?, String>? {
        try {
            val dirs = File("/sys/class/thermal").listFiles()
                ?.filter { it.isDirectory && it.name.startsWith("thermal_zone") }
                ?.take(128) ?: return null
            // Generic tsens_tz_sensorNN IDs are not necessarily CPU sensors.
            // Only show a CPU/SoC label for an explicitly identified zone.
            val temperatures = dirs.mapNotNull { dir ->
                try {
                    val type = File(dir, "type").readText().trim().lowercase(Locale.US)
                    val label = when {
                        "cpu" in type || "cluster" in type -> "CPU"
                        "soc" in type || "ap-therm" in type -> "SoC"
                        else -> null
                    } ?: return@mapNotNull null
                    val value = CpuMath.thermalCelsius(File(dir, "temp").readText())
                        ?: return@mapNotNull null
                    value to label
                } catch (_: Exception) { null }
            }
            // Prefer a named CPU zone; select hottest sensor when there are several.
            return temperatures.filter { it.second == "CPU" }.maxByOrNull { it.first }
                ?: temperatures.filter { it.second == "SoC" }.maxByOrNull { it.first }
        } catch (_: Exception) { return null }
    }
}

/** Pure parsing/delta arithmetic; JVM unit tests do not require a live Android device. */
internal data class CpuTicks(val total: Long, val idle: Long)

internal object CpuMath {
    fun parse(line: String?): CpuTicks? {
        if (line == null) return null
        val fields = line.trim().split(Regex("\\s+"))
        if (fields.size < 5 || fields[0] != "cpu") return null
        // user nice system idle iowait irq softirq steal; guest fields overlap user.
        val values = fields.drop(1).take(8).map { it.toLongOrNull() ?: return null }
        if (values.size < 4 || values.any { it < 0L }) return null
        val total = values.sum()
        val idle = values[3] + values.getOrElse(4) { 0L }
        return CpuTicks(total, idle)
    }

    fun percent(before: CpuTicks?, after: CpuTicks?): Int? {
        if (before == null || after == null) return null
        val delta = after.total - before.total
        val idleDelta = after.idle - before.idle
        if (delta <= 0 || idleDelta < 0 || idleDelta > delta) return null
        return ((delta - idleDelta) * 100.0 / delta).toInt().coerceIn(0, 100)
    }

    fun thermalCelsius(text: String): Double? {
        val raw = text.trim().toDoubleOrNull() ?: return null
        if (!raw.isFinite()) return null
        val c = when {
            raw > 1000.0 -> raw / 1000.0 // millidegrees (e.g. 42500)
            raw > 125.0 -> raw / 10.0   // tenths (e.g. 425)
            else -> raw                   // degrees (e.g. 42.5)
        }
        return c.takeIf { it in -20.0..120.0 }
    }
}
