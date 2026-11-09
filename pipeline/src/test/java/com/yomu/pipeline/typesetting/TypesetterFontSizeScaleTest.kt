package com.yomu.pipeline.typesetting

import com.yomu.pipeline.translation.TranslatedBubble
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TypesetterFontSizeScaleTest {

    private fun bounds(w: Float, h: Float) = floatArrayOf(0f, 0f, w, h)

    private fun bubble(id: Int, text: String) = TranslatedBubble(
        bubbleId = id,
        originalText = "",
        translatedText = text,
        answered = true
    )

    @Test
    fun `scale 1_0 gives default font size`() {
        val default = Typesetter().typeset(
            listOf(bubble(1, "Hello world this is a test")),
            mapOf(1 to bounds(300f, 200f))
        ).first().fontSize

        val scaled = Typesetter(fontSizeScale = 1.0f).typeset(
            listOf(bubble(1, "Hello world this is a test")),
            mapOf(1 to bounds(300f, 200f))
        ).first().fontSize

        assertEquals(default, scaled, 0.01f)
    }

    @Test
    fun `scale 2_0 gives larger font size`() {
        val default = Typesetter(fontSizeScale = 1.0f).typeset(
            listOf(bubble(1, "Hello world")),
            mapOf(1 to bounds(300f, 200f))
        ).first().fontSize

        val scaled = Typesetter(fontSizeScale = 2.0f).typeset(
            listOf(bubble(1, "Hello world")),
            mapOf(1 to bounds(300f, 200f))
        ).first().fontSize

        assertTrue("Scaled font size should be larger", scaled > default)
    }

    @Test
    fun `scale 0_5 gives smaller font size`() {
        val default = Typesetter(fontSizeScale = 1.0f).typeset(
            listOf(bubble(1, "Hello world")),
            mapOf(1 to bounds(300f, 200f))
        ).first().fontSize

        val scaled = Typesetter(fontSizeScale = 0.5f).typeset(
            listOf(bubble(1, "Hello world")),
            mapOf(1 to bounds(300f, 200f))
        ).first().fontSize

        assertTrue("Scaled font size should be smaller", scaled < default)
    }

    @Test
    fun `scale is clamped to maximum`() {
        val scaled = Typesetter(fontSizeScale = 100.0f).typeset(
            listOf(bubble(1, "Hello world")),
            mapOf(1 to bounds(500f, 500f))
        ).first().fontSize

        assertTrue("Scaled font size should not exceed max clamp", scaled <= 72f)
    }

    @Test
    fun `scale is clamped to minimum`() {
        val scaled = Typesetter(fontSizeScale = 0.01f).typeset(
            listOf(bubble(1, "Hello world")),
            mapOf(1 to bounds(500f, 500f))
        ).first().fontSize

        assertTrue("Scaled font size should not be below min clamp", scaled >= 10f)
    }
}
