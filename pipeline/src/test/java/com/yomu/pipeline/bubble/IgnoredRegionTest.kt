package com.yomu.pipeline.bubble

import android.graphics.RectF
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class IgnoredRegionTest {

    private val statusBar = RectF(0f, 0f, 1280f, 140f)
    private val floatingButton = RectF(150f, 380f, 270f, 500f)

    @Test
    fun `a bubble centered in the status bar is dropped even when its box spills below it`() {
        val clock = bubble(1, 194f, 42f, 410f, 160f)
        val speech = bubble(2, 300f, 600f, 500f, 900f)

        assertEquals(listOf(speech), listOf(clock, speech).centeredOutside(listOf(statusBar)))
    }

    @Test
    fun `a bubble on the floating button is dropped`() {
        val button = bubble(1, 160f, 390f, 260f, 490f)

        assertEquals(emptyList<Bubble>(), listOf(button).centeredOutside(listOf(statusBar, floatingButton)))
    }

    @Test
    fun `a bubble the floating button only covers in part is kept`() {
        val speech = bubble(1, 200f, 420f, 600f, 900f)

        assertEquals(listOf(speech), listOf(speech).centeredOutside(listOf(floatingButton)))
    }

    @Test
    fun `no regions keep every bubble`() {
        val bubbles = listOf(bubble(1, 0f, 0f, 100f, 100f), bubble(2, 300f, 600f, 500f, 900f))

        assertEquals(bubbles, bubbles.centeredOutside(emptyList()))
    }

    private fun bubble(id: Int, left: Float, top: Float, right: Float, bottom: Float) =
        Bubble(id = id, boundingBox = RectF(left, top, right, bottom), confidence = 0.9f)
}
