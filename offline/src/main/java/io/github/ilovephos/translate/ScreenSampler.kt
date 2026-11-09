package io.github.ilovephos.translate

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.view.WindowInsets
import android.view.WindowManager

/** Samples captures in screen coordinates, excluding the real floating window and Android's changing system bars. */
class ScreenSampler(context: Context) {
    private val window = context.getSystemService(WindowManager::class.java)

    fun sample(bitmap: Bitmap, controls: RectF): ScreenSample {
        val small = Bitmap.createScaledBitmap(bitmap, ScreenSample.COLUMNS, ScreenSample.ROWS, true)
        val pixels = IntArray(ScreenSample.COLUMNS * ScreenSample.ROWS)
        try {
            small.getPixels(pixels, 0, ScreenSample.COLUMNS, 0, 0, ScreenSample.COLUMNS, ScreenSample.ROWS)
        } finally {
            if (small !== bitmap) small.recycle()
        }
        val metrics = window.maximumWindowMetrics
        val bars = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        val width = bitmap.width.toFloat()
        val height = bitmap.height.toFloat()
        val scaleX = width / metrics.bounds.width()
        val scaleY = height / metrics.bounds.height()
        val ignored = listOf(
            SampleRegion(controls.left, controls.top, controls.right, controls.bottom),
            SampleRegion(0f, 0f, width, bars.top * scaleY),
            SampleRegion(0f, height - bars.bottom * scaleY, width, height),
            SampleRegion(0f, 0f, bars.left * scaleX, height),
            SampleRegion(width - bars.right * scaleX, 0f, width, height)
        )
        return ScreenSample.fromPixels(bitmap.width, bitmap.height, pixels, ignored = ignored)
    }
}
