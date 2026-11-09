package com.yomu.ml.encdec

import com.yomu.core.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class FumetoTranslationBridgeTest {

    private fun page(vararg bubbles: Pair<Int, String>): TranslatablePage =
        TranslatablePage(listOf(bubbles.map { TranslatableBubble(it.first, it.second) }))

    @Test
    fun ensureReady_missingModelDirectory_returnsFalseAndSetsMissingStatus() = runTest {
        val nonExistentDir = File("build/non_existent_model_dir_xyz_${System.currentTimeMillis()}")
        val bridge = FumetoTranslationBridge(nonExistentDir)

        assertFalse(bridge.ensureReady())
        assertEquals(TranslationStatus.Error(ERROR_MODEL_MISSING), bridge.status)
    }

    @Test
    fun translatePage_refusesNonEnglishTargetLanguage() = runTest {
        val dummyDir = File.createTempFile("models", "dir")
        val bridge = FumetoTranslationBridge(
            modelDir = dummyDir,
            engineProvider = { null }
        )

        val nonEnglishPage = TranslatablePage(
            listOf(listOf(TranslatableBubble(1, "こんにちは"))),
            targetLanguage = TargetLanguage.FRENCH
        )

        val result = bridge.translatePage(nonEnglishPage)
        assertEquals(TranslationOutcome.ERROR, result.outcome)
        assertEquals(ERROR_ENGLISH_ONLY, result.errorCode)
        dummyDir.delete()
    }

    @Test
    fun translatePage_emptyBubbles_returnsEmptyTranslation() = runTest {
        val dummyDir = File.createTempFile("models", "dir")
        val bridge = FumetoTranslationBridge(
            modelDir = dummyDir,
            engineProvider = { null }
        )

        val emptyPage = page(1 to "", 2 to "   ")
        val result = bridge.translatePage(emptyPage)

        assertEquals(TranslationOutcome.SUCCESS, result.outcome)
        assertTrue(result.byId.isEmpty() || result.byId.values.all { it.isBlank() })
        dummyDir.delete()
    }

    @Test
    fun formatWithTypography_preservesQuotationTypographyForJapaneseQuotes() {
        val raw = "Hello world"
        val quotedCorner = FumetoTranslationBridge.formatWithTypography("「こんにちは」", raw)
        assertTrue("Corner quotes should wrap output in quotation marks: $quotedCorner",
            (quotedCorner.startsWith("“") && quotedCorner.endsWith("”")) ||
            (quotedCorner.startsWith("\"") && quotedCorner.endsWith("\"")))

        val quotedDouble = FumetoTranslationBridge.formatWithTypography("『こんにちは』", raw)
        assertTrue("Double quotes should wrap output in quotation marks: $quotedDouble",
            (quotedDouble.startsWith("“") && quotedDouble.endsWith("”")) ||
            (quotedDouble.startsWith("\"") && quotedDouble.endsWith("\"")))

        val normal = FumetoTranslationBridge.formatWithTypography("こんにちは", raw)
        assertEquals("Hello world", normal)
    }

    @Test
    fun sanitizeTranslation_cleansLeakMarkersAndPreambles() {
        val leaky = "The following Japanese text has been translated into English: Hello! Reference the following..."
        val cleaned = FumetoTranslationBridge.sanitizeTranslation(leaky)
        assertEquals("Hello!", cleaned)
    }

    @Test
    fun translatePage_invokesOnBubbleCallback() = runTest {
        val dummyDir = File.createTempFile("models", "dir")
        val fakeEngine = object : FumetoTranslationBridge.TranslationEngine {
            override fun translate(lines: List<String>, beam: Int): List<String> {
                return lines.map { "Translated: $it" }
            }
            override fun close() {}
        }
        val bridge = FumetoTranslationBridge(
            modelDir = dummyDir,
            engineProvider = { fakeEngine }
        )
        bridge.ensureReady()

        val delivered = mutableMapOf<Int, String>()
        val result = bridge.translatePage(page(1 to "テスト", 2 to "成功")) { id, text ->
            delivered[id] = text
        }

        assertEquals(TranslationOutcome.SUCCESS, result.outcome)
        assertEquals("Translated: テスト", result.byId[1])
        assertEquals("Translated: 成功", result.byId[2])
        assertEquals("Translated: テスト", delivered[1])
        assertEquals("Translated: 成功", delivered[2])
        dummyDir.delete()
    }
}
