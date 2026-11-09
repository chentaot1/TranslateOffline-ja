package com.yomu.pipeline.ocr

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ChineseOcrImagesTest {
    @Test fun recognitionUsesRgbAndPreservesAspectRatioAndCallerBitmap() {
        val bitmap = Bitmap.createBitmap(96, 48, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.RED)
        try {
            val tensor = chineseImageTensor(bitmap, true)
            assertArrayEquals(longArrayOf(1, 3, 48, 96), tensor.shape)
            assertEquals(1f, tensor.values[0], 0f)
            assertEquals(-1f, tensor.values[48 * 96], 0f)
            assertEquals(-1f, tensor.values[2 * 48 * 96], 0f)
            assertFalse(bitmap.isRecycled)
        } finally { bitmap.recycle() }
    }

    @Test fun transparentPixelsBecomeWhite() {
        val bitmap = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.TRANSPARENT)
        try {
            val tensor = chineseImageTensor(bitmap, true)
            assertEquals(1f, tensor.values[0], 0f)
        } finally { bitmap.recycle() }
    }

    @Test fun detectionUsesPinnedBgrNormalizationAndMultipleOf32() {
        val bitmap = Bitmap.createBitmap(736, 736, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.RED)
        try {
            val tensor = chineseImageTensor(bitmap, false)
            assertArrayEquals(longArrayOf(1, 3, 736, 736), tensor.shape)
            assertEquals(-0.485f / 0.229f, tensor.values[0], 0.0001f)
            assertEquals((1f - 0.406f) / 0.225f, tensor.values[2 * 736 * 736], 0.0001f)
        } finally { bitmap.recycle() }
    }

    @Test fun cropPreservesBothLineGeometryAndCallerBitmap() {
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        val line = ChineseLine(listOf(TextPoint(10f, 10f), TextPoint(90f, 10f),
            TextPoint(90f, 30f), TextPoint(10f, 30f)), 80f, 20f)
        try {
            val crop = chineseLineCrop(bitmap, line, 100, 100)
            try {
                assertEquals(80, crop.width)
                assertEquals(20, crop.height)
                assertFalse(bitmap.isRecycled)
            } finally { crop.recycle() }
        } finally { bitmap.recycle() }
    }

    @Test fun rotatedLineTransformMapsAllFourCornersToTheCrop() {
        val points = floatArrayOf(80f, 50f, 150f, 120f, 120f, 150f, 50f, 80f)
        val mapped = points.copyOf()
        chineseLineTransform(points, 99, 42).mapPoints(mapped)
        assertArrayEquals(floatArrayOf(0f, 0f, 99f, 0f, 99f, 42f, 0f, 42f), mapped, 0.001f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun collinearLineCornersAreRejected() {
        chineseLineTransform(floatArrayOf(0f, 0f, 1f, 1f, 2f, 2f, 3f, 3f), 10, 10)
    }

    @Test fun missingModelsFailCleanlyAndReleaseIsIdempotent() {
        val engine = ChineseOcrEngine()
        assertFalse(engine.loadModel("missing-det.onnx", "missing-rec.onnx", "missing-chars.json"))
        val bitmap = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
        try { assertNull(engine.extractText(bitmap)) } finally { bitmap.recycle() }
        engine.release()
        engine.release()
    }
}
