package com.yomu.pipeline.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import kotlin.math.ceil
import kotlin.math.roundToInt

internal data class ChineseImageTensor(val values: FloatArray, val shape: LongArray)

/** Matches the official Paddle Android RGB recognition and BGR detection preprocessing. */
internal fun chineseImageTensor(bitmap: Bitmap, recognition: Boolean): ChineseImageTensor {
    val scale = minOf(maxOf(1f, 736f / minOf(bitmap.width, bitmap.height)),
        4000f / maxOf(bitmap.width, bitmap.height))
    val width = if (recognition) ceil(48.0 * bitmap.width / bitmap.height).toInt().coerceIn(1, 3200)
        else ((bitmap.width * scale / 32f).roundToInt() * 32).coerceAtLeast(32)
    val height = if (recognition) 48 else ((bitmap.height * scale / 32f).roundToInt() * 32).coerceAtLeast(32)
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
            val shift = if (recognition) (2 - channel) * 8 else channel * 8
            val alpha = (pixel ushr 24) / 255f
            val value = (((pixel shr shift) and 255) / 255f) * alpha + (1f - alpha)
            values[channel * plane + index] = if (recognition) (value - 0.5f) / 0.5f
                else (value - mean[channel]) / std[channel]
        }
    }
    return ChineseImageTensor(values, longArrayOf(1, 3, height.toLong(), width.toLong()))
}

/** Rectifies a detector's rotated rectangle and rotates tall crops like the official Paddle cropper. */
internal fun chineseLineCrop(source: Bitmap, line: ChineseLine, mapWidth: Int, mapHeight: Int): Bitmap {
    val scaleX = source.width.toFloat() / mapWidth
    val scaleY = source.height.toFloat() / mapHeight
    val points = line.points.flatMap { listOf(it.x * scaleX, it.y * scaleY) }.toFloatArray()
    val width = kotlin.math.hypot(points[2] - points[0], points[3] - points[1]).roundToInt().coerceAtLeast(1)
    val height = kotlin.math.hypot(points[6] - points[0], points[7] - points[1]).roundToInt().coerceAtLeast(1)
    val matrix = chineseLineTransform(points, width, height)
    val crop = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    try {
        Canvas(crop).apply {
            drawColor(Color.WHITE)
            drawBitmap(source, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
        }
        if (height < width * 1.5f) return crop
        val rotation = Matrix().apply { postRotate(270f) }
        val rotated = Bitmap.createBitmap(crop, 0, 0, width, height, rotation, true)
        if (rotated !== crop) crop.recycle()
        return rotated
    } catch (error: Exception) {
        crop.recycle()
        throw error
    }
}

/** Maps a parallelogram's top-left, top-right and bottom-left to the crop's coordinate axes. */
internal fun chineseLineTransform(points: FloatArray, width: Int, height: Int): Matrix {
    require(points.size == 8 && points.all { it.isFinite() } && width > 0 && height > 0)
    val ux = points[2] - points[0]
    val uy = points[3] - points[1]
    val vx = points[6] - points[0]
    val vy = points[7] - points[1]
    val determinant = ux * vy - uy * vx
    require(kotlin.math.abs(determinant) > 0.0001f) { "Text line corners must span an area" }
    val a = width * vy / determinant
    val b = -width * vx / determinant
    val d = -height * uy / determinant
    val e = height * ux / determinant
    return Matrix().apply {
        setValues(floatArrayOf(a, b, -a * points[0] - b * points[1],
            d, e, -d * points[0] - e * points[1], 0f, 0f, 1f))
    }
}
