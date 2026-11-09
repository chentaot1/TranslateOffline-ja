package io.github.ilovephos.translate

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

data class SampleRegion(val left: Float, val top: Float, val right: Float, val bottom: Float)

/** A small immutable image of the source viewport; comparisons tolerate capture rounding and isolated noise. */
class ScreenSample private constructor(
    private val width: Int,
    private val height: Int,
    private val pixels: IntArray,
    private val columns: Int,
    private val rows: Int,
    ignored: List<SampleRegion>
) {
    private val excluded = mask(ignored)
    val cacheKey: Long = run {
        var hash = (-3750763034362895579L xor width.toLong()) * 1099511628211L xor height.toLong()
        for (index in pixels.indices) if (!excluded[index]) {
            hash = (hash xor (pixels[index] and 0x00f8f8f8).toLong()) * 1099511628211L
        }
        hash
    }

    /** Mask whole sample cells touching our lettering, controls or system bars, in source-screen coordinates. */
    fun matches(other: ScreenSample, painted: List<SampleRegion> = emptyList()): Boolean {
        if (width != other.width || height != other.height || columns != other.columns || rows != other.rows) return false
        val overlay = mask(painted)
        var compared = 0
        var different = 0
        for (index in pixels.indices) {
            if (excluded[index] || other.excluded[index] || overlay[index]) continue
            compared++
            val a = pixels[index]
            val b = other.pixels[index]
            val delta = maxOf(abs((a shr 16 and 255) - (b shr 16 and 255)),
                abs((a shr 8 and 255) - (b shr 8 and 255)), abs((a and 255) - (b and 255)))
            if (delta >= 16) different++
        }
        // Keep a finished translation when no source area is visible; gestures and resize still invalidate it.
        return different < maxOf(3, ceil(compared * 0.003).toInt())
    }

    private fun mask(regions: List<SampleRegion>): BooleanArray {
        val result = BooleanArray(pixels.size)
        for (region in regions) {
            if (!listOf(region.left, region.top, region.right, region.bottom).all { it.isFinite() }) continue
            if (region.right <= region.left || region.bottom <= region.top) continue
            val left = floor(region.left.toDouble() * columns / width).toInt().coerceIn(0, columns)
            val right = ceil(region.right.toDouble() * columns / width).toInt().coerceIn(0, columns)
            val top = floor(region.top.toDouble() * rows / height).toInt().coerceIn(0, rows)
            val bottom = ceil(region.bottom.toDouble() * rows / height).toInt().coerceIn(0, rows)
            for (row in top until bottom) for (column in left until right) result[row * columns + column] = true
        }
        return result
    }

    companion object {
        const val COLUMNS = 96
        const val ROWS = 192

        /** [pixels] contains the downsampled image, row by row, independently owned after construction. */
        fun fromPixels(width: Int, height: Int, pixels: IntArray, columns: Int = COLUMNS, rows: Int = ROWS,
                       ignored: List<SampleRegion> = emptyList()): ScreenSample {
            require(width > 0 && height > 0 && columns > 0 && rows > 0)
            require(columns.toLong() * rows == pixels.size.toLong())
            return ScreenSample(width, height, pixels.copyOf(), columns, rows, ignored)
        }
    }
}
