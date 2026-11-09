package com.yomu.pipeline.bubble

import android.graphics.Bitmap

// The detector boxes the Japanese glyphs, not the bubble around them (ADR-0007), so a short
// vertical line like はい gives a tall sliver English cannot be typeset into. Flood-filling the
// bubble's white interior around the glyphs recovers the space the letterer actually had.

// Bubble interiors are paper white; light screentone backgrounds (~220) must not pass, or text
// lettered straight onto a grey panel fills out across the artwork.
private const val WHITE_LUMINANCE = 235
// Thickens dark strokes so a hairline gap in a bubble outline does not let the fill escape.
private const val OUTLINE_CLOSE_RADIUS = 2
// Detector boxes sit inset inside the glyphs; seeding just outside the box still reaches the
// interior when dense glyphs leave no free pixel inside it.
private const val SEED_PAD = 3
// The rectangle inscribed in an ellipse is 1/sqrt(2) of its bounding box on each side.
private const val INSCRIBED_FRACTION = 0.707f
// Fixed search cap, a fraction of page width; keeps the per-bubble pixel copy small.
// A bubble reaching further than this from its glyphs falls back to the detector box.
private const val MAX_SEARCH_MARGIN_OF_PAGE_WIDTH = 0.25f

/**
 * The box each bubble's translation is typeset into, keyed like [textBoxes] (detector boxes,
 * {left, top, right, bottom} in page pixels): the rectangle inside the white bubble interior
 * around the glyphs, never smaller than the detector box. A bubble keeps its detector box when
 * no enclosed interior is found (text on art, screentone bubbles, open bubbles) or when its
 * grown box would reach another bubble's glyphs, which would paint two translations over
 * each other (#88).
 */
fun bubbleRenderBoxes(bitmap: Bitmap, textBoxes: Map<Int, FloatArray>): Map<Int, FloatArray> {
    val grown = textBoxes.mapValues { (_, box) -> bubbleRenderBox(bitmap, box) }
    return grown.mapValues { (id, box) ->
        val reachesOther = textBoxes.any { (otherId, other) -> otherId != id && intersects(box, other) }
        if (reachesOther) textBoxes.getValue(id) else box
    }
}

private fun bubbleRenderBox(bitmap: Bitmap, textBox: FloatArray): FloatArray {
    val margin = minOf(
        maxOf(textBox[2] - textBox[0], textBox[3] - textBox[1]),
        bitmap.width * MAX_SEARCH_MARGIN_OF_PAGE_WIDTH
    ).toInt()
    val left = (textBox[0].toInt() - margin).coerceIn(0, bitmap.width - 1)
    val top = (textBox[1].toInt() - margin).coerceIn(0, bitmap.height - 1)
    val right = (textBox[2].toInt() + margin).coerceIn(left + 1, bitmap.width)
    val bottom = (textBox[3].toInt() + margin).coerceIn(top + 1, bitmap.height)
    val width = right - left
    val height = bottom - top
    val pixels = IntArray(width * height)
    bitmap.getPixels(pixels, 0, width, left, top, width, height)

    val inWindow = floatArrayOf(textBox[0] - left, textBox[1] - top, textBox[2] - left, textBox[3] - top)
    val interior = findBubbleInterior(pixels, width, height, inWindow) ?: return textBox
    return floatArrayOf(interior[0] + left, interior[1] + top, interior[2] + left, interior[3] + top)
}

/**
 * Pure core of [bubbleRenderBoxes] over an ARGB search window; boxes are in window coordinates.
 * Null means no enclosed interior was found.
 */
internal fun findBubbleInterior(pixels: IntArray, width: Int, height: Int, textBox: FloatArray): FloatArray? {
    val fillable = fillablePixels(pixels, width, height)
    val interior = interiorAround(fillable, width, height, textBox) ?: return null

    val insetX = (interior[2] - interior[0]) * (1 - INSCRIBED_FRACTION) / 2f
    val insetY = (interior[3] - interior[1]) * (1 - INSCRIBED_FRACTION) / 2f
    return floatArrayOf(
        minOf(interior[0] + insetX, textBox[0]),
        minOf(interior[1] + insetY, textBox[1]),
        maxOf(interior[2] - insetX, textBox[2]),
        maxOf(interior[3] - insetY, textBox[3])
    )
}

/** White pixels, minus a [OUTLINE_CLOSE_RADIUS] halo around every dark one. */
private fun fillablePixels(pixels: IntArray, width: Int, height: Int): BooleanArray {
    val fillable = BooleanArray(pixels.size) { true }
    for (y in 0 until height) for (x in 0 until width) {
        if (luminance(pixels[y * width + x]) > WHITE_LUMINANCE) continue
        for (ny in maxOf(0, y - OUTLINE_CLOSE_RADIUS)..minOf(height - 1, y + OUTLINE_CLOSE_RADIUS)) {
            for (nx in maxOf(0, x - OUTLINE_CLOSE_RADIUS)..minOf(width - 1, x + OUTLINE_CLOSE_RADIUS)) {
                fillable[ny * width + nx] = false
            }
        }
    }
    return fillable
}

/**
 * Bounds of the enclosed region holding the most seed pixels around [textBox]. Seeds also land
 * in glyph counters (口, あ) and, where the box pokes past an outline, outside the bubble; the
 * interior is the region most of the box sits in. A region reaching the window edge has leaked
 * into the page and is never chosen.
 */
private fun interiorAround(fillable: BooleanArray, width: Int, height: Int, textBox: FloatArray): IntArray? {
    val seedLeft = (textBox[0].toInt() - SEED_PAD).coerceIn(0, width - 1)
    val seedTop = (textBox[1].toInt() - SEED_PAD).coerceIn(0, height - 1)
    val seedRight = (textBox[2].toInt() + SEED_PAD).coerceIn(seedLeft + 1, width)
    val seedBottom = (textBox[3].toInt() + SEED_PAD).coerceIn(seedTop + 1, height)

    val region = IntArray(fillable.size) // 0 = unvisited, otherwise region index + 1
    val queue = IntArray(fillable.size)
    val bounds = mutableListOf<IntArray>()
    val leaks = mutableListOf<Boolean>()
    val seedCounts = mutableListOf<Int>()

    for (sy in seedTop until seedBottom) for (sx in seedLeft until seedRight) {
        val seed = sy * width + sx
        if (!fillable[seed]) continue
        if (region[seed] == 0) {
            val label = bounds.size + 1
            val box = intArrayOf(sx, sy, sx + 1, sy + 1)
            var head = 0
            var tail = 0
            queue[tail++] = seed
            region[seed] = label
            while (head < tail) {
                val i = queue[head++]
                val x = i % width
                val y = i / width
                box[0] = minOf(box[0], x)
                box[1] = minOf(box[1], y)
                box[2] = maxOf(box[2], x + 1)
                box[3] = maxOf(box[3], y + 1)
                if (x > 0 && fillable[i - 1] && region[i - 1] == 0) { region[i - 1] = label; queue[tail++] = i - 1 }
                if (x < width - 1 && fillable[i + 1] && region[i + 1] == 0) { region[i + 1] = label; queue[tail++] = i + 1 }
                if (y > 0 && fillable[i - width] && region[i - width] == 0) { region[i - width] = label; queue[tail++] = i - width }
                if (y < height - 1 && fillable[i + width] && region[i + width] == 0) { region[i + width] = label; queue[tail++] = i + width }
            }
            bounds.add(box)
            // A bubble clipped by the screen edge counts as leaked and falls back;
            // handle it if captures often cut bubbles.
            leaks.add(box[0] == 0 || box[1] == 0 || box[2] == width || box[3] == height)
            seedCounts.add(0)
        }
        seedCounts[region[seed] - 1]++
    }

    val best = seedCounts.indices.filter { !leaks[it] }.maxByOrNull { seedCounts[it] } ?: return null
    return bounds[best]
}

private fun intersects(a: FloatArray, b: FloatArray): Boolean =
    a[0] < b[2] && b[0] < a[2] && a[1] < b[3] && b[1] < a[3]

internal fun luminance(argb: Int): Int =
    ((argb shr 16 and 0xFF) * 299 + (argb shr 8 and 0xFF) * 587 + (argb and 0xFF) * 114) / 1000
