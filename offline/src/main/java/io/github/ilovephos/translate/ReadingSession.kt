package io.github.ilovephos.translate

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import com.yomu.core.*
import com.yomu.ml.LlamaBridge
import com.yomu.ml.LlamaTranslationBridge
import com.yomu.ml.OnnxRuntime
import com.yomu.ml.encdec.FumetoTranslationBridge
import com.yomu.pipeline.bubble.Bubble
import com.yomu.pipeline.bubble.BubbleDetector
import com.yomu.pipeline.bubble.bubbleRenderBoxes
import com.yomu.pipeline.context.ContextAssembler
import com.yomu.pipeline.layout.sortedBubbles
import com.yomu.pipeline.layout.supplementalTextRegions
import com.yomu.pipeline.ocr.ChineseOcrEngine
import com.yomu.pipeline.ocr.NarOcrEngine
import com.yomu.pipeline.ocr.ArOcrEngine
import com.yomu.pipeline.ocr.OcrResult
import com.yomu.pipeline.sfx.SfxFilterEngine
import com.yomu.pipeline.translation.TranslatedBubble
import com.yomu.pipeline.typesetting.TypesetBubble
import com.yomu.pipeline.typesetting.Typesetter
import kotlinx.coroutines.ensureActive
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt

data class ReadingPage(val bubbles: List<TypesetBubble>, val total: Int, val elapsedMs: Long)

enum class JapaneseOcrMode(val label: String, val shortLabel: String) {
    AR("Autoregressive (SOTA Accuracy)", "AR"),
    NAR("Non-Autoregressive (Fast 15ms)", "NAR")
}

/** Worker-thread-owned inference objects. Native cancellation is the only cross-thread operation. */
class ReadingSession(
    context: Context,
    private val source: SourceLanguage,
    private val strip: Boolean
) {
    private val root = BundledAssets.directory(context)
    private val runtime = OnnxRuntime(context)
    private val detector = BubbleDetector(runtime)
    private val japaneseNar = NarOcrEngine()
    private val japaneseAr = ArOcrEngine()
    private val chinese = ChineseOcrEngine()
    private var sfxFilter: SfxFilterEngine? = null
    private val llama = if (source == SourceLanguage.CHINESE) LlamaBridge(context) else null
    private val translation: TranslationSlot = if (source == SourceLanguage.JAPANESE) {
        FumetoTranslationBridge(modelDir = root, threads = 4, beam = 4)
    } else {
        LlamaTranslationBridge(llama!!, profile())
    }
    private val typesetter = Typesetter()

    @Volatile
    var ocrMode: JapaneseOcrMode = JapaneseOcrMode.AR

    fun toggleOcrMode(): JapaneseOcrMode {
        ocrMode = if (ocrMode == JapaneseOcrMode.NAR) JapaneseOcrMode.AR else JapaneseOcrMode.NAR
        return ocrMode
    }

    private fun path(name: String) = File(root, name).absolutePath

    suspend fun load() {
        val detectorModel = if (File(root, "manga_panel_detector.onnx").exists()) "manga_panel_detector.onnx" else "bubble_detection.onnx"
        check(detector.loadModel(path(detectorModel))) { "Bubble detector could not be loaded" }
        val ocr = when (source) {
            SourceLanguage.JAPANESE -> {
                val narOk = japaneseNar.loadModel(path("manga_ocr_nar.onnx"), path("nar_vocabulary_6817.json"))
                val arOk = japaneseAr.loadModel(path("manga_ocr_ar_encoder.onnx"), path("manga_ocr_ar_decoder.onnx"), path("nar_vocabulary_6817.json"))
                val detOk = chinese.loadDetector(path("chinese_detection.onnx"))
                sfxFilter = SfxFilterEngine.create(path("sfx-filter-v0.3.onnx"), threads = 2)
                (narOk || arOk) && detOk
            }
            SourceLanguage.CHINESE -> chinese.loadModel(path("chinese_detection.onnx"), path("chinese_recognition.onnx"), path("chinese_characters.json"))
        }
        check(ocr) { "${source.label.substringBefore(' ')} OCR could not be loaded" }
        coroutineContext.ensureActive()
        check(translation.ensureReady()) {
            val statusMsg = when (val s = translation.status) {
                is TranslationStatus.Error -> " (${s.reason})"
                else -> ""
            }
            "Translation model could not be loaded$statusMsg. Stop other large apps and retry."
        }
    }

    private fun profile() = ModelProfile(
        modelPath = path(source.model),
        idKeyedBatch = (source == SourceLanguage.JAPANESE),
        promptMode = if (source == SourceLanguage.JAPANESE) TranslationPromptMode.HY_MT2_MANGA else TranslationPromptMode.HY_MT2,
        generation = if (source == SourceLanguage.JAPANESE)
            GenerationParams(temperature = 0.15f, topK = 20, topP = 0.6f, penaltyRepeat = 1.05f, maxTokens = 64)
        else
            GenerationParams(temperature = 0.7f, topK = 20, topP = 0.6f, penaltyRepeat = 1.05f, maxTokens = 128),
        runtime = RuntimeLimits(
            threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4),
            contextTokens = 2048
        ),
        repackWeights = true,
        multilingual = false
    )

    suspend fun read(
        bitmap: Bitmap,
        ignored: RectF,
        status: (String) -> Unit,
        partial: ((List<TypesetBubble>) -> Unit)? = null
    ): ReadingPage {
        val started = System.currentTimeMillis()
        status("Finding speech bubbles…")
        val bubbles = detector.detect(bitmap, 0.25f)
        coroutineContext.ensureActive()

        status("Finding captions outside speech bubbles…")
        val captions = supplementalTextRegions(chinese.detectRegions(bitmap), bubbles.map { it.boundingBox }, source == SourceLanguage.CHINESE)
        val firstCaption = (bubbles.maxOfOrNull { it.id } ?: -1) + 1
        val rawDetected = filterDetections(
            bubbles + captions.mapIndexed { index, box -> Bubble(firstCaption + index, box, 1f) },
            bitmap.width,
            bitmap.height,
            ignored
        )
        val detected = deduplicateBubbles(rawDetected, firstCaption)
        coroutineContext.ensureActive()

        val ordered = sortedBubbles(detected, strip, source == SourceLanguage.CHINESE)
        val reads = linkedMapOf<Int, OcrResult>()
        val sfxTranslations = linkedMapOf<Int, String>()

        val medianGlyphArea = if (ordered.isNotEmpty()) {
            val areas = ordered.map { it.boundingBox.width() * it.boundingBox.height() }.sorted()
            areas[areas.size / 2] / 5f
        } else 500f

        val pageCtx = SfxFilterEngine.PageContext(
            pageWidth = bitmap.width.toFloat(),
            pageHeight = bitmap.height.toFloat(),
            medianGlyphSize = medianGlyphArea
        )

        for ((index, bubble) in ordered.withIndex()) {
            val engineTag = if (source == SourceLanguage.JAPANESE) ocrMode.shortLabel else "OCR"
            status("Reading ${index + 1} / ${ordered.size} ($engineTag)…")
            val box = bubble.boundingBox
            val left = box.left.roundToInt().coerceIn(0, bitmap.width - 1)
            val top = box.top.roundToInt().coerceIn(0, bitmap.height - 1)
            val right = box.right.roundToInt().coerceIn(left + 1, bitmap.width)
            val bottom = box.bottom.roundToInt().coerceIn(top + 1, bitmap.height)
            val crop = Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
            try {
                val read = if (source == SourceLanguage.JAPANESE) {
                    if (ocrMode == JapaneseOcrMode.AR && japaneseAr.isModelLoaded()) {
                        japaneseAr.extractText(crop)
                    } else {
                        japaneseNar.extractText(crop)
                    }
                } else {
                    chinese.extractText(crop)
                }
                // Japanese OCR confidence threshold raised to 0.65f to reject faint halftones and line noise
                val minimum = if (source == SourceLanguage.JAPANESE) 0.65f else 0.6f
                if (read != null && read.confidence >= minimum && read.text.any { it in '\u3040'..'\u30ff' || it in '\u3400'..'\u9fff' || it in '\uff66'..'\uff9f' }) {
                    // Check sound effect classification for unenclosed text in Japanese
                    if (source == SourceLanguage.JAPANESE && bubble.id >= firstCaption && sfxFilter != null) {
                        val candidate = SfxFilterEngine.CandidateRegion(
                            boundingBox = bubble.boundingBox,
                            recognitionConfidence = read.confidence,
                            classificationConfidence = bubble.confidence,
                            layoutKind = SfxFilterEngine.LayoutKind.UNENCLOSED,
                            text = read.text
                        )
                        val decision = sfxFilter?.classify(bitmap, candidate, pageCtx)
                        if (decision != null && decision.isSfx) {
                            val sfxText = decision.translatedSfx
                            if (sfxText != null) {
                                sfxTranslations[bubble.id] = sfxText
                                reads[bubble.id] = read
                            }
                            // Dictionary miss drops completely
                            continue
                        }
                    }
                    reads[bubble.id] = read
                }
            } finally {
                if (crop !== bitmap) crop.recycle()
            }
            coroutineContext.ensureActive()
        }
        if (reads.isEmpty()) return ReadingPage(emptyList(), detected.size, System.currentTimeMillis() - started)

        val rawPanels = if (!strip && source == SourceLanguage.JAPANESE) {
            val assembled = ContextAssembler().assemble(detected, reads, bitmap).blocks.map { it.readingOrder }
            if (assembled.flatten().isNotEmpty()) assembled else listOf(ordered.map { it.id })
        } else listOf(ordered.map { it.id })

        val allIdsInOrder = rawPanels.flatten()
        val missingIds = reads.keys.filter { it !in allIdsInOrder }
        val prioritizedIds = (allIdsInOrder + missingIds).filter { it in reads }.distinct()

        // Exclude sound effect matches from the page-level dialogue translation queue
        val dialogueOnlyIds = prioritizedIds.filter { it !in sfxTranslations }
        val page = TranslatablePage(listOf(dialogueOnlyIds.mapNotNull { id ->
            reads[id]?.let { read ->
                val cleanText = read.text
                    .replace("\r", "")
                    .replace("\n", "")
                    .trim()
                if (cleanText.isNotEmpty()) {
                    TranslatableBubble(id, cleanText.take(500))
                } else null
            }
        }), TargetLanguage.ENGLISH)

        val boxes = bubbleRenderBoxes(bitmap, detected.associate { bubble ->
            bubble.id to floatArrayOf(bubble.boundingBox.left, bubble.boundingBox.top, bubble.boundingBox.right, bubble.boundingBox.bottom)
        })

        val output = if (dialogueOnlyIds.isNotEmpty()) {
            status("Translating ${dialogueOnlyIds.size} bubbles…")
            translation.translatePage(page)
        } else {
            PageTranslation(emptyMap(), "", 0L, TranslationOutcome.SUCCESS)
        }
        coroutineContext.ensureActive()

        val rendered = linkedMapOf<Int, TypesetBubble>()
        for ((id, text) in output.byId) {
            if (text.isNotBlank()) {
                val original = reads[id] ?: continue
                val typeset = matchPageColors(bitmap, typesetter.typeset(listOf(TranslatedBubble(id, original.text, text, true)), boxes).single())
                // Enforce pure black #000000 on dark AMOLED backgrounds for full subpixel shutoff
                rendered[id] = if (Color.luminance(typeset.backgroundColor) < 0.15f) {
                    typeset.copy(backgroundColor = Color.BLACK, textColor = Color.WHITE)
                } else {
                    typeset
                }
            }
        }

        // Render sound effect matches with unboxed, translucent typography over comic art
        for ((id, sfxText) in sfxTranslations) {
            val original = reads[id] ?: continue
            val typeset = typesetter.typeset(listOf(TranslatedBubble(id, original.text, sfxText, true)), boxes).single()
            rendered[id] = typeset.copy(
                backgroundColor = Color.TRANSPARENT,
                textColor = 0xE6FFFFFF.toInt()
            )
        }

        if (rendered.isEmpty()) {
            return ReadingPage(emptyList(), reads.size, System.currentTimeMillis() - started)
        }
        return ReadingPage(rendered.values.toList(), reads.size, System.currentTimeMillis() - started)
    }

    companion object {
        internal fun isInsideControlBar(box: RectF, controls: RectF): Boolean {
            if (controls.width() <= 0f || controls.height() <= 0f) return false
            val interLeft = maxOf(box.left, controls.left)
            val interTop = maxOf(box.top, controls.top)
            val interRight = minOf(box.right, controls.right)
            val interBottom = minOf(box.bottom, controls.bottom)
            val interArea = maxOf(0f, interRight - interLeft) * maxOf(0f, interBottom - interTop)
            val boxArea = box.width() * box.height()
            return boxArea > 0f && (interArea / boxArea) >= 0.90f && box.height() <= controls.height()
        }

        internal fun filterDetections(
            candidates: List<Bubble>,
            screenWidth: Int,
            screenHeight: Int,
            ignoredControls: RectF = RectF()
        ): List<Bubble> {
            return candidates.filter { bubble ->
                val box = bubble.boundingBox
                box.width() >= 12f && box.height() >= 12f &&
                    !(box.width() >= screenWidth - 4 && box.height() >= screenHeight - 4) &&
                    !isInsideControlBar(box, ignoredControls)
            }
        }

        internal fun deduplicateBubbles(list: List<Bubble>, firstCaption: Int): List<Bubble> {
            if (list.size <= 1) return list
            // Bubble detection boxes (id < firstCaption) take precedence over supplemental caption boxes (id >= firstCaption)
            val sorted = list.sortedWith(
                compareBy<Bubble> { if (it.id < firstCaption) 0 else 1 }
                    .thenByDescending { it.confidence }
                    .thenByDescending { it.boundingBox.width() * it.boundingBox.height() }
            )
            val kept = mutableListOf<Bubble>()
            for (cand in sorted) {
                val cBox = cand.boundingBox
                val cArea = cBox.width() * cBox.height()
                val duplicate = kept.any { ex ->
                    val eBox = ex.boundingBox
                    val eArea = eBox.width() * eBox.height()
                    val interLeft = maxOf(cBox.left, eBox.left)
                    val interTop = maxOf(cBox.top, eBox.top)
                    val interRight = minOf(cBox.right, eBox.right)
                    val interBottom = minOf(cBox.bottom, eBox.bottom)
                    val interArea = maxOf(0f, interRight - interLeft) * maxOf(0f, interBottom - interTop)
                    val minArea = minOf(cArea, eArea)
                    val unionArea = cArea + eArea - interArea
                    val iou = if (unionArea > 0f) interArea / unionArea else 0f
                    val containment = if (minArea > 0f) interArea / minArea else 0f
                    iou >= 0.40f || containment >= 0.60f
                }
                if (!duplicate) {
                    kept.add(cand)
                }
            }
            return kept
        }
    }

    fun abort() { llama?.setAbortRequested(true) }

    fun close() {
        translation.close()
        sfxFilter?.close()
        japaneseNar.release()
        japaneseAr.release()
        chinese.release()
        detector.release()
        runtime.release()
    }
}
