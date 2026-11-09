package com.yomu.pipeline.layout

import android.graphics.RectF
import com.yomu.pipeline.bubble.Bubble
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BubbleReadingOrderTest {
    private val left = Bubble(1, RectF(10f, 12f, 50f, 40f), 1f)
    private val right = Bubble(2, RectF(70f, 10f, 110f, 40f), 1f)
    private val bottom = Bubble(3, RectF(0f, 100f, 40f, 130f), 1f)

    @Test fun mangaReadsRowsRightToLeft() {
        assertEquals(listOf(2, 1, 3), sortedBubbles(listOf(bottom, left, right), false, false).map { it.id })
    }

    @Test fun explicitLeftToRightChangesDirectionWithinRow() {
        assertEquals(listOf(1, 2, 3), sortedBubbles(listOf(bottom, right, left), false, true).map { it.id })
    }

    @Test fun continuousStripReadsStrictlyTopToBottom() {
        assertEquals(listOf(2, 1, 3), sortedBubbles(listOf(bottom, left, right), true, true).map { it.id })
    }

    @Test fun japaneseStripReadsEqualTopBubblesRightToLeft() {
        val sameTopLeft = left.copy(boundingBox = RectF(10f, 10f, 50f, 40f))
        assertEquals(listOf(2, 1, 3), sortedBubbles(listOf(bottom, sameTopLeft, right), true, false).map { it.id })
    }

    @Test fun leftToRightStripReadsEqualTopBubblesLeftToRight() {
        val sameTopLeft = left.copy(boundingBox = RectF(10f, 10f, 50f, 40f))
        assertEquals(listOf(1, 2, 3), sortedBubbles(listOf(bottom, right, sameTopLeft), true, true).map { it.id })
    }

    @Test fun permutationDoesNotChangeRowsWithChainedVerticalOverlaps() {
        val a = Bubble(4, RectF(0f, 0f, 20f, 20f), 1f)
        val b = Bubble(5, RectF(30f, 12f, 50f, 32f), 1f)
        val c = Bubble(6, RectF(60f, 24f, 80f, 44f), 1f)
        val first = sortedBubbles(listOf(a, b, c), false, false).map { it.id }
        assertEquals(listOf(5, 4, 6), first)
        assertEquals(first, sortedBubbles(listOf(c, a, b), false, false).map { it.id })
    }
}
