package com.yomu.pipeline.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Autoregressive (AR) manga OCR engine based on muscgab/manga-ocr-ar-experimental.
 * Features MonkeyOCRv2-B dynamic patch vision tower and 1-layer autoregressive decoder with KV-cache.
 * Achieves 96.01% EM on JMangaBench_Read and 89.35% EM on JMangaBench_Hard (handwriting & SFX).
 */
class ArOcrEngine {

    companion object {
        private const val TAG = "ArOcrEngine"
        private const val BUDGET = 324
        private const val PATCH = 14
        private const val FEAT = 588 // 14 * 14 * 3
        private val MEAN = floatArrayOf(0.48145466f, 0.4578275f, 0.40821073f)
        private val STD = floatArrayOf(0.26862954f, 0.26130258f, 0.27577711f)
        private const val MAX_DECODE_STEPS = 128
    }

    private var encoderSession: OrtSession? = null
    private var decoderSession: OrtSession? = null
    private var characters: List<String> = emptyList()
    private var eosTokenId: Long = 6817L
    private var bosTokenId: Long = 6818L

    data class GridInfo(
        val scale: Float,
        val gridH: Int,
        val gridW: Int,
        val resizedW: Int,
        val resizedH: Int
    )

    fun computeGrid(width: Int, height: Int): GridInfo {
        var lo = 1e-4f
        var hi = 1000.0f
        for (i in 0 until 60) {
            val mid = (lo + hi) / 2.0f
            val gh = ceil(height * mid / PATCH).toLong()
            val gw = ceil(width * mid / PATCH).toLong()
            if (gh * gw <= BUDGET) {
                lo = mid
            } else {
                hi = mid
            }
        }
        val gh = ceil(height * lo / PATCH).toInt()
        val gw = ceil(width * lo / PATCH).toInt()
        val rw = max(1, min(gw * PATCH, (width * lo).roundToInt()))
        val rh = max(1, min(gh * PATCH, (height * lo).roundToInt()))
        return GridInfo(lo, gh, gw, rw, rh)
    }

    @Synchronized
    fun loadModel(encoderPath: String, decoderPath: String, vocabPath: String): Boolean {
        release()
        if (!File(encoderPath).isFile || !File(decoderPath).isFile || !File(vocabPath).isFile) return false
        return try {
            val vocabText = File(vocabPath).readText(Charsets.UTF_8)
            val root = JSONObject(vocabText)
            val arr = root.getJSONArray("characters")
            val loaded = (0 until arr.length()).map { arr.getString(it) }
            require(loaded.isNotEmpty())
            characters = loaded
            eosTokenId = loaded.size.toLong()
            bosTokenId = eosTokenId + 1L

            val env = OrtEnvironment.getEnvironment()
            OrtSession.SessionOptions().use { options ->
                options.setIntraOpNumThreads(4)
                options.setInterOpNumThreads(1)
                encoderSession = env.createSession(encoderPath, options)
                decoderSession = env.createSession(decoderPath, options)
            }
            Log.i(TAG, "ArOcrEngine loaded: enc=$encoderPath dec=$decoderPath vocab=${characters.size} eos=$eosTokenId")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load AR OCR model", e)
            release()
            false
        }
    }

    fun isModelLoaded(): Boolean = encoderSession != null && decoderSession != null && characters.isNotEmpty()

    @Synchronized
    fun extractText(region: Bitmap): OcrResult? {
        val encSess = encoderSession ?: return null
        val decSess = decoderSession ?: return null
        if (region.isRecycled || region.width <= 0 || region.height <= 0) return null

        return try {
            val grid = computeGrid(region.width, region.height)
            val scaled = Bitmap.createScaledBitmap(region, grid.resizedW, grid.resizedH, true)

            val canvas0: FloatArray
            val canvas1: FloatArray
            val canvas2: FloatArray
            val canvasW = grid.gridW * PATCH
            try {
                val borderR = ArrayList<Int>((grid.resizedW + grid.resizedH) * 2)
                val borderG = ArrayList<Int>((grid.resizedW + grid.resizedH) * 2)
                val borderB = ArrayList<Int>((grid.resizedW + grid.resizedH) * 2)

                for (x in 0 until grid.resizedW) {
                    val rgbTop = scaled.getPixel(x, 0)
                    val rgbBot = scaled.getPixel(x, grid.resizedH - 1)
                    borderR.add(Color.red(rgbTop)); borderG.add(Color.green(rgbTop)); borderB.add(Color.blue(rgbTop))
                    borderR.add(Color.red(rgbBot)); borderG.add(Color.green(rgbBot)); borderB.add(Color.blue(rgbBot))
                }
                for (y in 0 until grid.resizedH) {
                    val rgbLeft = scaled.getPixel(0, y)
                    val rgbRight = scaled.getPixel(grid.resizedW - 1, y)
                    borderR.add(Color.red(rgbLeft)); borderG.add(Color.green(rgbLeft)); borderB.add(Color.blue(rgbLeft))
                    borderR.add(Color.red(rgbRight)); borderG.add(Color.green(rgbRight)); borderB.add(Color.blue(rgbRight))
                }
                val lightR = borderR.filter { it >= 128 }.sorted()
                val lightG = borderG.filter { it >= 128 }.sorted()
                val lightB = borderB.filter { it >= 128 }.sorted()

                val padR = if (lightR.isNotEmpty()) lightR[lightR.size / 2] else { borderR.sort(); borderR[borderR.size / 2] }
                val padG = if (lightG.isNotEmpty()) lightG[lightG.size / 2] else { borderG.sort(); borderG[borderG.size / 2] }
                val padB = if (lightB.isNotEmpty()) lightB[lightB.size / 2] else { borderB.sort(); borderB[borderB.size / 2] }

                val normMedR = ((padR / 255.0f) - MEAN[0]) / STD[0]
                val normMedG = ((padG / 255.0f) - MEAN[1]) / STD[1]
                val normMedB = ((padB / 255.0f) - MEAN[2]) / STD[2]

                val canvasH = grid.gridH * PATCH

                canvas0 = FloatArray(canvasH * canvasW) { normMedR }
                canvas1 = FloatArray(canvasH * canvasW) { normMedG }
                canvas2 = FloatArray(canvasH * canvasW) { normMedB }

                val pixels = IntArray(grid.resizedW * grid.resizedH)
                scaled.getPixels(pixels, 0, grid.resizedW, 0, 0, grid.resizedW, grid.resizedH)

                for (y in 0 until grid.resizedH) {
                    val rowOffset = y * canvasW
                    val pixRowOffset = y * grid.resizedW
                    for (x in 0 until grid.resizedW) {
                        val p = pixels[pixRowOffset + x]
                        canvas0[rowOffset + x] = ((Color.red(p) / 255.0f) - MEAN[0]) / STD[0]
                        canvas1[rowOffset + x] = ((Color.green(p) / 255.0f) - MEAN[1]) / STD[1]
                        canvas2[rowOffset + x] = ((Color.blue(p) / 255.0f) - MEAN[2]) / STD[2]
                    }
                }
            } finally {
                if (scaled !== region) scaled.recycle()
            }

            val pArr = FloatArray(BUDGET * FEAT)
            val rArr = LongArray(BUDGET)
            val cArr = LongArray(BUDGET)
            val vArr = Array(1) { BooleanArray(BUDGET) }

            val patchPixels = PATCH * PATCH // 196
            for (gh in 0 until grid.gridH) {
                for (gw in 0 until grid.gridW) {
                    val idx = gh * grid.gridW + gw
                    if (idx < BUDGET) {
                        rArr[idx] = gh.toLong()
                        cArr[idx] = gw.toLong()
                        vArr[0][idx] = true

                        val pOffset = idx * FEAT
                        for (py in 0 until PATCH) {
                            val canvasRow = (gh * PATCH + py) * canvasW + (gw * PATCH)
                            val pyOffset = py * PATCH
                            for (px in 0 until PATCH) {
                                val cIdx = canvasRow + px
                                pArr[pOffset + pyOffset + px] = canvas0[cIdx]
                                pArr[pOffset + patchPixels + pyOffset + px] = canvas1[cIdx]
                                pArr[pOffset + 2 * patchPixels + pyOffset + px] = canvas2[cIdx]
                            }
                        }
                    }
                }
            }

            val env = OrtEnvironment.getEnvironment()
            val encInputs = mapOf(
                "P" to OnnxTensor.createTensor(env, FloatBuffer.wrap(pArr), longArrayOf(1L, BUDGET.toLong(), FEAT.toLong())),
                "V" to OnnxTensor.createTensor(env, vArr),
                "R" to OnnxTensor.createTensor(env, LongBuffer.wrap(rArr), longArrayOf(1L, BUDGET.toLong())),
                "C" to OnnxTensor.createTensor(env, LongBuffer.wrap(cArr), longArrayOf(1L, BUDGET.toLong())),
                "H" to OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(grid.gridH.toLong())), longArrayOf(1L)),
                "W" to OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(grid.gridW.toLong())), longArrayOf(1L))
            )
            val encResult = try {
                encSess.run(encInputs)
            } finally {
                encInputs.values.forEach { it.close() }
            }

            val mk = encResult.get("mk").orElse(null) as? OnnxTensor
            val mv = encResult.get("mv").orElse(null) as? OnnxTensor
            val km = encResult.get("km").orElse(null) as? OnnxTensor

            if (mk == null || mv == null || km == null) {
                encResult.close()
                return null
            }

            var currentTok = bosTokenId
            var pastK = OnnxTensor.createTensor(env, FloatBuffer.allocate(0), longArrayOf(1L, 12L, 0L, 64L))
            var pastV = OnnxTensor.createTensor(env, FloatBuffer.allocate(0), longArrayOf(1L, 12L, 0L, 64L))

            val tokens = ArrayList<Int>()
            val confidences = ArrayList<Float>()
            var prevResult: OrtSession.Result? = null

            try {
                for (t in 0 until MAX_DECODE_STEPS) {
                    val tokTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(currentTok)), longArrayOf(1L))
                    val tTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(t.toLong())), longArrayOf(1L))

                    val decInputs = mapOf(
                        "tok" to tokTensor,
                        "t" to tTensor,
                        "mk" to mk,
                        "mv" to mv,
                        "km" to km,
                        "past_k" to pastK,
                        "past_v" to pastV
                    )
                    val decResult = try {
                        decSess.run(decInputs)
                    } finally {
                        tokTensor.close()
                        tTensor.close()
                    }

                    if (t == 0) {
                        pastK.close()
                        pastV.close()
                    }
                    prevResult?.close()

                    val nextTokTensor = decResult.get("next_tok").orElse(null) as? OnnxTensor
                    val confTensor = decResult.get("conf").orElse(null) as? OnnxTensor
                    val newPastK = decResult.get("new_past_k").orElse(null) as? OnnxTensor
                    val newPastV = decResult.get("new_past_v").orElse(null) as? OnnxTensor

                    if (nextTokTensor == null || confTensor == null || newPastK == null || newPastV == null) {
                        decResult.close()
                        prevResult = null
                        break
                    }

                    val nextTokVal = when (val v = nextTokTensor.value) {
                        is LongArray -> v[0]
                        is Array<*> -> (v[0] as? LongArray)?.get(0)
                        else -> null
                    }
                    if (nextTokVal == null) {
                        decResult.close()
                        prevResult = null
                        break
                    }
                    val confVal = when (val v = confTensor.value) {
                        is FloatArray -> v[0]
                        is Array<*> -> (v[0] as? FloatArray)?.get(0)
                        else -> null
                    }
                    if (confVal == null) {
                        decResult.close()
                        prevResult = null
                        break
                    }

                    nextTokTensor.close()
                    confTensor.close()

                    if (nextTokVal == eosTokenId) {
                        decResult.close()
                        prevResult = null
                        break
                    }

                    tokens.add(nextTokVal.toInt())
                    confidences.add(confVal)
                    currentTok = nextTokVal
                    pastK = newPastK
                    pastV = newPastV
                    prevResult = decResult
                }
            } finally {
                prevResult?.close()
                encResult.close()
            }

            val sb = StringBuilder()
            for (id in tokens) {
                if (id in characters.indices) {
                    sb.append(characters[id])
                }
            }
            val text = sb.toString().trim()
            if (text.isEmpty()) return null

            val meanConf = if (confidences.isNotEmpty()) confidences.average().toFloat() else 1.0f
            OcrResult(
                text = text,
                confidence = meanConf,
                boundingBox = floatArrayOf(
                    0f,
                    0f,
                    region.width.toFloat(),
                    region.height.toFloat()
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "ArOcrEngine extractText failed", e)
            null
        }
    }

    @Synchronized
    fun release() {
        try { encoderSession?.close() } catch (e: Exception) { Log.w(TAG, "Error closing encoderSession", e) }
        try { decoderSession?.close() } catch (e: Exception) { Log.w(TAG, "Error closing decoderSession", e) }
        encoderSession = null
        decoderSession = null
        characters = emptyList()
    }
}
