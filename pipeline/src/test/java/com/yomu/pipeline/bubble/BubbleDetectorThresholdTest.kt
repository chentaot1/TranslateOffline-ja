package com.yomu.pipeline.bubble

import com.yomu.ml.OnnxRuntime
import org.junit.Assert.assertEquals
import org.junit.Test

class BubbleDetectorThresholdTest {

    private val strong = detection(0.5f, floatArrayOf(0f, 0f, 100f, 100f))
    private val weak = detection(0.3f, floatArrayOf(300f, 300f, 400f, 400f))
    private val faint = detection(0.2f, floatArrayOf(600f, 600f, 700f, 700f))

    @Test
    fun `the default threshold is 0_25`() {
        assertEquals(0.25f, BubbleDetector.DEFAULT_CONFIDENCE_THRESHOLD)
    }

    @Test
    fun `the default threshold keeps detections at or above 0_25`() {
        val kept = keptDetections(listOf(faint, weak, strong), BubbleDetector.DEFAULT_CONFIDENCE_THRESHOLD)

        assertEquals(listOf(strong, weak), kept)
    }

    @Test
    fun `a raised threshold drops detections below it`() {
        val kept = keptDetections(listOf(faint, weak, strong), 0.4f)

        assertEquals(listOf(strong), kept)
    }

    private fun detection(confidence: Float, bbox: FloatArray) =
        OnnxRuntime.Detection(label = "bubble", confidence = confidence, bbox = bbox)
}
