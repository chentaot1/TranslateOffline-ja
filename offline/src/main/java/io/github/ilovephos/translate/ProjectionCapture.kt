package io.github.ilovephos.translate

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import kotlinx.coroutines.delay
import java.io.Closeable

/** A single approved capture session, resized without reusing its consent token. */
class ProjectionCapture(
    private val context: Context,
    data: Intent,
    private val changed: () -> Unit,
    private val ended: () -> Unit
) : Closeable {
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var projection: MediaProjection? = null
    private var closed = false

    init {
        try {
        val manager = context.getSystemService(MediaProjectionManager::class.java)
        val active = requireNotNull(manager.getMediaProjection(android.app.Activity.RESULT_OK, data)) {
            "Screen capture approval is no longer available"
        }
        projection = active
        active.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                if (!closed) { close(); ended() }
            }

            override fun onCapturedContentResize(width: Int, height: Int) {
                if (closed || width <= 0 || height <= 0) return
                if (reader?.width == width && reader?.height == height) return
                resize(width, height)
                changed()
            }
        }, Handler(Looper.getMainLooper()))
        val window = context.getSystemService(WindowManager::class.java)
        val metrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        window.defaultDisplay.getRealMetrics(metrics)
        val images = ImageReader.newInstance(metrics.widthPixels, metrics.heightPixels, PixelFormat.RGBA_8888, 3)
        reader = images
        display = active.createVirtualDisplay(
            "Translate Offline", metrics.widthPixels, metrics.heightPixels, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, images.surface, null,
            Handler(Looper.getMainLooper())
        )
        } catch (error: Exception) {
            close()
            throw error
        }
    }

    private fun resize(width: Int, height: Int) {
        val old = reader
        val replacement = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
        display?.resize(width, height, context.resources.displayMetrics.densityDpi)
        display?.surface = replacement.surface
        reader = replacement
        old?.close()
    }

    /** Drains frames queued before a UI transition; no cached image can satisfy a fresh read. */
    fun discardPending() { reader?.acquireLatestImage()?.close() }

    /** Returns only a newly delivered image, or null when the display has not changed. */
    suspend fun frame(timeoutMs: Long = 120): Bitmap? {
        repeat((timeoutMs / 20).toInt().coerceAtLeast(1)) {
            val image = reader?.acquireLatestImage()
            if (image == null) {
                delay(20)
                return@repeat
            }
            try {
                val plane = image.planes[0]
                val paddedWidth = plane.rowStride / plane.pixelStride
                val padded = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
                padded.copyPixelsFromBuffer(plane.buffer)
                val result = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
                if (result !== padded) padded.recycle()
                return result
            } finally {
                image.close()
            }
        }
        return null
    }

    override fun close() {
        if (closed) return
        closed = true
        display?.release()
        display = null
        reader?.close()
        reader = null
        val previous = projection
        projection = null
        previous?.stop()
    }
}
