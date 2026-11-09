package com.yomu.pipeline.ocr

import android.graphics.Bitmap
import android.graphics.RectF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ChineseDetectionBoundsTest {
    @Test fun detectorCoordinatesScaleAndClampToOriginalImage() {
        val line = ChineseLine(listOf(TextPoint(-5f, 10f), TextPoint(110f, 10f),
            TextPoint(110f, 60f), TextPoint(-5f, 60f)), 115f, 50f)
        assertEquals(RectF(0f, 20f, 200f, 100f), chineseLineBounds(line, 100, 50, 200, 100))
    }

    @Test fun entirelyOffPageLinesAreDiscarded() {
        val line = ChineseLine(listOf(TextPoint(-30f, 10f), TextPoint(-10f, 10f),
            TextPoint(-10f, 30f), TextPoint(-30f, 30f)), 20f, 20f)
        assertNull(chineseLineBounds(line, 100, 50, 200, 100))
    }

    @Test fun missingDetectorFailsWithoutNativeInitialization() {
        val engine = ChineseOcrEngine()
        assertFalse(engine.loadDetector("missing-chinese-detector.onnx"))
        engine.release()
    }

    @Test fun unloadedDetectionKeepsCallerBitmapAlive() {
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val engine = ChineseOcrEngine()
        try {
            assertEquals(emptyList<RectF>(), engine.detectRegions(bitmap))
            assertFalse(bitmap.isRecycled)
        } finally {
            engine.release()
            bitmap.recycle()
        }
    }
}
