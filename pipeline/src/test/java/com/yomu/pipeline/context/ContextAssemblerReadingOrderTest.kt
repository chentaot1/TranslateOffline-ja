package com.yomu.pipeline.context

import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import com.yomu.pipeline.bubble.Bubble
import com.yomu.pipeline.ocr.OcrResult
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ContextAssemblerReadingOrderTest {

    private val assembler = ContextAssembler()

    private val paper = 0xFFFFFFFF.toInt()
    private val art = 0xFF808080.toInt()
    private val black = 0xFF000000.toInt()

    private val onePanel = page(1000, 1000, Rect(0, 0, 1000, 1000) to art)
    private val twoPanelsSideBySide = page(
        1000,
        1000,
        Rect(0, 0, 150, 1000) to art,
        Rect(170, 0, 1000, 1000) to art
    )

    @Test
    fun panelsSortedRightToLeft() {
        val leftPanelBubble = bubble(1, left = 0f, top = 0f, right = 100f, bottom = 100f)
        val rightPanelBubble = bubble(2, left = 200f, top = 0f, right = 300f, bottom = 100f)

        val result = assembler.assemble(
            bubbles = listOf(leftPanelBubble, rightPanelBubble),
            ocrResults = emptyMap(),
            page = twoPanelsSideBySide
        )

        assertEquals(listOf(2, 1), result.orderedBubbleIds())
    }

    @Test
    fun bubblesWithinPanelSortedTopToBottom() {
        val top = bubble(1, left = 0f, top = 0f, right = 100f, bottom = 30f)
        val middle = bubble(2, left = 0f, top = 40f, right = 100f, bottom = 70f)
        val bottom = bubble(3, left = 0f, top = 80f, right = 100f, bottom = 110f)

        val result = assembler.assemble(
            bubbles = listOf(bottom, top, middle),
            ocrResults = emptyMap(),
            page = onePanel
        )

        assertEquals(listOf(1, 2, 3), result.orderedBubbleIds())
    }

    @Test
    fun bubblesSideBySideReadRightToLeftBeforeTheRowBelow() {
        val upperLeft = bubble(1, left = 0f, top = 0f, right = 40f, bottom = 40f)
        val upperRight = bubble(2, left = 60f, top = 10f, right = 100f, bottom = 50f)
        val lowerRight = bubble(3, left = 60f, top = 100f, right = 100f, bottom = 140f)

        val result = assembler.assemble(
            bubbles = listOf(upperLeft, upperRight, lowerRight),
            ocrResults = emptyMap(),
            page = onePanel
        )

        assertEquals(listOf(2, 1, 3), result.orderedBubbleIds())
    }

    @Test
    fun everyBubbleAppearsInExactlyOnePanel() {
        // A wide panel overlapping a lower, narrower one along X once put both bubbles in both
        // panels, and the page prompt carried duplicate [id] lines (found by the #165 run records).
        val wide = bubble(1, left = 0f, top = 0f, right = 300f, bottom = 50f)
        val lowerNarrow = bubble(2, left = 100f, top = 500f, right = 150f, bottom = 550f)
        val twoTiers = page(1000, 1000, Rect(0, 0, 1000, 200) to art, Rect(0, 300, 1000, 1000) to art)

        val result = assembler.assemble(
            bubbles = listOf(wide, lowerNarrow),
            ocrResults = emptyMap(),
            page = twoTiers
        )

        assertEquals(listOf(listOf(1), listOf(2)), result.blocks.map { block -> block.readingOrder })
    }

    @Test
    fun emptyBubblesReturnsEmpty() {
        val result = assembler.assemble(
            bubbles = emptyList(),
            ocrResults = emptyMap(),
            page = onePanel
        )

        assertEquals(emptyList<Int>(), result.orderedBubbleIds())
    }

    @Test
    fun singleBubbleUnchanged() {
        val only = bubble(1, left = 10f, top = 10f, right = 50f, bottom = 50f)

        val result = assembler.assemble(
            bubbles = listOf(only),
            ocrResults = emptyMap(),
            page = onePanel
        )

        assertEquals(listOf(1), result.orderedBubbleIds())
    }

    @Test
    fun eachPanelIsItsOwnBlock() {
        val leftPanel = bubble(1, left = 0f, top = 0f, right = 100f, bottom = 100f)
        val rightPanel = bubble(2, left = 200f, top = 0f, right = 300f, bottom = 100f)

        val result = assembler.assemble(
            bubbles = listOf(leftPanel, rightPanel),
            ocrResults = emptyMap(),
            page = twoPanelsSideBySide
        )

        assertEquals(2, result.blocks.size)
        assertEquals(listOf(2), result.blocks[0].bubbles.map { it.id })
        assertEquals(listOf(1), result.blocks[1].bubbles.map { it.id })
    }

    @Test
    fun textByBubbleIdPreservesOcrMappingWhenReadingOrderDiffersFromId() {
        // Same panel, right half read before left: reading order [2, 1] differs from id order.
        val leftHalf = bubble(1, left = 0f, top = 0f, right = 40f, bottom = 40f)
        val rightHalf = bubble(2, left = 60f, top = 0f, right = 100f, bottom = 40f)

        val result = assembler.assemble(
            bubbles = listOf(leftHalf, rightHalf),
            ocrResults = mapOf(
                1 to ocr("left text"),
                2 to ocr("right text")
            ),
            page = onePanel
        )

        val block = result.blocks.single()
        assertEquals(listOf(2, 1), block.bubbles.map { it.id })
        assertEquals(listOf(2, 1), block.readingOrder)
        assertEquals("left text", block.textByBubbleId[1]?.text)
        assertEquals("right text", block.textByBubbleId[2]?.text)
    }

    @Test
    fun aThreeTierPageIsReadTierByTierAndPanelByPanel() {
        // balloon-dense-dialogue as the emulator captured it (#367): the detector's bubble boxes,
        // the panels traced from capture.png, Photos' black letterbox and the floating button.
        // Gaps inside a panel (10 to 12) are wider than the gutters between tiers, so only the
        // drawn gutters can tell the tiers apart. Expected order is the hand reading order.
        val page = page(
            1280,
            2856,
            Rect(0, 0, 1280, 526) to black,
            Rect(130, 362, 290, 520) to paper,
            Rect(0, 2330, 1280, 2856) to black,
            Rect(570, 526, 1280, 1140) to art,
            Rect(115, 640, 555, 1140) to art,
            Rect(115, 1200, 1165, 1495) to art,
            Rect(915, 1545, 1165, 2215) to art,
            Rect(335, 1545, 900, 2215) to art,
            Rect(115, 1545, 315, 2215) to art
        )
        val bubbles = listOf(
            bubble(1, left = 773f, top = 1562f, right = 865f, bottom = 1837f),
            bubble(2, left = 1016f, top = 1218f, right = 1121f, bottom = 1449f),
            bubble(3, left = 148f, top = 1224f, right = 295f, bottom = 1447f),
            bubble(4, left = 597f, top = 579f, right = 755f, bottom = 770f),
            bubble(5, left = 406f, top = 1911f, right = 471f, bottom = 2152f),
            bubble(6, left = 1054f, top = 568f, right = 1247f, bottom = 734f),
            bubble(7, left = 120f, top = 1961f, right = 187f, bottom = 2087f),
            bubble(8, left = 346f, top = 1245f, right = 443f, bottom = 1381f),
            bubble(9, left = 934f, top = 788f, right = 967f, bottom = 899f),
            bubble(10, left = 1109f, top = 1574f, right = 1141f, bottom = 1672f),
            bubble(11, left = 132f, top = 674f, right = 255f, bottom = 1100f),
            bubble(12, left = 954f, top = 1962f, right = 1044f, bottom = 2180f),
            bubble(13, left = 477f, top = 672f, right = 507f, bottom = 811f),
            bubble(14, left = 264f, top = 1575f, right = 296f, bottom = 1650f),
            bubble(15, left = 850f, top = 1218f, right = 875f, bottom = 1395f)
        )

        val result = assembler.assemble(bubbles, ocrResults = emptyMap(), page = page)

        assertEquals(
            listOf(listOf(6, 4, 9), listOf(13, 11), listOf(2, 15, 8, 3), listOf(10, 12), listOf(1, 5), listOf(14, 7)),
            result.blocks.map { block -> block.bubbles.map { it.id } }
        )
    }

    private fun page(width: Int, height: Int, vararg fills: Pair<Rect, Int>): Bitmap {
        val pixels = IntArray(width * height) { paper }
        for ((rect, colour) in fills) {
            for (y in rect.top until rect.bottom) pixels.fill(colour, y * width + rect.left, y * width + rect.right)
        }
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, width, 0, 0, width, height)
        }
    }

    private fun bubble(
        id: Int,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float
    ): Bubble = Bubble(
        id = id,
        boundingBox = RectF(left, top, right, bottom),
        confidence = 0.9f
    )

    private fun ocr(text: String): OcrResult = OcrResult(
        text = text,
        confidence = 1f,
        boundingBox = floatArrayOf(0f, 0f, 1f, 1f)
    )

    private fun PageContext.orderedBubbleIds(): List<Int> =
        blocks.flatMap { it.bubbles }.map { it.id }
}
