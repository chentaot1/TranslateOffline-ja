package com.yomu.ml

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OnnxRuntimeTest {

    @Test
    fun bitmapPixelsToCHW_writesRThenGThenBPlanes() {
        val pixels = intArrayOf(0xFF112233.toInt(), 0xFF445566.toInt())

        val chw = bitmapPixelsToCHW(pixels, width = 2, height = 1)

        assertEquals(6, chw.size)
        assertEquals(0x11 / 255f, chw[0], 1e-6f)
        assertEquals(0x44 / 255f, chw[1], 1e-6f)
        assertEquals(0x22 / 255f, chw[2], 1e-6f)
        assertEquals(0x55 / 255f, chw[3], 1e-6f)
        assertEquals(0x33 / 255f, chw[4], 1e-6f)
        assertEquals(0x66 / 255f, chw[5], 1e-6f)
    }

    @Test
    fun parseYoloDetections_preservesXyxy_filtersAndSortsByConfidenceDesc() {
        val output = floatArrayOf(
            10f, 20f, 30f, 40f, 0.60f, 0f,
            1f, 2f, 3f, 4f, 0.95f, 1f,
            50f, 60f, 70f, 80f, 0.49f, 0f,
            100f, 110f, 120f, 130f, 0.90f, 0f
        )

        val detections = parseYoloDetections(
            output = output,
            fieldsPerDetection = 6,
            confidenceThreshold = 0.5f
        )

        assertEquals(2, detections.size)
        assertEquals(0.90f, detections[0].confidence, 1e-6f)
        assertArrayEquals(floatArrayOf(100f, 110f, 120f, 130f), detections[0].bbox, 1e-6f)
        assertEquals(0.60f, detections[1].confidence, 1e-6f)
        assertArrayEquals(floatArrayOf(10f, 20f, 30f, 40f), detections[1].bbox, 1e-6f)
        assertTrue(detections.all { it.label == "bubble" })
    }

    @Test
    fun parseYoloDetections_keepsCandidatesAtPointTwentyFiveThreshold() {
        val output = floatArrayOf(
            0f, 0f, 10f, 10f, 0.24f, 0f,
            10f, 10f, 20f, 20f, 0.25f, 0f,
            20f, 20f, 30f, 30f, 0.30f, 0f
        )

        val detections = parseYoloDetections(
            output = output,
            fieldsPerDetection = 6,
            confidenceThreshold = 0.25f
        )

        assertEquals(2, detections.size)
        assertEquals(0.30f, detections[0].confidence, 1e-6f)
        assertEquals(0.25f, detections[1].confidence, 1e-6f)
    }

    @Test
    fun parseYoloDetections_keepsCandidatesBelowTheDefaultThreshold() {
        val output = floatArrayOf(
            0f, 0f, 10f, 10f, 0.18f, 0f
        )

        val lowered = parseYoloDetections(
            output = output,
            fieldsPerDetection = 6,
            confidenceThreshold = 0.10f
        )
        val atDefault = parseYoloDetections(
            output = output,
            fieldsPerDetection = 6,
            confidenceThreshold = 0.25f
        )

        assertEquals(1, lowered.size)
        assertEquals(0.18f, lowered[0].confidence, 1e-6f)
        assertTrue(atDefault.isEmpty())
    }

    @Test
    fun parseYoloDetections_returnsEmptyForInvalidFieldLayout() {
        val invalidFields = parseYoloDetections(
            output = floatArrayOf(1f, 2f, 3f, 4f, 0.5f, 0f),
            fieldsPerDetection = 0,
            confidenceThreshold = 0.5f
        )

        val nonDivisibleOutput = parseYoloDetections(
            output = floatArrayOf(1f, 2f, 3f, 4f, 0.5f),
            fieldsPerDetection = 6,
            confidenceThreshold = 0.5f
        )

        assertTrue(invalidFields.isEmpty())
        assertTrue(nonDivisibleOutput.isEmpty())
    }
}
