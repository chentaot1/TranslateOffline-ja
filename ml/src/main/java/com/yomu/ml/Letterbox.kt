package com.yomu.ml

import kotlin.math.roundToInt

/**
 * YOLO was trained on aspect-kept, padded pages. Stretching a 1280×2856 screen into a 1280×1280
 * square squashed text 2.2× and hid whole bubbles from the model (#369). The padding sits at the
 * bottom and right, so [toSource] needs no offset.
 */
internal class Letterbox(width: Int, height: Int, inputSize: Int, val fixedSquare: Boolean = false) {
    private val scale = inputSize.toFloat() / maxOf(width, height)
    val scaledWidth = (width * scale).roundToInt()
    val scaledHeight = (height * scale).roundToInt()
    val inputWidth = if (fixedSquare) inputSize else roundUpToStride(scaledWidth)
    val inputHeight = if (fixedSquare) inputSize else roundUpToStride(scaledHeight)
    val padX = if (fixedSquare) (inputWidth - scaledWidth) / 2 else 0
    val padY = if (fixedSquare) (inputHeight - scaledHeight) / 2 else 0

    fun toSource(bbox: FloatArray): FloatArray = floatArrayOf(
        (bbox[0] - padX) / scale,
        (bbox[1] - padY) / scale,
        (bbox[2] - padX) / scale,
        (bbox[3] - padY) / scale
    )

    private fun roundUpToStride(size: Int): Int = (size + STRIDE - 1) / STRIDE * STRIDE

    companion object {
        private const val STRIDE = 32
        const val PAD_COLOR = 0xFF727272.toInt()
    }
}
