package com.yomu.pipeline.ocr

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DbNetImagesTest {
    @Test fun transparentPixelsBecomeWhiteUnderDetectionNormalization() {
        val bitmap = Bitmap.createBitmap(736, 736, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.TRANSPARENT)
        try {
            val tensor = dbNetImageTensor(bitmap)
            assertEquals((1f - 0.485f) / 0.229f, tensor.values[0], 0.0001f)
            assertFalse(bitmap.isRecycled)
        } finally {
            bitmap.recycle()
        }
    }

    @Test fun detectionUsesPinnedBgrNormalizationAndMultipleOf32() {
        val bitmap = Bitmap.createBitmap(736, 736, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.RED)
        try {
            val tensor = dbNetImageTensor(bitmap)
            assertArrayEquals(longArrayOf(1, 3, 736, 736), tensor.shape)
            assertEquals(-0.485f / 0.229f, tensor.values[0], 0.0001f)
            assertEquals((1f - 0.406f) / 0.225f, tensor.values[2 * 736 * 736], 0.0001f)
        } finally {
            bitmap.recycle()
        }
    }
}
