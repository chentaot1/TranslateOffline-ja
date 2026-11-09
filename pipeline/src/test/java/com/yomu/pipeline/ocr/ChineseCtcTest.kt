package com.yomu.pipeline.ocr

import org.junit.Assert.assertEquals
import org.junit.Test

class ChineseCtcTest {
    @Test fun blankSeparatesRepeatedCharacters() {
        val decoded = decodeChineseCtc(probabilities(1, 1, 0, 1, 2, 2), 3, listOf("人", "口"))
        assertEquals("人人口", decoded.text)
        assertEquals(0.9f, decoded.confidence, 0.0001f)
        assertEquals(3, decoded.characterCount)
    }

    @Test fun allBlankProducesNoTextOrConfidence() {
        val decoded = decodeChineseCtc(probabilities(0, 0), 3, listOf("人", "口"))
        assertEquals("", decoded.text)
        assertEquals(0f, decoded.confidence, 0f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun mismatchedDictionaryIsRejected() {
        decodeChineseCtc(probabilities(1), 3, listOf("人"))
    }

    @Test fun confidenceCountsOnlyEmittedCharacters() {
        val decoded = decodeChineseCtc(floatArrayOf(0.1f, 0.9f, 0.9f, 0.1f, 0.3f, 0.7f), 2, listOf("好"))
        assertEquals("好好", decoded.text)
        assertEquals(0.8f, decoded.confidence, 0.0001f)
    }

    private fun probabilities(vararg ids: Int): FloatArray =
        ids.flatMap { id -> List(3) { if (it == id) 0.9f else 0.05f } }.toFloatArray()
}
