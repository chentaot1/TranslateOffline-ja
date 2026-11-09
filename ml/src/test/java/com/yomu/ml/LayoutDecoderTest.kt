package com.yomu.ml

import org.junit.Assert.assertEquals
import org.junit.Test

class LayoutDecoderTest {

    @Test
    fun parseDenseYoloDetections_decodesPanelsAndTextWithNms() {
        // Synthetic [1, 6, N] tensor: 2 panels and 2 text boxes with overlap
        val numAnchors = 4
        val output = floatArrayOf(
            // Panel 1: xc=100, yc=100, w=50, h=50, score_panel=0.9, score_text=0.1
            100f, 100f, 50f, 50f, 0.9f, 0.1f,
            // Panel 2 (duplicate overlap): xc=102, yc=102, w=50, h=50, score_panel=0.8, score_text=0.1
            102f, 102f, 50f, 50f, 0.8f, 0.1f,
            // Text 1: xc=200, yc=200, w=30, h=30, score_panel=0.1, score_text=0.95
            200f, 200f, 30f, 30f, 0.1f, 0.95f,
            // Text 2 (distinct): xc=400, yc=400, w=30, h=30, score_panel=0.05, score_text=0.85
            400f, 400f, 30f, 30f, 0.05f, 0.85f
        )
        val detections = parseDenseYoloLayoutDetections(
            output = output,
            numAnchors = numAnchors,
            confThreshold = 0.25f,
            iouThreshold = 0.45f
        )
        assertEquals(3, detections.size) // Panel 2 suppressed by Panel 1
        assertEquals(1, detections.count { it.classId == 0 })
        assertEquals(2, detections.count { it.classId == 1 })
    }

    @Test
    fun parseDenseYoloDetections_decodesEnd2EndPanelsAndTextWithNms() {
        val numAnchors = 3
        val output = floatArrayOf(
            // Panel 1: x1=50, y1=50, x2=150, y2=150, conf=0.95, classId=0.0
            50f, 50f, 150f, 150f, 0.95f, 0.0f,
            // Text 1: x1=60, y1=60, x2=90, y2=90, conf=0.90, classId=1.0
            60f, 60f, 90f, 90f, 0.90f, 1.0f,
            // Low conf panel: conf=0.15 (below 0.25)
            10f, 10f, 30f, 30f, 0.15f, 0.0f
        )
        val detections = parseDenseYoloLayoutDetections(
            output = output,
            numAnchors = numAnchors,
            confThreshold = 0.25f,
            iouThreshold = 0.45f
        )
        assertEquals(2, detections.size)
        assertEquals(1, detections.count { it.classId == 0 })
        assertEquals(1, detections.count { it.classId == 1 })
    }

    @Test
    fun parseDenseYoloDetections_ignoresTrailingEmptyAnchorsInEnd2End() {
        val numAnchors = 4
        val output = floatArrayOf(
            // Panel 1: x1=50, y1=50, x2=150, y2=150, conf=0.95, classId=0.0
            50f, 50f, 150f, 150f, 0.95f, 0.0f,
            // Text 1: x1=60, y1=60, x2=90, y2=90, conf=0.90, classId=1.0
            60f, 60f, 90f, 90f, 0.90f, 1.0f,
            // Trailing anchor with zero conf and non-standard float classId
            0f, 0f, 0f, 0f, 0.0f, 0.5f,
            // Trailing anchor with x2 < x1 and negative coords
            10f, 10f, 5f, 5f, 0.01f, -1.0f
        )
        val detections = parseDenseYoloLayoutDetections(
            output = output,
            numAnchors = numAnchors,
            confThreshold = 0.25f,
            iouThreshold = 0.45f
        )
        assertEquals(2, detections.size)
        assertEquals(1, detections.count { it.classId == 0 })
        assertEquals(1, detections.count { it.classId == 1 })
        assertEquals(50f, detections.first { it.classId == 0 }.bbox[0], 1e-4f)
        assertEquals(150f, detections.first { it.classId == 0 }.bbox[2], 1e-4f)
    }
}
