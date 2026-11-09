package com.yomu.pipeline

import com.yomu.pipeline.ocr.OcrResult
import com.yomu.pipeline.translation.TranslationResult
import com.yomu.pipeline.typesetting.TypesetBubble

data class PipelineResult(
    val typesetBubbles: List<TypesetBubble>,
    val translationResult: TranslationResult,
    val pageWidth: Int,
    val pageHeight: Int,
    val totalTimeMs: Long,
    /** From the page reaching the pipeline to its first bubble being ready to draw; null when none was. */
    val timeToFirstBubbleMs: Long? = null,
    /** Each stage that ran, in order. Loading the vision models is left out; a translation model load is in TRANSLATION. */
    val stageTimesMs: Map<TranslationPipeline.Stage, Long> = emptyMap(),
    /** Every detected bubble's glyph box as left, top, right, bottom, in detection order. */
    val bubbleBounds: Map<Int, FloatArray> = emptyMap(),
    /** Every read with kana or kanji in it, by bubble id, including the [OcrResult.unsure] ones never translated. */
    val reads: Map<Int, OcrResult> = emptyMap(),
    /** OCR text with no kana or kanji, by bubble id. Only the page report reads it; it is never previewed, translated or drawn. */
    val droppedReads: Map<Int, String> = emptyMap()
)
