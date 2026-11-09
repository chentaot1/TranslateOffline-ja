package com.yomu.pipeline.layout

import android.graphics.RectF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SupplementalTextRegionsTest {
    private val bubble = RectF(100f, 100f, 300f, 300f)

    @Test fun textAlreadyInsideBubbleIsSuppressed() {
        assertTrue(supplementalTextRegions(listOf(RectF(120f, 140f, 250f, 170f)), listOf(bubble), false).isEmpty())
    }

    @Test fun freeCaptionOutsideBubbleIsRetained() {
        val caption = RectF(20f, 20f, 160f, 40f)
        assertEquals(listOf(caption), supplementalTextRegions(listOf(caption), listOf(bubble), true))
    }

    @Test fun significantPartialBubbleOverlapIsSuppressed() {
        assertTrue(supplementalTextRegions(listOf(RectF(80f, 120f, 120f, 160f)), listOf(bubble), false).isEmpty())
    }

    @Test fun tinyEdgeOverlapDoesNotHideAnOutsideCaption() {
        val caption = RectF(20f, 90f, 200f, 101f)
        assertEquals(listOf(caption), supplementalTextRegions(listOf(caption), listOf(bubble), true))
    }

    @Test fun duplicateAndContainedDetectionsProduceOneCaption() {
        val caption = RectF(20f, 20f, 180f, 45f)
        assertEquals(listOf(caption), supplementalTextRegions(listOf(RectF(21f, 21f, 179f, 44f), caption,
            RectF(50f, 25f, 100f, 40f)), emptyList(), true))
    }

    @Test fun equalAreaDuplicatesHaveTheSameWinnerForEveryInputOrder() {
        val wide = RectF(10f, 10f, 110f, 30f)
        val tall = RectF(10f, 10f, 90f, 35f)
        assertEquals(listOf(wide), supplementalTextRegions(listOf(tall, wide), emptyList(), true))
        assertEquals(listOf(wide), supplementalTextRegions(listOf(wide, tall), emptyList(), true))
    }

    @Test fun alignedAdjacentHorizontalLinesMergeIntoParagraph() {
        val lines = listOf(RectF(20f, 20f, 180f, 40f), RectF(20f, 46f, 170f, 66f))
        assertEquals(listOf(RectF(20f, 20f, 180f, 66f)), supplementalTextRegions(lines, emptyList(), true))
    }

    @Test fun adjacentVerticalColumnsMergeWhenTheirTopsAlign() {
        val lines = listOf(RectF(20f, 20f, 40f, 180f), RectF(46f, 20f, 66f, 170f))
        assertEquals(listOf(RectF(20f, 20f, 66f, 180f)), supplementalTextRegions(lines, emptyList(), false))
    }

    @Test fun neighboringCaptionColumnsRemainSeparate() {
        val lines = listOf(RectF(20f, 20f, 150f, 40f), RectF(180f, 20f, 310f, 40f))
        assertEquals(lines, supplementalTextRegions(lines, emptyList(), true))
    }

    @Test fun distantLinesAreNotMerged() {
        val lines = listOf(RectF(20f, 20f, 180f, 40f), RectF(20f, 90f, 180f, 110f))
        assertEquals(lines, supplementalTextRegions(lines, emptyList(), true))
    }

    @Test fun diagonalChainCannotGrowIntoOneParagraph() {
        val lines = listOf(RectF(0f, 0f, 100f, 20f), RectF(10f, 25f, 110f, 45f), RectF(20f, 50f, 120f, 70f))
        val result = supplementalTextRegions(lines, emptyList(), true)
        assertEquals(2, result.size)
        assertEquals(result, supplementalTextRegions(lines.reversed(), emptyList(), true))
    }

    @Test fun paragraphUnionCannotCoverAnExistingBubble() {
        val lines = listOf(RectF(20f, 20f, 180f, 40f), RectF(20f, 52f, 180f, 72f))
        assertEquals(lines, supplementalTextRegions(lines, listOf(RectF(80f, 42f, 100f, 50f)), true))
    }

    @Test fun squareArtAndTextWithDifferentThicknessDoNotMerge() {
        val lines = listOf(RectF(20f, 20f, 40f, 40f), RectF(20f, 44f, 180f, 64f))
        assertEquals(lines, supplementalTextRegions(lines, emptyList(), true))
    }

    @Test fun outputDirectionIsExplicitAndInputsAreNotMutated() {
        val left = RectF(10f, 10f, 100f, 30f)
        val right = RectF(200f, 10f, 290f, 30f)
        val regions = supplementalTextRegions(listOf(left, right), emptyList(), false)
        assertEquals(listOf(right, left), regions)
        assertNotSame(left, regions[1])
        regions[1].offset(4f, 4f)
        assertEquals(RectF(10f, 10f, 100f, 30f), left)
    }

    @Test fun invalidOrNonFiniteRegionsAreDiscarded() {
        assertTrue(supplementalTextRegions(listOf(RectF(0f, 0f, 0f, 2f),
            RectF(Float.NaN, 0f, 1f, 2f)), emptyList(), true).isEmpty())
    }
}
