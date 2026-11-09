package com.yomu.pipeline

data class CropBounds(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int
)

object CropBoundsCalculator {
    fun clamp(
        bitmapWidth: Int,
        bitmapHeight: Int,
        left: Int,
        top: Int,
        width: Int,
        height: Int
    ): CropBounds {
        val x = left.coerceIn(0, bitmapWidth - 1)
        val y = top.coerceIn(0, bitmapHeight - 1)
        val clampedWidth = width.coerceAtMost(bitmapWidth - x).coerceAtLeast(1)
        val clampedHeight = height.coerceAtMost(bitmapHeight - y).coerceAtLeast(1)
        return CropBounds(x = x, y = y, width = clampedWidth, height = clampedHeight)
    }
}
