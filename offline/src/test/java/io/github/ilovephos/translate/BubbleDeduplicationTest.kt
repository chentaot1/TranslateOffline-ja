package io.github.ilovephos.translate

import android.graphics.RectF
import com.yomu.pipeline.bubble.Bubble
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BubbleDeduplicationTest {

    @Test
    fun speechBubble_suppresses_overlappingCaption() {
        val firstCaption = 10
        val speechBubble = Bubble(id = 1, boundingBox = RectF(100f, 100f, 300f, 300f), confidence = 0.85f)
        // Caption with 70% overlap inside the speech bubble
        val caption = Bubble(id = 11, boundingBox = RectF(120f, 120f, 280f, 280f), confidence = 1.0f)

        val result = ReadingSession.deduplicateBubbles(listOf(speechBubble, caption), firstCaption)

        assertEquals(1, result.size)
        assertEquals(1, result[0].id)
    }

    @Test
    fun containment_suppressesNestedBox() {
        val firstCaption = 10
        val parentBubble = Bubble(id = 2, boundingBox = RectF(50f, 50f, 250f, 250f), confidence = 0.9f)
        // Sub-box completely inside parent
        val childBubble = Bubble(id = 3, boundingBox = RectF(70f, 70f, 150f, 150f), confidence = 0.7f)

        val result = ReadingSession.deduplicateBubbles(listOf(parentBubble, childBubble), firstCaption)

        assertEquals(1, result.size)
        assertEquals(2, result[0].id)
    }

    @Test
    fun highIou_suppressesDuplicateBubble() {
        val firstCaption = 10
        val bubbleA = Bubble(id = 4, boundingBox = RectF(100f, 100f, 200f, 200f), confidence = 0.95f)
        val bubbleB = Bubble(id = 5, boundingBox = RectF(105f, 105f, 205f, 205f), confidence = 0.75f)

        val result = ReadingSession.deduplicateBubbles(listOf(bubbleA, bubbleB), firstCaption)

        assertEquals(1, result.size)
        assertEquals(4, result[0].id)
    }

    @Test
    fun distinctAdjacentBubbles_areBothKept() {
        val firstCaption = 10
        val bubbleTop = Bubble(id = 1, boundingBox = RectF(100f, 100f, 200f, 200f), confidence = 0.9f)
        val bubbleBottom = Bubble(id = 2, boundingBox = RectF(100f, 250f, 200f, 350f), confidence = 0.9f)

        val result = ReadingSession.deduplicateBubbles(listOf(bubbleTop, bubbleBottom), firstCaption)

        assertEquals(2, result.size)
        assertTrue(result.any { it.id == 1 })
        assertTrue(result.any { it.id == 2 })
    }
}
