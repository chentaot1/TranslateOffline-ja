package com.yomu.pipeline.sfx

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.util.Log
import com.yomu.core.MangaSfxDictionary
import java.io.Closeable
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class SfxFilterEngine(
    private val session: OrtSession? = null,
    private val env: OrtEnvironment? = null,
    private val ownsSession: Boolean = false
) : Closeable {

    enum class LayoutKind(val index: Int) {
        ENCLOSED_BALLOON(0),
        UNENCLOSED(1),
        FLOATING(2),
        CAPTION(3),
        OTHER(4)
    }

    data class CandidateRegion(
        val boundingBox: RectF,
        val recognitionConfidence: Float,
        val classificationConfidence: Float,
        val layoutKind: LayoutKind,
        val text: String
    )

    data class PageContext(
        val pageWidth: Float,
        val pageHeight: Float,
        val medianGlyphSize: Float
    )

    data class SfxDecision(
        val isSfx: Boolean,
        val pSfx: Float,
        val translatedSfx: String? = null
    )

    companion object {
        private const val TAG = "SfxFilterEngine"
        const val SFX_THRESHOLD = 0.8672f
        const val INPUT_SIZE = 224
        private const val PADDING_PERCENT = 0.12f

        private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        private val STD = floatArrayOf(0.229f, 0.224f, 0.225f)
        private const val NEUTRAL_GRAY = 114

        fun computePaddedBounds(
            box: RectF,
            pageWidth: Float,
            pageHeight: Float,
            paddingPercent: Float = PADDING_PERCENT
        ): RectF {
            val w = box.right - box.left
            val h = box.bottom - box.top
            val padW = w * paddingPercent
            val padH = h * paddingPercent
            val left = max(0f, box.left - padW)
            val top = max(0f, box.top - padH)
            val right = min(pageWidth, box.right + padW)
            val bottom = min(pageHeight, box.bottom + padH)
            return RectF(left, top, right, bottom)
        }

        fun computeFeatures(candidate: CandidateRegion, page: PageContext): FloatArray {
            val feats = FloatArray(21)
            val box = candidate.boundingBox
            val w = box.right - box.left
            val h = box.bottom - box.top
            val area = w * h
            val text = candidate.text
            val charCount = text.length

            // 0: conf_ocr
            feats[0] = candidate.recognitionConfidence
            // 1: conf_det
            feats[1] = candidate.classificationConfidence
            // 2: log(w/h)
            feats[2] = ln(max(1e-4f, w / max(1e-4f, h)))
            // 3: log(area/page)
            val pageArea = max(1f, page.pageWidth * page.pageHeight)
            feats[3] = ln(max(1e-7f, area / pageArea))
            // 4: glyph_size
            val glyphSize = area / max(1, charCount)
            feats[4] = glyphSize / max(1f, page.medianGlyphSize)
            // 5: center_x
            feats[5] = (box.left + box.right) / (2f * max(1f, page.pageWidth))
            // 6: center_y
            feats[6] = (box.top + box.bottom) / (2f * max(1f, page.pageHeight))

            // 7..11: layout one-hot (5 categories)
            val kIdx = candidate.layoutKind.index.coerceIn(0, 4)
            feats[7 + kIdx] = 1.0f

            // 12: log1p_chars
            feats[12] = ln(1f + charCount)

            // Text features
            var katakana = 0
            var hiragana = 0
            var kanji = 0
            var emphasis = 0
            var latin = 0
            var punct = 0
            var hasSmallTsu = false

            for (c in text) {
                when {
                    c in '\u30A0'..'\u30FF' || c in '\uFF66'..'\uFF9F' -> katakana++
                    c in '\u3040'..'\u309F' -> hiragana++
                    c in '\u4E00'..'\u9FFF' || c in '\u3400'..'\u4DBF' -> kanji++
                    c in "!?!?\uFF01\uFF1F" -> emphasis++
                    c in 'a'..'z' || c in 'A'..'Z' -> latin++
                }
                if (c == 'っ' || c == 'ッ') hasSmallTsu = true
                if (c in ".,…〜ー-()[]{}「」『』\"'、。！？") punct++
            }

            val denom = max(1, charCount).toFloat()
            // 13: katakana_ratio
            feats[13] = katakana / denom
            // 14: hiragana_ratio
            feats[14] = hiragana / denom
            // 15: kanji_ratio
            feats[15] = kanji / denom
            // 16: emphasis_ratio
            feats[16] = emphasis / denom
            // 17: small_tsu
            feats[17] = if (hasSmallTsu) 1.0f else 0.0f
            // 18: multiline
            feats[18] = if (text.contains('\n') || text.contains('\r')) 1.0f else 0.0f
            // 19: latin_ratio
            feats[19] = latin / denom
            // 20: punct_flag
            feats[20] = if (punct > 0) 1.0f else 0.0f

            return feats
        }

        fun evaluateDecision(pSfx: Float, text: String): SfxDecision {
            return if (pSfx >= SFX_THRESHOLD) {
                val translation = MangaSfxDictionary.lookup(text)
                SfxDecision(isSfx = true, pSfx = pSfx, translatedSfx = translation)
            } else {
                SfxDecision(isSfx = false, pSfx = pSfx)
            }
        }

        fun create(modelPath: String, threads: Int = 2): SfxFilterEngine? {
            if (!File(modelPath).isFile) return null
            return try {
                val env = OrtEnvironment.getEnvironment()
                val opts = OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(threads)
                    setInterOpNumThreads(1)
                    addConfigEntry("session.intra_op.allow_spinning", "0")
                }
                val sess = try {
                    env.createSession(modelPath, opts)
                } finally {
                    opts.close()
                }
                SfxFilterEngine(sess, env, ownsSession = true)
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to load SfxFilterEngine", t)
                null
            }
        }
    }

    fun isLoaded(): Boolean = session != null

    fun classify(
        bitmap: Bitmap,
        candidate: CandidateRegion,
        page: PageContext
    ): SfxDecision {
        val sess = session ?: return SfxDecision(isSfx = false, pSfx = 0f)
        val activeEnv = env ?: OrtEnvironment.getEnvironment()

        val padded = computePaddedBounds(candidate.boundingBox, page.pageWidth, page.pageHeight)
        val cropLeft = padded.left.roundToInt().coerceIn(0, bitmap.width - 1)
        val cropTop = padded.top.roundToInt().coerceIn(0, bitmap.height - 1)
        val cropRight = padded.right.roundToInt().coerceIn(cropLeft + 1, bitmap.width)
        val cropBottom = padded.bottom.roundToInt().coerceIn(cropTop + 1, bitmap.height)

        val cropW = cropRight - cropLeft
        val cropH = cropBottom - cropTop
        val crop = Bitmap.createBitmap(bitmap, cropLeft, cropTop, cropW, cropH)

        val imageBuffer = FloatBuffer.allocate(1 * 3 * INPUT_SIZE * INPUT_SIZE)
        val features = computeFeatures(candidate, page)
        val featuresBuffer = FloatBuffer.wrap(features)

        try {
            // Letterbox into 224x224
            val scale = min(INPUT_SIZE.toFloat() / cropW, INPUT_SIZE.toFloat() / cropH)
            val targetW = max(1, min(INPUT_SIZE, (cropW * scale).roundToInt()))
            val targetH = max(1, min(INPUT_SIZE, (cropH * scale).roundToInt()))
            val scaled = if (targetW == cropW && targetH == cropH) crop else Bitmap.createScaledBitmap(crop, targetW, targetH, true)

            val xOffset = (INPUT_SIZE - targetW) / 2
            val yOffset = (INPUT_SIZE - targetH) / 2

            val pixels = IntArray(targetW * targetH)
            scaled.getPixels(pixels, 0, targetW, 0, 0, targetW, targetH)

            val grayNorm = floatArrayOf(
                ((NEUTRAL_GRAY / 255.0f) - MEAN[0]) / STD[0],
                ((NEUTRAL_GRAY / 255.0f) - MEAN[1]) / STD[1],
                ((NEUTRAL_GRAY / 255.0f) - MEAN[2]) / STD[2]
            )

            // Fill with neutral gray
            val planeSize = INPUT_SIZE * INPUT_SIZE
            val imgArray = FloatArray(3 * planeSize)
            for (c in 0 until 3) {
                val base = c * planeSize
                val normG = grayNorm[c]
                for (i in 0 until planeSize) {
                    imgArray[base + i] = normG
                }
            }

            // Copy scaled pixels
            for (y in 0 until targetH) {
                val rowInImg = (yOffset + y) * INPUT_SIZE + xOffset
                val rowInPix = y * targetW
                for (x in 0 until targetW) {
                    val p = pixels[rowInPix + x]
                    val idx = rowInImg + x
                    imgArray[0 * planeSize + idx] = ((Color.red(p) / 255.0f) - MEAN[0]) / STD[0]
                    imgArray[1 * planeSize + idx] = ((Color.green(p) / 255.0f) - MEAN[1]) / STD[1]
                    imgArray[2 * planeSize + idx] = ((Color.blue(p) / 255.0f) - MEAN[2]) / STD[2]
                }
            }
            if (scaled !== crop) scaled.recycle()

            imageBuffer.put(imgArray)
            imageBuffer.flip()

            val imgTensor = OnnxTensor.createTensor(activeEnv, imageBuffer, longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong()))
            val featTensor = OnnxTensor.createTensor(activeEnv, featuresBuffer, longArrayOf(1, 21))

            val sideName = if (sess.inputNames.contains("side")) "side" else "features"
            val inputs = mapOf("image" to imgTensor, sideName to featTensor)
            val result = try {
                sess.run(inputs)
            } finally {
                imgTensor.close()
                featTensor.close()
            }

            val pSfx = try {
                val outTensor = result.get(0) as OnnxTensor
                val fb = outTensor.floatBuffer
                if (fb.remaining() >= 2) {
                    // Softmax or 2-class output: [p_other, p_sfx]
                    fb.position(1)
                    fb.get()
                } else {
                    // Logit: sigmoid
                    val logit = fb.get(0)
                    (1.0f / (1.0f + kotlin.math.exp(-logit)))
                }
            } finally {
                result.close()
            }

            return evaluateDecision(pSfx, candidate.text)
        } finally {
            if (crop !== bitmap) crop.recycle()
        }
    }

    override fun close() {
        if (ownsSession) {
            session?.close()
            env?.close()
        }
    }
}
