package com.yomu.pipeline.layout

import com.yomu.pipeline.bubble.Bubble

/** Orders a continuous strip by top edge, or page rows by explicit horizontal direction. */
fun sortedBubbles(bubbles: List<Bubble>, verticalStrip: Boolean, leftToRight: Boolean): List<Bubble> {
    val direction = if (leftToRight) 1f else -1f
    val topFirst = bubbles.sortedWith(compareBy<Bubble> { it.boundingBox.top }
        .thenBy { if (verticalStrip) direction * it.boundingBox.centerX() else it.boundingBox.left }.thenBy { it.id })
    if (verticalStrip) return topFirst
    val rows = mutableListOf<MutableList<Bubble>>()
    for (bubble in topFirst) {
        val row = rows.lastOrNull()
        val anchor = row?.firstOrNull()?.boundingBox
        val bounds = bubble.boundingBox
        val overlap = if (anchor == null) 0f else minOf(anchor.bottom, bounds.bottom) - maxOf(anchor.top, bounds.top)
        if (anchor != null && overlap >= minOf(anchor.height(), bounds.height()) * 0.3f) {
            row.add(bubble)
        } else {
            rows.add(mutableListOf(bubble))
        }
    }
    return rows.flatMap { row -> row.sortedWith(compareBy<Bubble> { direction * it.boundingBox.centerX() }
        .thenBy { it.boundingBox.top }.thenBy { it.id }) }
}
