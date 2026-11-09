package com.yomu.pipeline.bubble

import android.graphics.Bitmap
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BubbleRenderBoxTest {

    private val black = 0xFF000000.toInt()
    private val white = 0xFFFFFFFF.toInt()
    private val grey = 0xFFDCDCDC.toInt()

    // An elliptical bubble outline centred at (cx, cy), radii 80x120, with a stripe of vertical
    // glyphs down its middle, on a [background] page.
    private fun page(
        width: Int = 200,
        height: Int = 300,
        cx: Int = 100,
        cy: Int = 150,
        outline: Boolean = true,
        gapAt: Int? = null,
        background: Int = white
    ): IntArray = IntArray(width * height) { i ->
        val x = i % width
        val y = i / width
        val nx = (x - cx) / 80.0
        val ny = (y - cy) / 120.0
        val r = nx * nx + ny * ny
        val onOutline = outline && r in 0.95..1.05 && (gapAt == null || kotlin.math.abs(y - gapAt) > 3)
        val glyph = x in cx - 5..cx + 5 && y in cy - 40..cy + 40 && y % 10 < 5
        when {
            onOutline || glyph -> black
            r < 1 -> white
            else -> background
        }
    }

    private val textBox = floatArrayOf(92f, 105f, 108f, 195f)

    @Test
    fun `enclosed bubble widens the render box past the thin glyph box`() {
        val box = findBubbleInterior(page(), 200, 300, textBox)!!
        assertTrue("wider than glyphs: ${box.toList()}", box[2] - box[0] > 90f)
        assertTrue("stays inside the bubble: ${box.toList()}", box[0] >= 20f && box[2] <= 180f)
    }

    @Test
    fun `no outline means no interior`() {
        assertNull(findBubbleInterior(page(outline = false), 200, 300, textBox))
    }

    @Test
    fun `a gap wider than the closing radius leaks and falls back`() {
        assertNull(findBubbleInterior(page(gapAt = 150), 200, 300, textBox))
    }

    @Test
    fun `render box never shrinks below the glyph box`() {
        val big = floatArrayOf(40f, 60f, 160f, 240f)
        val box = findBubbleInterior(page(), 200, 300, big)!!
        assertTrue(box[0] <= 40f && box[1] <= 60f && box[2] >= 160f && box[3] >= 240f)
    }

    @Test
    fun `glyphs whose box pokes past the outline still pick the bubble they mostly sit in`() {
        // The bubble's top edge is at y=30; this box starts above it, out on the page.
        val poking = floatArrayOf(92f, 25f, 108f, 195f)
        val box = findBubbleInterior(page(), 200, 300, poking)!!
        assertTrue("wider than glyphs: ${box.toList()}", box[2] - box[0] > 90f)
    }

    @Test
    fun `text on a light grey panel does not fill across the art`() {
        // Enclosed grey region, no white bubble: grey must not count as fillable.
        val panel = IntArray(200 * 300) { i ->
            val x = i % 200
            val y = i / 200
            if (x < 3 || y < 3 || x > 196 || y > 296) black else grey
        }
        assertNull(findBubbleInterior(panel, 200, 300, textBox))
    }

    private fun bitmap(width: Int, height: Int, pixels: IntArray): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, width, 0, 0, width, height)
        }

    @Test
    fun `page coordinates round trip through the search window`() {
        // Bubble placed away from the page origin so a window-offset bug shows.
        val pixels = page(width = 600, height = 800, cx = 400, cy = 500)
        val glyphs = floatArrayOf(392f, 455f, 408f, 545f)
        val box = bubbleRenderBoxes(bitmap(600, 800, pixels), mapOf(1 to glyphs)).getValue(1)

        assertTrue("wider than glyphs: ${box.toList()}", box[2] - box[0] > 90f)
        assertTrue("inside the bubble: ${box.toList()}", box[0] >= 320f && box[2] <= 480f && box[1] >= 380f && box[3] <= 620f)
    }

    @Test
    fun `a grown box reaching another bubble's glyphs keeps its glyph box`() {
        val pixels = page(width = 600, height = 800, cx = 400, cy = 500)
        val glyphs = floatArrayOf(392f, 455f, 408f, 545f)
        val neighbour = floatArrayOf(430f, 400f, 440f, 420f)
        val boxes = bubbleRenderBoxes(bitmap(600, 800, pixels), mapOf(1 to glyphs, 2 to neighbour))

        assertArrayEquals(glyphs, boxes.getValue(1), 0f)
    }
}
