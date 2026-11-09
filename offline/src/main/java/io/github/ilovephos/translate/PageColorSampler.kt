package io.github.ilovephos.translate

import android.graphics.Bitmap
import com.yomu.pipeline.typesetting.TypesetBubble

/** Samples just inside the text box, avoiding a minority of original letters and border pixels. */
fun matchPageColors(bitmap: Bitmap, bubble: TypesetBubble): TypesetBubble {
    val box = bubble.boundingBox
    val inset = (minOf(box[2] - box[0], box[3] - box[1]) * 0.06f).coerceAtLeast(0.5f)
    val left = box[0] + inset
    val top = box[1] + inset
    val right = box[2] - inset
    val bottom = box[3] - inset
    if (right <= left || bottom <= top) return bubble
    val samples = ArrayList<Int>()
    fun sample(x: Float, y: Float) {
        samples.add(bitmap.getPixel(x.toInt().coerceIn(0, bitmap.width - 1), y.toInt().coerceIn(0, bitmap.height - 1)))
    }
    repeat(32) { index ->
        val portion = (index + 0.5f) / 32f
        sample(left + portion * (right - left), top)
        sample(left + portion * (right - left), bottom)
        sample(left, top + portion * (bottom - top))
        sample(right, top + portion * (bottom - top))
    }
    val background = PageColors.background(samples.toIntArray())
    return bubble.copy(backgroundColor = background, textColor = PageColors.text(background))
}
