package com.github.digitallyrefined.androidipcamera.helpers

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Process
import android.os.SystemClock
import androidx.preference.PreferenceManager
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Read-only device metrics. Android 8 often restricts /proc/stat and thermal zones
 * to non-system UIDs; those failures must never hide the telemetry overlay.
 *
 * CPU: system-wide delta from /proc/stat, or app CPU time / logical core count.
 * Temp: named CPU/SoC thermal zones (including Qualcomm tsens), else BATTERY
 * temperature clearly labeled. Optional bounded, read-only root fallback.
 */
internal class DevicePerformanceMonitor(private val context: Context) {
    internal data class Sample(
        val cpuPercent: Int,
        val cpuScope: String, // system, app, or unavailable
        val temperatureCelsius: Double?,
        val temperatureSource: String, // CPU, SoC, Battery, or unavailable
        val cpuNote: String = "",
        val temperatureNote: String = ""
    )

    private var previousSystem: CpuTicks? = null
    private var previousProcessMs = -1L
    private var previousTimeMs = -1L
    private var lastSampleAtMs = -1L
    private var lastTemperatureAtMs = -1L
    private var cachedTemperature: Pair<Double?, String> = null to "unavailable"
    private var temperatureNote = "No readable thermal sensor"
    private var lastSample = Sample(-1, "unavailable", null, "unavailable", "Warming up")
    private var rootUnavailable = false

    private fun allowRoot() = PreferenceManager.getDefaultSharedPreferences(context)
        .getBoolean("metrics_root_thermal_enabled", false)

    private fun readSystemCpuLine(): String? {
        val ordinary = try {
            File("/proc/stat").bufferedReader().use { it.readLine() }
        } catch (_: Exception) { null }
        if (ordinary != null && CpuMath.parse(ordinary) != null) return ordinary
        if (!allowRoot()) return null
        return readRootCommand("head -n 1 /proc/stat")?.lineSequence()
            ?.firstOrNull { it.startsWith("cpu ") }
    }

    /** Static commands only, never accepts user-controlled shell input. */
    private fun readRootCommand(command: String): String? {
        if (rootUnavailable || !allowRoot()) return null
        var process: java.lang.Process? = null
        return try {
            process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
            // Never block the web server if a root manager is prompting/denying.
            if (!process.waitFor(900L, TimeUnit.MILLISECONDS)) {
                rootUnavailable = true
                process.destroyForcibly()
                return null
            }
            if (process.exitValue() != 0) {
                rootUnavailable = true
                null
            } else {
                process.inputStream.bufferedReader().use { it.readText().take(16384) }
            }
        } catch (_: Exception) {
            rootUnavailable = true
            null
        } finally {
            try { process?.destroy() } catch (_: Exception) {}
        }
    }

    @Synchronized
    fun sample(): Sample {
        val now = SystemClock.elapsedRealtime()
        if (lastSampleAtMs >= 0 && now - lastSampleAtMs < 4_000L) return lastSample

        val systemTicks = CpuMath.parse(readSystemCpuLine())
        val systemPercent = CpuMath.percent(previousSystem, systemTicks)
        if (systemTicks != null) previousSystem = systemTicks

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
            val sensor = readThermal()
                ?: readRootThermal()
            if (sensor != null) {
                cachedTemperature = sensor
                temperatureNote = "Thermal zone (" + sensor.second + ")"
            } else {
                cachedTemperature = readBatteryTemperature()
                temperatureNote = if (cachedTemperature.first != null)
                    "CPU/SoC sensors unavailable; showing battery temperature"
                else "CPU/SoC and battery temperature unavailable"
            }
            lastTemperatureAtMs = now
        }

        val cpu = systemPercent ?: appPercent ?: -1
        val scope = when {
            systemPercent != null -> "system"
            appPercent != null -> "app"
            else -> "unavailable"
        }
        val note = when (scope) {
            "system" -> "All logical processors combined"
            "app" -> "System /proc/stat unavailable: this app's process CPU only"
            else -> "Collecting first CPU sample; check again after 5 seconds"
        }
        lastSample = Sample(cpu, scope, cachedTemperature.first,
            cachedTemperature.second, note, temperatureNote)
        lastSampleAtMs = now
        return lastSample
    }

    private fun readBatteryTemperature(): Pair<Double?, String> {
        return try {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val tenths = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
            val temp = tenths / 10.0
            if (tenths < 0 || temp !in -20.0..100.0) null to "unavailable"
            else temp to "Battery"
        } catch (_: Exception) { null to "unavailable" }
    }

    private fun bestThermal(temperatures: List<Pair<Double, String>>): Pair<Double, String>? {
        return temperatures.filter { it.second == "CPU" }.maxByOrNull { it.first }
            ?: temperatures.filter { it.second == "SoC" }.maxByOrNull { it.first }
    }

    private fun readThermal(): Pair<Double, String>? = try {
        val zones = File("/sys/class/thermal").listFiles()
            ?.filter { it.isDirectory && it.name.startsWith("thermal_zone") }
            ?.take(128).orEmpty()
        bestThermal(zones.mapNotNull { dir ->
            try {
                val kind = CpuMath.thermalType(File(dir, "type").readText())
                    ?: return@mapNotNull null
                val temp = CpuMath.thermalCelsius(File(dir, "temp").readText())
                    ?: return@mapNotNull null
                temp to kind
            } catch (_: Exception) { null }
        })
    } catch (_: Exception) { null }

    private fun readRootThermal(): Pair<Double, String>? {
        if (!allowRoot()) return null
        // All paths are fixed, read-only and bounded; no settings in shell text.
        val script = "for z in /sys/class/thermal/thermal_zone*; do " +
            "[ -r \"\$z/type\" ] && [ -r \"\$z/temp\" ] && " +
            "printf '%s|%s\\n' \"\$(cat \"\$z/type\")\" \"\$(cat \"\$z/temp\")\"; done"
        val data = readRootCommand(script) ?: return null
        val found = data.lineSequence().take(128).mapNotNull { line ->
            val pair = line.trim().split('|', limit = 2)
            if (pair.size != 2) return@mapNotNull null
            val kind = CpuMath.thermalType(pair[0]) ?: return@mapNotNull null
            val temp = CpuMath.thermalCelsius(pair[1]) ?: return@mapNotNull null
            temp to kind
        }.toList()
        return bestThermal(found)
    }
}

internal data class CpuTicks(val total: Long, val idle: Long)

/** Pure parsing and classification logic covered by JVM tests. */
internal object CpuMath {
    fun parse(line: String?): CpuTicks? {
        if (line == null) return null
        val fields = line.trim().split(Regex("\\s+"))
        if (fields.size < 5 || fields[0] != "cpu") return null
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

    /** Qualcomm tsens_tz_sensorN are SoC die sensors, not necessarily CPU-core temps. */
    fun thermalType(raw: String): String? {
        val s = raw.trim().lowercase(Locale.US)
        return when {
            "cpu" in s || "cluster" in s -> "CPU"
            "soc" in s || "ap-therm" in s ||
                "tsens" in s || "msm_therm" in s || "msm-therm" in s -> "SoC"
            else -> null
        }
    }

    fun thermalCelsius(text: String): Double? {
        val raw = text.trim().toDoubleOrNull() ?: return null
        if (!raw.isFinite()) return null
        val c = when {
            raw > 1000.0 -> raw / 1000.0
            raw > 125.0 -> raw / 10.0
            else -> raw
        }
        return c.takeIf { it in -20.0..120.0 }
    }
}
