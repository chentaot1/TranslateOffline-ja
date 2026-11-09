package com.yomu.pipeline.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChineseTextDetectionTest {
    @Test fun separateLinesRemainSeparateAndInReadingOrder() {
        val map = FloatArray(60 * 40)
        for (y in 5..10) for (x in 5..40) map[y * 60 + x] = 0.95f
        for (y in 25..30) for (x in 5..45) map[y * 60 + x] = 0.95f
        val lines = detectChineseLines(map, 60, 40)
        assertEquals(2, lines.size)
        assertTrue(lines[0].centerY < lines[1].centerY)
    }

    @Test fun weakRegionsAndTinyNoiseAreDiscarded() {
        val map = FloatArray(60 * 40)
        for (y in 5..10) for (x in 5..40) map[y * 60 + x] = 0.4f
        map[35 * 60 + 50] = 1f
        assertTrue(detectChineseLines(map, 60, 40).isEmpty())
    }

    @Test fun blankPageHasNoLines() {
        assertTrue(detectChineseLines(FloatArray(32 * 32), 32, 32).isEmpty())
    }
}
