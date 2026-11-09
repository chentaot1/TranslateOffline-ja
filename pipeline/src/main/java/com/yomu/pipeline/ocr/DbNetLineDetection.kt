package com.yomu.pipeline.ocr

import kotlin.math.abs
import kotlin.math.hypot

internal data class TextPoint(val x: Float, val y: Float)
internal data class DbNetLine(val points: List<TextPoint>, val width: Float, val height: Float) {
    val centerY: Float get() = points.sumOf { it.y.toDouble() }.toFloat() / 4f
    val centerX: Float get() = points.sumOf { it.x.toDouble() }.toFloat() / 4f
}

/** DB threshold components become expanded minimum-area rectangles, one crop per text line. */
internal fun detectDbNetLines(probabilities: FloatArray, width: Int, height: Int): List<DbNetLine> {
    require(width > 0 && height > 0 && probabilities.size == width * height)
    val visited = BooleanArray(probabilities.size)
    val queue = IntArray(probabilities.size)
    val lines = mutableListOf<DbNetLine>()
    var candidates = 0
    for (start in probabilities.indices) {
        if (visited[start] || probabilities[start] <= 0.2f) continue
        if (++candidates > 3000) break
        var tail = 1
        var head = 0
        queue[0] = start
        visited[start] = true
        val boundary = mutableListOf<TextPoint>()
        while (head < tail) {
            val pixel = queue[head++]
            val x = pixel % width
            val y = pixel / width
            var isBoundary = false
            for (dy in -1..1) for (dx in -1..1) {
                val nx = x + dx
                val ny = y + dy
                if (nx !in 0 until width || ny !in 0 until height) {
                    isBoundary = true
                    continue
                }
                val next = ny * width + nx
                if (probabilities[next] <= 0.2f) isBoundary = true
                else if (!visited[next]) {
                    visited[next] = true
                    queue[tail++] = next
                }
            }
            if (isBoundary) boundary.add(TextPoint(x.toFloat(), y.toFloat()))
        }
        val rectangle = minimumRectangle(convexHull(boundary)) ?: continue
        if (minOf(rectangle.width, rectangle.height) < 3f) continue
        if (rectangleScore(rectangle, probabilities, width, height) < 0.45f) continue
        val expanded = expandRectangle(rectangle)
        if (minOf(expanded.width, expanded.height) >= 5f) lines.add(expanded)
    }
    return orderDbNetLines(lines)
}

private fun cross(a: TextPoint, b: TextPoint, c: TextPoint): Float =
    (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)

private fun convexHull(points: List<TextPoint>): List<TextPoint> {
    val sorted = points.distinct().sortedWith(compareBy<TextPoint> { it.x }.thenBy { it.y })
    if (sorted.size < 3) return sorted
    fun half(sequence: List<TextPoint>): List<TextPoint> {
        val hull = mutableListOf<TextPoint>()
        for (p in sequence) {
            while (hull.size >= 2 && cross(hull[hull.lastIndex - 1], hull.last(), p) <= 0f) hull.removeAt(hull.lastIndex)
            hull.add(p)
        }
        return hull.dropLast(1)
    }
    return half(sorted) + half(sorted.reversed())
}

private fun minimumRectangle(hull: List<TextPoint>): DbNetLine? {
    if (hull.size < 3) return null
    var best: DbNetLine? = null
    var bestArea = Float.POSITIVE_INFINITY
    for (index in hull.indices) {
        val a = hull[index]
        val b = hull[(index + 1) % hull.size]
        val length = hypot(b.x - a.x, b.y - a.y)
        if (length == 0f) continue
        val ux = (b.x - a.x) / length
        val uy = (b.y - a.y) / length
        val xs = hull.map { it.x * ux + it.y * uy }
        val ys = hull.map { -it.x * uy + it.y * ux }
        val loX = xs.min()
        val hiX = xs.max()
        val loY = ys.min()
        val hiY = ys.max()
        val area = (hiX - loX) * (hiY - loY)
        if (area < bestArea) {
            fun point(x: Float, y: Float): TextPoint = TextPoint(x * ux - y * uy, x * uy + y * ux)
            val corners = listOf(point(loX, loY), point(hiX, loY), point(hiX, hiY), point(loX, hiY))
            val byX = corners.sortedWith(compareBy<TextPoint> { it.x }.thenBy { it.y })
            val left = byX.take(2).sortedBy { it.y }
            val right = byX.takeLast(2).sortedBy { it.y }
            val ordered = listOf(left[0], right[0], right[1], left[1])
            best = DbNetLine(ordered, distance(ordered[0], ordered[1]), distance(ordered[0], ordered[3]))
            bestArea = area
        }
    }
    return best
}

private fun distance(a: TextPoint, b: TextPoint): Float = hypot(b.x - a.x, b.y - a.y)

private fun rectangleScore(line: DbNetLine, probabilities: FloatArray, width: Int, height: Int): Float {
    val points = line.points
    val minX = points.minOf { it.x.toInt() }.coerceIn(0, width - 1)
    val maxX = points.maxOf { it.x.toInt() }.coerceIn(0, width - 1)
    val minY = points.minOf { it.y.toInt() }.coerceIn(0, height - 1)
    val maxY = points.maxOf { it.y.toInt() }.coerceIn(0, height - 1)
    var score = 0.0
    var count = 0
    for (y in minY..maxY) for (x in minX..maxX) {
        val p = TextPoint(x.toFloat(), y.toFloat())
        val products = points.indices.map { cross(points[it], points[(it + 1) % 4], p) }
        if (products.all { it >= -0.001f } || products.all { it <= 0.001f }) {
            score += probabilities[y * width + x]
            count++
        }
    }
    return if (count == 0) 0f else (score / count).toFloat()
}

private fun expandRectangle(line: DbNetLine): DbNetLine {
    val expansion = line.width * line.height * 1.4f / (2f * (line.width + line.height))
    val a = line.points[0]
    val b = line.points[1]
    val d = line.points[3]
    val ux = (b.x - a.x) / line.width
    val uy = (b.y - a.y) / line.width
    val vx = (d.x - a.x) / line.height
    val vy = (d.y - a.y) / line.height
    val signs = listOf(-1f to -1f, 1f to -1f, 1f to 1f, -1f to 1f)
    return DbNetLine(line.points.mapIndexed { index, p ->
        val (u, v) = signs[index]
        TextPoint(p.x + expansion * (u * ux + v * vx), p.y + expansion * (u * uy + v * vy))
    }, line.width + 2 * expansion, line.height + 2 * expansion)
}

/** Fixed row anchors prevent chained overlaps from making ordering dependent on input permutation. */
private fun orderDbNetLines(lines: List<DbNetLine>): List<DbNetLine> {
    val rows = mutableListOf<MutableList<DbNetLine>>()
    for (line in lines.sortedWith(compareBy<DbNetLine> { it.centerY }.thenBy { it.centerX })) {
        val row = rows.lastOrNull()
        val anchor = row?.firstOrNull()
        if (anchor != null && abs(line.centerY - anchor.centerY) < minOf(line.height, anchor.height) * 0.5f) {
            row.add(line)
        } else rows.add(mutableListOf(line))
    }
    return rows.flatMap { it.sortedBy { line -> line.centerX } }
}
