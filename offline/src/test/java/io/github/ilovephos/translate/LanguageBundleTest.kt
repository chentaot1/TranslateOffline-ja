package io.github.ilovephos.translate

import org.junit.Assert.*
import org.junit.Test

class LanguageBundleTest {
    @Test fun japaneseContainsFumetoAndNarOcrWithoutChineseRecognizerOrHy() {
        val assets = SourceLanguage.JAPANESE.requiredAssets
        assertEquals(setOf(
            "manga_panel_detector.onnx",
            "encoder.int8.onnx",
            "decoder.int8.onnx",
            "enc-vocab.json",
            "dec-vocab.json",
            "meta.json",
            "sfx-filter-v0.3.onnx",
            "manga_ocr_nar.onnx",
            "nar_vocabulary_6817.json",
            "chinese_detection.onnx",
            "manga_ocr_ar_encoder.onnx",
            "manga_ocr_ar_decoder.onnx"
        ), assets)
        assertEquals(12, assets.size)
        assertFalse(assets.contains("bubble_detection.onnx"))
        assertFalse(assets.contains("hy_mt2.gguf"))
        assertFalse(assets.contains("cat_translate.gguf"))
        assertFalse(assets.contains("chinese_recognition.onnx"))
        assertFalse(assets.contains("chinese_characters.json"))
        assertTrue(assets.contains("chinese_detection.onnx"))
        assertTrue(assets.contains("manga_ocr_ar_encoder.onnx"))
        assertTrue(assets.contains("manga_ocr_ar_decoder.onnx"))
    }

    @Test fun chineseContainsHyAndChineseOcrWithoutCatOrJapaneseOcr() {
        val assets = SourceLanguage.CHINESE.requiredAssets
        assertEquals(setOf("hy_mt2.gguf", "manga_panel_detector.onnx", "chinese_detection.onnx",
            "chinese_recognition.onnx", "chinese_characters.json"), assets)
        assertFalse(assets.contains("bubble_detection.onnx"))
        assertFalse(assets.contains("cat_translate.gguf"))
        assertFalse(assets.contains("manga_ocr_nar.onnx"))
    }

    @Test fun retirementOnlyTargetsKnownFilesUnusedByTheSelectedLanguage() {
        for (language in SourceLanguage.entries) {
            assertTrue(language.requiredAssets.intersect(language.retiredAssets).isEmpty())
        }
        assertFalse(SourceLanguage.JAPANESE.retiredAssets.contains("chinese_detection.onnx"))
        assertTrue(SourceLanguage.JAPANESE.retiredAssets.contains("manga_ocr_encoder.onnx"))
        assertTrue(SourceLanguage.JAPANESE.retiredAssets.contains("manga_ocr_decoder.onnx"))
        assertTrue(SourceLanguage.JAPANESE.retiredAssets.contains("vocab.txt"))
        assertTrue(SourceLanguage.JAPANESE.retiredAssets.contains("hy_mt2.gguf"))
        assertTrue(SourceLanguage.JAPANESE.retiredAssets.contains("cat_translate.gguf"))
    }

    @Test fun rejectsMixedMissingAndDuplicateAssetManifests() {
        val language = SourceLanguage.JAPANESE
        fun entry(name: String) = AssetEntry(name, 1, "a".repeat(64))
        val valid = language.requiredAssets.map(::entry)
        assertTrue(language.accepts(valid))
        assertFalse(language.accepts(valid + entry("hy_mt2.gguf")))
        assertFalse(language.accepts(valid.drop(1)))
        assertFalse(language.accepts(valid + valid.first()))
    }
}
