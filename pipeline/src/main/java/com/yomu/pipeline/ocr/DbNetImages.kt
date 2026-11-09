package com.yomu.pipeline.ocr

import android.graphics.Bitmap
import kotlin.math.roundToInt

internal data class DbNetImageTensor(val values: FloatArray, val shape: LongArray)

/** Matches the PP-OCRv6 DBNet BGR detection preprocessing and multiple-of-32 sizing. */
internal fun dbNetImageTensor(bitmap: Bitmap): DbNetImageTensor {
    val scale = minOf(
        maxOf(1f, 736f / minOf(bitmap.width, bitmap.height)),
        4000f / maxOf(bitmap.width, bitmap.height)
    )
    val width = ((bitmap.width * scale / 32f).roundToInt() * 32).coerceAtLeast(32)
    val height = ((bitmap.height * scale / 32f).roundToInt() * 32).coerceAtLeast(32)
    val resized = Bitmap.createScaledBitmap(bitmap, width, height, true)
    val pixels = IntArray(width * height)
    try {
        resized.getPixels(pixels, 0, width, 0, 0, width, height)
    } finally {
        if (resized !== bitmap) resized.recycle()
    }
    val plane = pixels.size
    val values = FloatArray(plane * 3)
    val mean = floatArrayOf(0.485f, 0.456f, 0.406f)
    val std = floatArrayOf(0.229f, 0.224f, 0.225f)
    for (index in pixels.indices) {
        val pixel = pixels[index]
        for (channel in 0..2) {
            val shift = channel * 8
            val alpha = (pixel ushr 24) / 255f
            val value = (((pixel shr shift) and 255) / 255f) * alpha + (1f - alpha)
            values[channel * plane + index] = (value - mean[channel]) / std[channel]
        }
    }
    return DbNetImageTensor(values, longArrayOf(1, 3, height.toLong(), width.toLong()))
}
