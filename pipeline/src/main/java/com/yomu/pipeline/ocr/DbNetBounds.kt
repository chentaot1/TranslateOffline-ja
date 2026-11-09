package com.yomu.pipeline.ocr

import android.graphics.RectF

/** Converts an expanded detector quad into a crop rectangle clipped to the caller's original image. */
internal fun dbNetLineBounds(
    line: DbNetLine,
    mapWidth: Int,
    mapHeight: Int,
    imageWidth: Int,
    imageHeight: Int
): RectF? {
    require(mapWidth > 0 && mapHeight > 0 && imageWidth > 0 && imageHeight > 0)
    val scaleX = imageWidth.toFloat() / mapWidth
    val scaleY = imageHeight.toFloat() / mapHeight
    val rect = RectF(
        (line.points.minOf { it.x } * scaleX).coerceIn(0f, imageWidth.toFloat()),
        (line.points.minOf { it.y } * scaleY).coerceIn(0f, imageHeight.toFloat()),
        (line.points.maxOf { it.x } * scaleX).coerceIn(0f, imageWidth.toFloat()),
        (line.points.maxOf { it.y } * scaleY).coerceIn(0f, imageHeight.toFloat())
    )
    return rect.takeIf { it.width() > 0f && it.height() > 0f }
}
