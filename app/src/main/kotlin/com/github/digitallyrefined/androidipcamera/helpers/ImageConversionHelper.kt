package com.github.digitallyrefined.androidipcamera.helpers

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

fun convertYUV420toNV21(image: ImageProxy, reuse: ByteArray? = null, reuseRow: ByteArray? = null): ByteArray {
    val crop: Rect = image.cropRect
    val format: Int = image.format
    val width: Int = crop.width()
    val height: Int = crop.height()
    val planes: Array<ImageProxy.PlaneProxy> = image.planes
    val requiredSize = width * height * ImageFormat.getBitsPerPixel(format) / 8
    val data = if (reuse != null && reuse.size == requiredSize) reuse else ByteArray(requiredSize)
    val rowStride = planes[0].rowStride
    val rowData = if (reuseRow != null && reuseRow.size >= rowStride) reuseRow else ByteArray(rowStride)

    var channelOffset = 0
    var outputStride = 1
    for (i in planes.indices) {
        when (i) {
            0 -> {
                channelOffset = 0
                outputStride = 1
            }
            1 -> {
                channelOffset = width * height + 1
                outputStride = 2
            }
            2 -> {
                channelOffset = width * height
                outputStride = 2
            }
        }

        val buffer: ByteBuffer = planes[i].buffer
        val rowStride: Int = planes[i].rowStride
        val pixelStride: Int = planes[i].pixelStride

        val shift: Int = if (i == 0) 0 else 1
        val w: Int = width shr shift
        val h: Int = height shr shift
        buffer.position(rowStride * (crop.top shr shift) + pixelStride * (crop.left shr shift))
        for (row in 0 until h) {
            val length: Int
            if (pixelStride == 1 && outputStride == 1) {
                length = w
                buffer.get(data, channelOffset, length)
                channelOffset += length
            } else {
                length = (w - 1) * pixelStride + 1
                buffer.get(rowData, 0, length)
                for (col in 0 until w) {
                    data[channelOffset] = rowData[col * pixelStride]
                    channelOffset += outputStride
                }
            }
            if (row < h - 1) {
                buffer.position(buffer.position() + rowStride - length)
            }
        }
    }
    return data
}

/**
 * Crop inside the full-resolution NV21 source *before* JPEG compression and
 * without first shrinking it to the output stream's target size.
 */
fun convertNV21toJPEG(
    nv21: ByteArray, width: Int, height: Int, quality: Int = 80,
    crop: Rect = Rect(0, 0, width, height)
): ByteArray {
    val out = ByteArrayOutputStream()
    val yuv = YuvImage(nv21, ImageFormat.NV21, width, height, null)
    yuv.compressToJpeg(crop, quality, out)
    return out.toByteArray()
}

fun nv21RoiRect(width: Int, height: Int, zoom: Float, centerX: Float, centerY: Float): Rect {
    val region = RoiCrop.window(zoom, centerX, centerY)
    // 4:2:0 chroma dimensions and offsets should be even.
    val w = ((width * region.width).toInt() / 2 * 2).coerceIn(2, width)
    val h = ((height * region.height).toInt() / 2 * 2).coerceIn(2, height)
    val x = ((width * region.left).toInt() / 2 * 2).coerceIn(0, width - w)
    val y = ((height * region.top).toInt() / 2 * 2).coerceIn(0, height - h)
    return Rect(x, y, x + w, y + h)
}
