package com.yomu.pipeline.ocr

internal data class ChineseRead(val text: String, val confidence: Float, val characterCount: Int)

/** Decodes Paddle CTC probabilities [time, classes], with blank at zero. */
internal fun decodeChineseCtc(values: FloatArray, classes: Int, characters: List<String>): ChineseRead {
    require(classes == characters.size + 1 && values.size % classes == 0)
    val text = StringBuilder()
    var previous = -1
    var confidence = 0.0
    var count = 0
    for (offset in values.indices step classes) {
        var best = 0
        for (index in 1 until classes) if (values[offset + index] > values[offset + best]) best = index
        if (best != 0 && best != previous) {
            text.append(characters[best - 1])
            confidence += values[offset + best].coerceIn(0f, 1f)
            count++
        }
        previous = best
    }
    return ChineseRead(text.toString(), if (count == 0) 0f else (confidence / count).toFloat(), count)
}
