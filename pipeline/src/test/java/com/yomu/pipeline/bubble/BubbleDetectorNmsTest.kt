package com.yomu.pipeline.bubble

import com.yomu.ml.OnnxRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BubbleDetectorNmsTest {

    @Test
    fun nonMaxSuppressDetections_removesLowerConfidenceOverlappingDetections() {
        val high = detection(0.9f, floatArrayOf(0f, 0f, 100f, 100f))
        val lowOverlap = detection(0.6f, floatArrayOf(10f, 10f, 110f, 110f))
        val separate = detection(0.7f, floatArrayOf(300f, 300f, 360f, 360f))

        val kept = nonMaxSuppressDetections(
            detections = listOf(lowOverlap, separate, high),
            iouThreshold = 0.45f
        )

        assertEquals(2, kept.size)
        assertTrue(kept.contains(high))
        assertTrue(kept.contains(separate))
    }

    @Test
    fun nonMaxSuppressDetections_keepsBoxesWhenOverlapUnderThreshold() {
        val first = detection(0.9f, floatArrayOf(0f, 0f, 100f, 100f))
        val second = detection(0.8f, floatArrayOf(40f, 40f, 140f, 140f))

        val kept = nonMaxSuppressDetections(
            detections = listOf(first, second),
            iouThreshold = 0.45f
        )

        assertEquals(2, kept.size)
    }

    @Test
    fun nonMaxSuppressDetections_dropsBoxNearlyContainedInAnother() {
        // #88 bug B: the small box sits fully inside the big one but their IoU is ~0.04, so an
        // IoU-only pass keeps both and their texts stack. Containment suppression drops the small one.
        val big = detection(0.9f, floatArrayOf(0f, 0f, 200f, 200f))
        val nested = detection(0.6f, floatArrayOf(50f, 50f, 90f, 90f))

        val kept = nonMaxSuppressDetections(
            detections = listOf(nested, big),
            iouThreshold = 0.45f,
            containmentThreshold = 0.97f
        )

        assertEquals(1, kept.size)
        assertTrue(kept.contains(big))
    }

    @Test
    fun nonMaxSuppressDetections_prefersLowerConfidenceBoxContainingTheKeptOne() {
        // #370: the model boxes なにあ然 only inside a box merged with the ヘリウム balloon below it,
        // and boxes ヘリウム alone with more confidence. Keeping the merged box keeps both lines.
        val heliumAlone = detection(0.582f, floatArrayOf(135f, 937f, 256f, 1098f))
        val merged = detection(0.216f, floatArrayOf(132f, 677f, 255f, 1100f))

        val kept = nonMaxSuppressDetections(
            detections = listOf(heliumAlone, merged),
            iouThreshold = 0.80f,
            containmentThreshold = 0.90f
        )

        assertEquals(listOf(merged), kept)
    }

    @Test
    fun nonMaxSuppressDetections_keepsSeparateBubblesOverTheBoxMergingThem() {
        val upper = detection(0.6f, floatArrayOf(0f, 0f, 100f, 100f))
        val lower = detection(0.2f, floatArrayOf(0f, 150f, 100f, 250f))
        val merged = detection(0.3f, floatArrayOf(0f, 0f, 100f, 250f))

        val kept = nonMaxSuppressDetections(
            detections = listOf(upper, merged, lower),
            iouThreshold = 0.80f,
            containmentThreshold = 0.90f
        )

        assertEquals(listOf(upper, lower), kept)
    }

    @Test
    fun nonMaxSuppressDetections_keepsNestedBoxWhenContainmentDisabled() {
        // Default threshold disables containment, leaving the prior IoU-only behaviour intact.
        val big = detection(0.9f, floatArrayOf(0f, 0f, 200f, 200f))
        val nested = detection(0.6f, floatArrayOf(50f, 50f, 90f, 90f))

        val kept = nonMaxSuppressDetections(
            detections = listOf(nested, big),
            iouThreshold = 0.45f
        )

        assertEquals(2, kept.size)
    }

    @Test
    fun detectionContainedFraction_isFullWhenInnerInsideOuter() {
        val outer = detection(0.9f, floatArrayOf(0f, 0f, 200f, 200f))
        val inner = detection(0.6f, floatArrayOf(50f, 50f, 90f, 90f))

        assertEquals(1.0f, detectionContainedFraction(inner, outer), 1e-4f)
        // Only 1600/40000 of the big box lies inside the small one — not a containment either way.
        assertEquals(0.04f, detectionContainedFraction(outer, inner), 1e-4f)
    }

    @Test
    fun detectionIou_calculatesExpectedValue() {
        val first = detection(0.9f, floatArrayOf(0f, 0f, 100f, 100f))
        val second = detection(0.8f, floatArrayOf(10f, 10f, 110f, 110f))

        val iou = detectionIou(first, second)

        assertEquals(0.6806723f, iou, 1e-4f)
    }

    private fun detection(confidence: Float, bbox: FloatArray): OnnxRuntime.Detection {
        return OnnxRuntime.Detection(
            label = "bubble",
            confidence = confidence,
            bbox = bbox
        )
    }
}
