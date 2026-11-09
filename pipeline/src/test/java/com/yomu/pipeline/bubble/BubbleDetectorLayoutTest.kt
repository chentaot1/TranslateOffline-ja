package com.yomu.pipeline.bubble

import android.graphics.Bitmap
import com.yomu.ml.LayoutDetection
import com.yomu.ml.OnnxRuntime
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BubbleDetectorLayoutTest {

    @Test
    fun detectLayout_separatesPanelsAndBubblesWithCorrectIds() {
        val mockRuntime = mock(OnnxRuntime::class.java)
        val detector = BubbleDetector(mockRuntime)
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val modelPath = "fake/path/manga_panel_detector.onnx"

        `when`(mockRuntime.loadModel(modelPath)).thenReturn(true)
        detector.loadModel(modelPath)

        val detections = listOf(
            // Panel 1: class 0
            LayoutDetection(classId = 0, confidence = 0.95f, bbox = floatArrayOf(50f, 50f, 500f, 500f)),
            // Text 1: class 1
            LayoutDetection(classId = 1, confidence = 0.90f, bbox = floatArrayOf(80f, 80f, 180f, 180f)),
            // Text 2: class 1
            LayoutDetection(classId = 1, confidence = 0.85f, bbox = floatArrayOf(250f, 250f, 350f, 350f))
        )
        `when`(mockRuntime.runLayoutInference(modelPath, bitmap, 0.25f)).thenReturn(detections)

        val layout = detector.detectLayout(bitmap, 0.25f)
        assertEquals(1, layout.panels.size)
        assertEquals(50f, layout.panels[0].left, 1e-4f)
        assertEquals(500f, layout.panels[0].right, 1e-4f)

        assertEquals(2, layout.bubbles.size)
        assertEquals(1, layout.bubbles[0].id)
        assertEquals(2, layout.bubbles[1].id)
        assertEquals(0.90f, layout.bubbles[0].confidence, 1e-4f)
        assertEquals(0.85f, layout.bubbles[1].confidence, 1e-4f)
    }

    @Test
    fun detect_delegatesToDetectLayoutBubbles() {
        val mockRuntime = mock(OnnxRuntime::class.java)
        val detector = BubbleDetector(mockRuntime)
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val modelPath = "fake/path/manga_panel_detector.onnx"

        `when`(mockRuntime.loadModel(modelPath)).thenReturn(true)
        detector.loadModel(modelPath)

        val detections = listOf(
            LayoutDetection(classId = 0, confidence = 0.95f, bbox = floatArrayOf(50f, 50f, 500f, 500f)),
            LayoutDetection(classId = 1, confidence = 0.90f, bbox = floatArrayOf(80f, 80f, 180f, 180f))
        )
        `when`(mockRuntime.runLayoutInference(modelPath, bitmap, 0.25f)).thenReturn(detections)

        val bubbles = detector.detect(bitmap, 0.25f)
        assertEquals(1, bubbles.size)
        assertEquals(1, bubbles[0].id)
        assertEquals(0.90f, bubbles[0].confidence, 1e-4f)
    }
}
