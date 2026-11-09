package com.yomu.ml

import com.yomu.core.ModelProfile
import com.yomu.core.TranslatableBubble
import com.yomu.core.TranslatablePage
import com.yomu.core.TranslationOutcome
import com.yomu.core.TranslationPromptMode
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The batch reply streamed line by line to the bubble listener (#332). */
class LlamaTranslationBridgeStreamingTest {

    /** Verifies each batch line is reported as soon as its newline is decoded, before the reply ends. */
    @Test
    fun translatePage_batchReportsEachLineWhileTheReplyIsStillGenerating() = runTest {
        val model = File.createTempFile("model", ".gguf")
        val native = FakeLlamaBridge { GenerationResult.Success("[1] Hello\n[2] Goodbye\n", 1L) }
        val slot = LlamaTranslationBridge(native, profile(model))
        val reported = mutableListOf<Pair<Int, String>>()

        val result = slot.translatePage(page(1 to "こんにちは", 2 to "さようなら")) { id, text ->
            assertTrue(native.generating)
            reported += id to text
        }

        assertEquals(listOf(1 to "Hello", 2 to "Goodbye"), reported)
        assertEquals(result.byId.toList(), reported)
        model.delete()
    }

    /** Verifies a character split across chunks is reassembled before its line is decoded, whatever the chunk size. */
    @Test
    fun translatePage_batchDecodesCharactersSplitAcrossChunks() = runTest {
        val model = File.createTempFile("model", ".gguf")
        for (chunkBytes in 1..7) {
            val slot = LlamaTranslationBridge(
                FakeLlamaBridge(chunkBytes) { GenerationResult.Success("[1] Café… ☕\n[2] Naïve — 🙂\n", 1L) },
                profile(model)
            )
            val reported = mutableListOf<Pair<Int, String>>()

            val result = slot.translatePage(page(1 to "こんにちは", 2 to "さようなら")) { id, text -> reported += id to text }

            assertEquals("chunk=$chunkBytes", listOf(1 to "Café… ☕", 2 to "Naïve — 🙂"), reported)
            assertEquals("chunk=$chunkBytes", result.byId.toList(), reported)
        }
        model.delete()
    }

    @Test
    fun translatePage_batchNeverReportsAnIdNotOnThePage() = runTest {
        val model = File.createTempFile("model", ".gguf")
        val slot = LlamaTranslationBridge(
            FakeLlamaBridge { GenerationResult.Success("[1] Hello\n[9] Ghost\n[2] Goodbye\n", 1L) },
            profile(model)
        )
        val reported = mutableListOf<Pair<Int, String>>()

        val result = slot.translatePage(page(1 to "こんにちは", 2 to "さようなら")) { id, text -> reported += id to text }

        assertEquals(listOf(1 to "Hello", 2 to "Goodbye"), reported)
        assertEquals(result.byId.toList(), reported)
        model.delete()
    }

    /** The grammar lets the model end its reply without a final newline, so the last line is reported when the model ends it (#368). */
    @Test
    fun translatePage_batchReportsTheLastLineWhenTheModelEndsTheReplyWithoutANewline() = runTest {
        val model = File.createTempFile("model", ".gguf")
        val slot = LlamaTranslationBridge(
            FakeLlamaBridge { GenerationResult.Success("[1] Hello\n[2] Goodbye", 1L, endedByModel = true) },
            profile(model)
        )
        val reported = mutableListOf<Pair<Int, String>>()

        val result = slot.translatePage(page(1 to "こんにちは", 2 to "さようなら")) { id, text -> reported += id to text }

        assertEquals(listOf(1 to "Hello", 2 to "Goodbye"), reported)
        assertEquals(result.byId.toList(), reported)
        model.delete()
    }

    /** A reply cut off by the deadline ends mid-line: the lines already reported stay, the cut one is unanswered. */
    @Test
    fun translatePage_batchCutOffMidLineKeepsOnlyTheCompleteLines() = runTest {
        val model = File.createTempFile("model", ".gguf")
        val slot = LlamaTranslationBridge(
            FakeLlamaBridge { GenerationResult.Success("[1] Hello\n[2] Good", 1L) },
            profile(model)
        )
        val reported = mutableListOf<Pair<Int, String>>()

        val result = slot.translatePage(page(1 to "こんにちは", 2 to "さようなら", 3 to "またね")) { id, text ->
            reported += id to text
        }

        assertEquals(listOf(1 to "Hello"), reported)
        assertEquals(mapOf(1 to "Hello"), result.byId)
        assertEquals(TranslationOutcome.SUCCESS, result.outcome)
        model.delete()
    }

    private fun profile(model: File): ModelProfile =
        ModelProfile(model.absolutePath, true, TranslationPromptMode.MODEL_CARD)

    private fun page(vararg bubbles: Pair<Int, String>): TranslatablePage =
        TranslatablePage(listOf(bubbles.map { (id, source) -> TranslatableBubble(id, source) }))
}
