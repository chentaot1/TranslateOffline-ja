package com.yomu.pipeline.layout

import android.graphics.RectF
import kotlin.math.abs

/**
 * Returns only free-text paragraphs, excluding lines with at least 25% of their area in a bubble.
 * Closely aligned horizontal lines or vertical columns merge only when the entire group remains aligned.
 * Independently implements the supplemental-text principle in jedzqer/manga-translator-android
 * (b729828dc90b0840538c9e18f5ba12fcf00dfea9), without its page framework.
 */
fun supplementalTextRegions(detected: List<RectF>, bubbles: List<RectF>, leftToRight: Boolean): List<RectF> {
    val occupied = bubbles.filter { it.validRegion() }
    val unique = mutableListOf<RectF>()
    val candidates = detected.filter { it.validRegion() }
        .filter { line -> occupied.none { intersectionArea(line, it) / line.area() >= 0.25f } }
        .sortedWith(compareByDescending<RectF> { it.area() }.thenBy { it.top }.thenBy { it.left }
            .thenBy { it.bottom }.thenBy { it.right })
    for (line in candidates) {
        if (unique.none { intersectionArea(line, it) / minOf(line.area(), it.area()) >= 0.8f }) {
            unique.add(RectF(line))
        }
    }
    val groups = mutableListOf<MutableList<RectF>>()
    for (line in unique.sortedWith(compareBy<RectF> { it.top }.thenBy { it.left })) {
        val group = groups.firstOrNull { canJoin(it, line, occupied) }
        if (group == null) groups.add(mutableListOf(line)) else group.add(line)
    }
    val direction = if (leftToRight) 1f else -1f
    return groups.map { boundsOf(it) }.sortedWith(compareBy<RectF> { it.top }
        .thenBy { direction * it.centerX() }.thenBy { it.bottom }.thenBy { it.right })
}

private fun RectF.validRegion(): Boolean = left.isFinite() && top.isFinite() && right.isFinite() &&
    bottom.isFinite() && width() > 0f && height() > 0f

private fun RectF.area(): Float = width() * height()

private fun intersectionArea(a: RectF, b: RectF): Float =
    (minOf(a.right, b.right) - maxOf(a.left, b.left)).coerceAtLeast(0f) *
        (minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)).coerceAtLeast(0f)

private fun boundsOf(lines: List<RectF>): RectF = RectF(lines.minOf { it.left }, lines.minOf { it.top },
    lines.maxOf { it.right }, lines.maxOf { it.bottom })

private fun orientation(line: RectF): Int = when {
    line.width() >= line.height() * 1.5f -> 1
    line.height() >= line.width() * 1.5f -> -1
    else -> 0
}

private fun canJoin(group: List<RectF>, candidate: RectF, occupied: List<RectF>): Boolean {
    val direction = orientation(candidate)
    if (direction == 0 || group.any { orientation(it) != direction }) return false
    val all = group + candidate
    if (occupied.any { intersectionArea(boundsOf(all), it) > 0f }) return false
    val horizontal = direction == 1
    fun thickness(line: RectF): Float = if (horizontal) line.height() else line.width()
    fun start(line: RectF): Float = if (horizontal) line.top else line.left
    fun end(line: RectF): Float = if (horizontal) line.bottom else line.right
    fun leading(line: RectF): Float = if (horizontal) line.left else line.top
    fun trailing(line: RectF): Float = if (horizontal) line.right else line.bottom
    val minThickness = all.minOf { thickness(it) }
    if (minThickness / all.maxOf { thickness(it) } < 0.67f) return false
    val leadingSpread = all.maxOf { leading(it) } - all.minOf { leading(it) }
    val trailingSpread = all.maxOf { trailing(it) } - all.minOf { trailing(it) }
    if (minOf(leadingSpread, trailingSpread) > minThickness * 0.75f) return false
    val commonOverlap = all.minOf { trailing(it) } - all.maxOf { leading(it) }
    val shortest = all.minOf { trailing(it) - leading(it) }
    if (commonOverlap < shortest * 0.65f) return false
    return all.sortedBy { start(it) }.zipWithNext().all { (before, after) ->
        val gap = start(after) - end(before)
        gap >= -minThickness * 0.15f && gap <= minThickness * 0.8f &&
            abs(start(after) - start(before)) >= minThickness * 0.5f
    }
}
