package com.yomu.ml

import com.yomu.core.ModelProfile
import com.yomu.core.TargetLanguage
import com.yomu.core.TranslatableBubble
import com.yomu.core.TranslatablePage
import com.yomu.core.TranslationOutcome
import com.yomu.core.TranslationPromptMode
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LlamaTranslationBridgeOverflowTest {

    /** Verifies batch overflow retries each bubble and marks a successful fallback without an error code. */
    @Test
    fun translatePage_batchOverflowFallsBackToPerLine() = runTest {
        val model = File.createTempFile("model", ".gguf")
        val native = FakeLlamaBridge { prompt ->
            if (prompt.contains("Panels are separated")) {
                GenerationResult.Overflow(5L)
            } else {
                GenerationResult.Success(if (prompt.endsWith("こんにちは")) "Hello" else "Goodbye", 10L)
            }
        }
        val slot = LlamaTranslationBridge(native, batchProfile(model))

        val result = slot.translatePage(page(1 to "こんにちは", 2 to "さようなら"))

        assertEquals(mapOf(1 to "Hello", 2 to "Goodbye"), result.byId)
        assertEquals(TranslationOutcome.SUCCESS, result.outcome)
        assertTrue(result.batchOverflowFallback)
        assertNull(result.errorCode)
        assertEquals(3, native.prompts.size)
        model.delete()
    }

    /** Verifies the per-line fallback reports each translated bubble in page order. */
    @Test
    fun translatePage_batchOverflowFallbackStreamsEachBubble() = runTest {
        val model = File.createTempFile("model", ".gguf")
        val native = FakeLlamaBridge { prompt ->
            when {
                prompt.contains("Panels are separated") -> GenerationResult.Overflow(5L)
                prompt.endsWith("こんにちは") -> GenerationResult.Success("Hello", 10L)
                else -> GenerationResult.Success("Goodbye", 10L)
            }
        }
        val slot = LlamaTranslationBridge(native, batchProfile(model))
        val reported = mutableListOf<Pair<Int, String>>()

        slot.translatePage(page(1 to "こんにちは", 2 to "さようなら")) { id, text -> reported += id to text }

        assertEquals(listOf(1 to "Hello", 2 to "Goodbye"), reported)
        model.delete()
    }

    /** Verifies a partial fallback retains successful text while reporting both overflow fallback and timeout. */
    @Test
    fun translatePage_batchOverflowThenPerLineFailureReportsBoth() = runTest {
        val model = File.createTempFile("model", ".gguf")
        val native = FakeLlamaBridge { prompt ->
            when {
                prompt.contains("Panels are separated") -> GenerationResult.Overflow(5L)
                prompt.endsWith("こんにちは") -> GenerationResult.Success("Hello", 10L)
                else -> GenerationResult.Timeout(15L)
            }
        }
        val slot = LlamaTranslationBridge(native, batchProfile(model))

        val result = slot.translatePage(page(1 to "こんにちは", 2 to "さようなら"))

        assertEquals(mapOf(1 to "Hello"), result.byId)
        assertTrue(result.batchOverflowFallback)
        assertEquals(TranslationOutcome.TIMEOUT, result.outcome)
        assertEquals("deadline", result.errorCode)
        model.delete()
    }

    @Test
    fun translatePage_batchThatFitsIsNotMarkedAsFallback() = runTest {
        val model = File.createTempFile("model", ".gguf")
        val slot = LlamaTranslationBridge(
            FakeLlamaBridge { GenerationResult.Success("[1] Hello", 1L) },
            batchProfile(model)
        )

        val result = slot.translatePage(page(1 to "こんにちは"))

        assertFalse(result.batchOverflowFallback)
        model.delete()
    }

    @Test
    fun translatePage_batchOverflowFallbackKeepsTheTargetLanguage() = runTest {
        val model = File.createTempFile("model", ".gguf")
        val native = FakeLlamaBridge { prompt ->
            if (prompt.contains("Panels are separated")) GenerationResult.Overflow(5L) else GenerationResult.Success("Hallo", 10L)
        }
        val profile = ModelProfile(model.absolutePath, true, TranslationPromptMode.TRANSLATION_ONLY, multilingual = true)
        val slot = LlamaTranslationBridge(native, profile)

        slot.translatePage(TranslatablePage(listOf(listOf(TranslatableBubble(1, "こんにちは"))), TargetLanguage.GERMAN))

        assertTrue(native.prompts[0].startsWith("Translate each Japanese line to German."))
        assertTrue(native.prompts[1].startsWith("Translate the target Japanese manga text into natural German."))
        model.delete()
    }

    private fun batchProfile(model: File): ModelProfile =
        ModelProfile(model.absolutePath, true, TranslationPromptMode.MODEL_CARD)

    private fun page(vararg bubbles: Pair<Int, String>): TranslatablePage =
        TranslatablePage(listOf(bubbles.map { (id, source) -> TranslatableBubble(id, source) }))
}
