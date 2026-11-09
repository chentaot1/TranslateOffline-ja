package com.yomu.pipeline.sfx

import android.graphics.RectF
import com.yomu.core.MangaSfxDictionary
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.abs
import kotlin.math.ln

@RunWith(RobolectricTestRunner::class)
class SfxFilterEngineTest {

    @Test
    fun computeFeatures_vectorLengthIsExactlyTwentyOne() {
        val box = RectF(100f, 200f, 300f, 400f)
        val candidate = SfxFilterEngine.CandidateRegion(
            boundingBox = box,
            recognitionConfidence = 0.95f,
            classificationConfidence = 0.88f,
            layoutKind = SfxFilterEngine.LayoutKind.FLOATING,
            text = "ドキドキ！"
        )
        val page = SfxFilterEngine.PageContext(
            pageWidth = 1000f,
            pageHeight = 1500f,
            medianGlyphSize = 500f
        )

        val features = SfxFilterEngine.computeFeatures(candidate, page)
        assertEquals("Feature vector must have exactly 21 elements", 21, features.size)
    }

    @Test
    fun computeFeatures_matchesReferenceFormulas() {
        val box = RectF(100f, 200f, 300f, 600f) // width=200, height=400, area=80000
        val candidate = SfxFilterEngine.CandidateRegion(
            boundingBox = box,
            recognitionConfidence = 0.90f,
            classificationConfidence = 0.85f,
            layoutKind = SfxFilterEngine.LayoutKind.UNENCLOSED,
            text = "ドンッ" // 3 chars: 2 Katakana, 1 small tsu (ッ)
        )
        val page = SfxFilterEngine.PageContext(
            pageWidth = 1000f,
            pageHeight = 2000f, // pageArea = 2,000,000
            medianGlyphSize = 10000f
        )

        val features = SfxFilterEngine.computeFeatures(candidate, page)

        // 0: conf_ocr
        assertEquals(0.90f, features[0], 1e-4f)
        // 1: conf_det
        assertEquals(0.85f, features[1], 1e-4f)
        // 2: log(w/h) = ln(200 / 400) = ln(0.5) ~ -0.6931
        assertEquals(ln(0.5f), features[2], 1e-4f)
        // 3: log(area/page) = ln(80000 / 2000000) = ln(0.04) ~ -3.2188
        assertEquals(ln(0.04f), features[3], 1e-4f)
        // 4: glyph_size = (80000 / 3) / 10000 = 2.6666
        assertEquals((80000f / 3f) / 10000f, features[4], 1e-4f)
        // 5: center_x = (100 + 300) / (2 * 1000) = 0.2
        assertEquals(0.2f, features[5], 1e-4f)
        // 6: center_y = (200 + 600) / (2 * 2000) = 0.2
        assertEquals(0.2f, features[6], 1e-4f)

        // 7..11: layout one-hot (UNENCLOSED is index 1)
        assertEquals(0f, features[7], 1e-4f) // ENCLOSED_BALLOON
        assertEquals(1f, features[8], 1e-4f) // UNENCLOSED
        assertEquals(0f, features[9], 1e-4f) // FLOATING
        assertEquals(0f, features[10], 1e-4f) // CAPTION
        assertEquals(0f, features[11], 1e-4f) // OTHER

        // 12: log1p_chars = ln(1 + 3) = ln(4)
        assertEquals(ln(4f), features[12], 1e-4f)
        // 13: katakana_ratio = 3/3 = 1.0 (ド, ン, ッ are all Katakana)
        assertEquals(1.0f, features[13], 1e-4f)
        // 14: hiragana_ratio = 0.0
        assertEquals(0.0f, features[14], 1e-4f)
        // 15: kanji_ratio = 0.0
        assertEquals(0.0f, features[15], 1e-4f)
        // 16: emphasis_ratio = 0.0
        assertEquals(0.0f, features[16], 1e-4f)
        // 17: small_tsu = 1.0 (contains ッ)
        assertEquals(1.0f, features[17], 1e-4f)
        // 18: multiline = 0.0
        assertEquals(0.0f, features[18], 1e-4f)
        // 19: latin_ratio = 0.0
        assertEquals(0.0f, features[19], 1e-4f)
        // 20: punct_flag = 0.0
        assertEquals(0.0f, features[20], 1e-4f)
    }

    @Test
    fun classifyResult_decisionThresholds() {
        // Above threshold (>= 0.8672) with dictionary match -> translated
        val matchResult = SfxFilterEngine.evaluateDecision(
            pSfx = 0.87f,
            text = "ドキドキ"
        )
        assertTrue(matchResult.isSfx)
        assertEquals("*thump thump*", matchResult.translatedSfx)

        // Above threshold (>= 0.8672) with dictionary miss -> dropped (null translation)
        val missResult = SfxFilterEngine.evaluateDecision(
            pSfx = 0.89f,
            text = "未知の謎の擬音xyz"
        )
        assertTrue(missResult.isSfx)
        assertNull("SFX dictionary misses must be dropped completely to protect comic art", missResult.translatedSfx)

        // Below threshold (< 0.8672) -> kept for dialogue pipeline
        val dialogueResult = SfxFilterEngine.evaluateDecision(
            pSfx = 0.45f,
            text = "お腹すいた"
        )
        assertFalse("Below threshold candidate must remain dialogue", dialogueResult.isSfx)
    }

    @Test
    fun calculatePaddedBox_appliesTwelvePercentPadding() {
        val box = RectF(100f, 100f, 200f, 300f) // w=100, h=200
        val padded = SfxFilterEngine.computePaddedBounds(
            box = box,
            pageWidth = 1000f,
            pageHeight = 1000f,
            paddingPercent = 0.12f
        )
        // padW = 100 * 0.12 = 12, padH = 200 * 0.12 = 24
        assertEquals(88f, padded.left, 1e-4f)
        assertEquals(76f, padded.top, 1e-4f)
        assertEquals(212f, padded.right, 1e-4f)
        assertEquals(324f, padded.bottom, 1e-4f)
    }
}
