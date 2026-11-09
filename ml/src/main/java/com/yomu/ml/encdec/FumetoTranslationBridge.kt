package com.yomu.ml.encdec

import android.util.Log
import com.yomu.core.*
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Page-level TranslationSlot implementation powered by fumetotranslate ONNX encoder-decoder.
 * Int8 ONNX (~448MB) executes in ~300-500ms on mobile CPUs.
 */
class FumetoTranslationBridge(
    private val modelDir: File,
    private val threads: Int = 4,
    private val beam: Int = 4,
    private val engineProvider: ((File) -> TranslationEngine?)? = null
) : TranslationSlot {

    interface TranslationEngine {
        fun translate(lines: List<String>, beam: Int = 4): List<String>
        fun close()
    }

    private class EncDecEngineWrapper(private val engine: EncDecEngine) : TranslationEngine {
        override fun translate(lines: List<String>, beam: Int): List<String> {
            return engine.translatePage(lines, beam = beam)
        }
        override fun close() {
            engine.close()
        }
    }

    companion object {
        private const val TAG = "FumetoTranslationBridge"

        internal fun sanitizeTranslation(raw: String): String {
            var text = raw.trim()
            val leadingPreamble = Regex("^(?:The following (?:Japanese|manga)? ?(?:text|test|line)? has been translated into English(?: as follows)?:?|Translation(?: result)?:?)\\s*", RegexOption.IGNORE_CASE)
            text = leadingPreamble.replace(text, "").trim()

            val leakMarkers = listOf(
                "The following Japanese text",
                "The following text",
                "The following Japanese manga",
                "Please keep",
                "Each line should keep",
                "Keep the [id]",
                "Keep the <id>",
                "Panels are separated",
                "tag in place",
                "Translate each",
                "Translate the following",
                "Note that you should",
                "Target text",
                "Output only the translated result",
                "Output each translation",
                "Reference the following",
                "部室 translates to",
                "ついたー translates to",
                "---"
            )
            for (marker in leakMarkers) {
                val idx = text.indexOf(marker, ignoreCase = true)
                if (idx >= 0) {
                    text = text.substring(0, idx).trimEnd()
                }
            }
            if ((text.startsWith("\"") && text.endsWith("\"") && text.length >= 2) ||
                (text.startsWith("“") && text.endsWith("”") && text.length >= 2) ||
                (text.startsWith("'") && text.endsWith("'") && text.length >= 2)) {
                text = text.substring(1, text.length - 1).trim()
            }
            return text
        }

        internal fun formatWithTypography(source: String, translated: String): String {
            val cleaned = sanitizeTranslation(translated)
            if (cleaned.isEmpty()) return ""

            val hasJapaneseQuotes = source.contains('「') || source.contains('」') ||
                                    source.contains('『') || source.contains('』')

            val isAlreadyQuoted = (cleaned.startsWith("“") && cleaned.endsWith("”")) ||
                                  (cleaned.startsWith("\"") && cleaned.endsWith("\""))

            return if (hasJapaneseQuotes && !isAlreadyQuoted) {
                "“$cleaned”"
            } else {
                cleaned
            }
        }
    }

    private val readinessMutex = Mutex()

    @Volatile
    override var status: TranslationStatus = TranslationStatus.NotReady
        private set

    @Volatile
    private var engine: TranslationEngine? = null

    override suspend fun ensureReady(): Boolean = readinessMutex.withLock {
        if (status is TranslationStatus.Ready && engine != null) return@withLock true

        if (engineProvider != null) {
            val custom = engineProvider.invoke(modelDir)
            if (custom != null) {
                engine = custom
                status = TranslationStatus.Ready
                return@withLock true
            }
        }

        if (!modelDir.exists() || !modelDir.isDirectory) {
            status = TranslationStatus.Error(ERROR_MODEL_MISSING)
            return@withLock false
        }

        val encFile = File(modelDir, "encoder.int8.onnx").let { if (it.exists()) it else File(modelDir, "encoder.onnx") }
        val decFile = File(modelDir, "decoder.int8.onnx").let { if (it.exists()) it else File(modelDir, "decoder.onnx") }
        val encVocab = File(modelDir, "enc-vocab.json")
        val decVocab = File(modelDir, "dec-vocab.json")

        if (!encFile.exists() || !decFile.exists() || !encVocab.exists() || !decVocab.exists()) {
            status = TranslationStatus.Error(ERROR_MODEL_MISSING)
            return@withLock false
        }

        try {
            val tokenizer = EncDecTokenizer.load(encVocab, decVocab)
            val encDec = EncDecEngine.create(
                encoderPath = encFile.absolutePath,
                decoderPath = decFile.absolutePath,
                tokenizer = tokenizer,
                threads = threads
            )
            engine = EncDecEngineWrapper(encDec)
            status = TranslationStatus.Ready
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to load EncDecEngine", t)
            status = TranslationStatus.Error("${t.javaClass.simpleName}: ${t.message}")
            false
        }
    }

    override suspend fun translatePage(
        page: TranslatablePage,
        onBubble: (bubbleId: Int, text: String) -> Unit
    ): PageTranslation {
        if (page.targetLanguage != TargetLanguage.ENGLISH) {
            return PageTranslation(
                byId = emptyMap(),
                rawResponse = "",
                durationMs = 0L,
                outcome = TranslationOutcome.ERROR,
                errorCode = ERROR_ENGLISH_ONLY
            )
        }

        val allBubbles = page.panels.flatten()
        if (allBubbles.isEmpty() || allBubbles.all { it.sourceText.isBlank() }) {
            return PageTranslation(
                byId = emptyMap(),
                rawResponse = "",
                durationMs = 0L,
                outcome = TranslationOutcome.SUCCESS
            )
        }

        if (!ensureReady()) {
            return PageTranslation.notLoaded(status)
        }

        val activeEngine = engine ?: return PageTranslation.notLoaded(status)
        val started = System.currentTimeMillis()

        val validBubbles = allBubbles.filter { it.sourceText.isNotBlank() }
        val lines = validBubbles.map { it.sourceText }

        coroutineContext.ensureActive()
        val rawTranslations = activeEngine.translate(lines, beam = beam)
        coroutineContext.ensureActive()

        val elapsed = System.currentTimeMillis() - started
        val byId = linkedMapOf<Int, String>()

        for (i in validBubbles.indices) {
            val b = validBubbles[i]
            val raw = rawTranslations.getOrElse(i) { "" }
            val formatted = formatWithTypography(b.sourceText, raw)
            byId[b.bubbleId] = formatted
            onBubble(b.bubbleId, formatted)
        }

        return PageTranslation(
            byId = byId,
            rawResponse = rawTranslations.joinToString("\n"),
            durationMs = elapsed,
            outcome = TranslationOutcome.SUCCESS
        )
    }

    override fun endSession() {
        // No per-session cache to invalidate in encoder-decoder
    }

    override fun close() {
        engine?.close()
        engine = null
        status = TranslationStatus.NotReady
    }
}
