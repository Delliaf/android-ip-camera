package com.github.digitallyrefined.androidipcamera.helpers

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.sqrt

internal data class LgBenchmarkFrame(
    val width: Int, val height: Int, val gray: ByteArray, val jpeg: ByteArray
)

internal data class LgImageMetrics(
    val brightness: Double, val contrastStdDev: Double,
    val blackClippingPercent: Double, val whiteClippingPercent: Double,
    val p01: Int, val p99: Int, val edgeEnergy: Double,
    val laplacianVariance: Double, val temporalLumaDifference: Double?
)

/** Uses a common output pixel grid for a FAIR comparison across ISP and CPU ROI. */
internal object LgBenchmarkImaging {
    fun cropPixels(sourceW: Int, sourceH: Int, zoom: Double, square: Boolean): Rect {
        require(sourceW > 0 && sourceH > 0 && zoom >= 1.0)
        val base = if (square) minOf(sourceW, sourceH) else sourceW
        val targetW = ((base / zoom).toInt().coerceAtLeast(2) / 2) * 2
        val targetH = ((if (square) base else sourceH) / zoom)
            .toInt().coerceAtLeast(2).let { it / 2 * 2 }
        val l = ((sourceW - targetW) / 4) * 2
        val t = ((sourceH - targetH) / 4) * 2
        return Rect(l, t, l + targetW, t + targetH)
    }

    /**
     * Copy ONLY the normalized ROI pixels from a full-resolution YUV_420_888
     * camera output. For ISP crop the HAL has already applied zoom: downsample
     * the complete camera output to native-information-size comparison pixels.
     * For CPU crop the request has full active array: center crop in software.
     */
    fun normalize(image: Image, zoom: Double, cpuCrop: Boolean,
                  square: Boolean, jpegQuality: Int = 95): LgBenchmarkFrame {
        require(image.format == ImageFormat.YUV_420_888)
        val w = image.width; val h = image.height
        val out = cropPixels(w, h, zoom, square)
        val dw = out.width(); val dh = out.height()
        // ISP mode uses a full ISP zoomed image, CPU mode uses the original center ROI.
        val input = if (cpuCrop) out else {
            if (square) {
                val side = minOf(w, h)
                Rect((w - side) / 2, (h - side) / 2, (w + side) / 2, (h + side) / 2)
            } else Rect(0, 0, w, h)
        }
        val planes = image.planes
        val nv21 = ByteArray(dw * dh * 3 / 2)
        val gray = ByteArray(dw * dh)
        fun pixel(plane: Int, px: Int, py: Int): Byte {
            val p = planes[plane]
            val x = px.coerceIn(0, (if (plane == 0) w else w / 2) - 1)
            val y = py.coerceIn(0, (if (plane == 0) h else h / 2) - 1)
            return p.buffer.get(y * p.rowStride + x * p.pixelStride)
        }
        for (y in 0 until dh) {
            val sy = input.top + (y.toLong() * input.height() / dh).toInt()
            for (x in 0 until dw) {
                val sx = input.left + (x.toLong() * input.width() / dw).toInt()
                val value = pixel(0, sx, sy)
                gray[y * dw + x] = value
                nv21[y * dw + x] = value
            }
        }
        var outPos = dw * dh
        for (y in 0 until dh / 2) {
            val sy = (input.top + (y.toLong() * 2 * input.height() / dh).toInt()) / 2
            for (x in 0 until dw / 2) {
                val sx = (input.left + (x.toLong() * 2 * input.width() / dw).toInt()) / 2
                nv21[outPos++] = pixel(2, sx, sy) // V
                nv21[outPos++] = pixel(1, sx, sy) // U
            }
        }
        val jpeg = ByteArrayOutputStream(dw * dh / 2)
        check(YuvImage(nv21, ImageFormat.NV21, dw, dh, null)
            .compressToJpeg(Rect(0, 0, dw, dh), jpegQuality.coerceIn(70, 100), jpeg))
        return LgBenchmarkFrame(dw, dh, gray, jpeg.toByteArray())
    }

    /** All scores are descriptive, not a "quality winner" without a target chart. */
    fun metrics(gray: ByteArray, w: Int, h: Int,
                previous: ByteArray? = null): LgImageMetrics {
        require(w > 4 && h > 4 && gray.size == w * h)
        val hist = IntArray(256)
        var count = 0
        var sum = 0.0; var sum2 = 0.0
        var black = 0; var white = 0
        var edge = 0.0; var lapSum = 0.0; var lapSum2 = 0.0; var detailCount = 0
        var temporal = 0.0; var temporalN = 0
        // Sample every second pixel to reduce device CPU without allocation.
        for (y in 2 until h - 2 step 2) for (x in 2 until w - 2 step 2) {
            val i = y * w + x
            val v = gray[i].toInt() and 255
            hist[v]++
            sum += v; sum2 += v.toDouble() * v
            count++
            if (v <= 5) black++
            if (v >= 250) white++
            val l = gray[i - 1].toInt() and 255
            val r = gray[i + 1].toInt() and 255
            val t = gray[i - w].toInt() and 255
            val b = gray[i + w].toInt() and 255
            edge += abs(r - l) + abs(b - t)
            val lap = (l + r + t + b - 4 * v).toDouble()
            lapSum += lap; lapSum2 += lap * lap
            detailCount++
            if (previous != null && previous.size == gray.size) {
                temporal += abs(v - (previous[i].toInt() and 255))
                temporalN++
            }
        }
        val n = count.coerceAtLeast(1)
        fun quantile(q: Double): Int {
            val threshold = (n * q).toInt()
            var cumulative = 0
            for (i in hist.indices) {
                cumulative += hist[i]
                if (cumulative >= threshold) return i
            }
            return 255
        }
        val mean = sum / n
        val lapAvg = lapSum / detailCount.coerceAtLeast(1)
        return LgImageMetrics(
            brightness = mean,
            contrastStdDev = sqrt((sum2 / n - mean * mean).coerceAtLeast(0.0)),
            blackClippingPercent = 100.0 * black / n,
            whiteClippingPercent = 100.0 * white / n,
            p01 = quantile(.01), p99 = quantile(.99),
            edgeEnergy = edge / detailCount.coerceAtLeast(1),
            laplacianVariance = (lapSum2 / detailCount.coerceAtLeast(1) -
                lapAvg * lapAvg).coerceAtLeast(0.0),
            temporalLumaDifference = if (temporalN == 0) null else temporal / temporalN
        )
    }
}
