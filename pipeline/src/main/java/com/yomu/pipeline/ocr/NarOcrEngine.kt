package com.yomu.pipeline.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.nio.FloatBuffer
import java.nio.IntBuffer
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Non-autoregressive (NAR) single-pass manga OCR engine based on muscgab/manga-ocr-nar-small.
 * Decodes all token position slots concurrently in a single forward pass (~100-180ms),
 * bypassing multi-step autoregressive ViT-BERT decoding loops.
 */
class NarOcrEngine {

    companion object {
        private const val TAG = "NarOcrEngine"
        private const val BUDGET = 324
        private const val PATCH = 14
        private const val FEAT = 588 // 14 * 14 * 3
        private val MEAN = floatArrayOf(0.48145466f, 0.4578275f, 0.40821073f)
        private val STD = floatArrayOf(0.26862954f, 0.26130258f, 0.27577711f)
    }

    private var session: OrtSession? = null
    private var modelPath: String? = null
    private var characters: List<String> = emptyList()
    private var eosTokenId: Int = 6817
    private var needsFixed324: Boolean = false
    private var hasV: Boolean = false

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
    fun loadModel(modelPath: String, vocabPath: String): Boolean {
        release()
        if (!File(modelPath).isFile || !File(vocabPath).isFile) return false
        return try {
            val vocabText = File(vocabPath).readText(Charsets.UTF_8)
            val root = JSONObject(vocabText)
            val arr = root.getJSONArray("characters")
            val loaded = (0 until arr.length()).map { arr.getString(it) }
            require(loaded.isNotEmpty())
            characters = loaded
            eosTokenId = loaded.size

            val env = OrtEnvironment.getEnvironment()
            val sess = OrtSession.SessionOptions().use { options ->
                options.setIntraOpNumThreads(4)
                options.setInterOpNumThreads(1)
                env.createSession(modelPath, options)
            }
            var fixed324 = false
            val pInfo = sess.inputInfo["P"]
            if (pInfo?.info is TensorInfo) {
                val shape = (pInfo.info as TensorInfo).shape
                if (shape.isNotEmpty() && shape[0] == 324L) {
                    fixed324 = true
                }
            }
            needsFixed324 = fixed324
            hasV = sess.inputNames.contains("V")
            session = sess
            this.modelPath = modelPath
            Log.i(TAG, "NarOcrEngine loaded: model=$modelPath vocab=${characters.size} eos=$eosTokenId fixed324=$needsFixed324 hasV=$hasV")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load NAR OCR model", e)
            release()
            false
        }
    }

    fun isModelLoaded(): Boolean = session != null && characters.isNotEmpty()

    @Synchronized
    fun extractText(region: Bitmap): OcrResult? {
        val sess = session ?: return null
        if (region.isRecycled || region.width <= 0 || region.height <= 0) return null

        return try {
            val grid = computeGrid(region.width, region.height)
            val scaled = Bitmap.createScaledBitmap(region, grid.resizedW, grid.resizedH, true)

            val canvas0: FloatArray
            val canvas1: FloatArray
            val canvas2: FloatArray
            val canvasW = grid.gridW * PATCH
            try {
                // Sample borders to find the median background color, ignoring dark reader background/gutter pixels (< 128)
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

            val n = grid.gridH * grid.gridW
            val targetN = if (needsFixed324) 324 else n
            val pArr = FloatArray(targetN * FEAT)
            val rArr = IntArray(targetN)
            val cArr = IntArray(targetN)

            val patchPixels = PATCH * PATCH // 196
            for (gh in 0 until grid.gridH) {
                for (gw in 0 until grid.gridW) {
                    val idx = gh * grid.gridW + gw
                    rArr[idx] = gh
                    cArr[idx] = gw
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

            val env = OrtEnvironment.getEnvironment()
            val inputs = mutableMapOf<String, OnnxTensor>(
                "P" to OnnxTensor.createTensor(env, FloatBuffer.wrap(pArr), longArrayOf(targetN.toLong(), FEAT.toLong())),
                "R" to OnnxTensor.createTensor(env, IntBuffer.wrap(rArr), longArrayOf(targetN.toLong())),
                "C" to OnnxTensor.createTensor(env, IntBuffer.wrap(cArr), longArrayOf(targetN.toLong())),
                "H" to OnnxTensor.createTensor(env, IntBuffer.wrap(intArrayOf(grid.gridH)), longArrayOf(1L)),
                "W" to OnnxTensor.createTensor(env, IntBuffer.wrap(intArrayOf(grid.gridW)), longArrayOf(1L))
            )
            if (hasV) {
                val vArr = FloatArray(targetN)
                for (i in 0 until n) vArr[i] = 1.0f
                inputs["V"] = OnnxTensor.createTensor(env, FloatBuffer.wrap(vArr), longArrayOf(targetN.toLong()))
            }

            try {
                val result = sess.run(inputs)
                try {
                    val idsTensor = result.get("ids").orElse(null) as? OnnxTensor ?: return null
                    val ids = when (val v = idsTensor.value) {
                        is IntArray -> v
                        is Array<*> -> (v.firstOrNull() as? IntArray) ?: return null
                        is LongArray -> IntArray(v.size) { v[it].toInt() }
                        else -> return null
                    }
                    val sb = StringBuilder()
                    for (id in ids) {
                        if (id == eosTokenId) break
                        if (id in characters.indices) {
                            sb.append(characters[id])
                        }
                    }
                    val text = sb.toString().trim()
                    if (text.isEmpty()) return null
                    OcrResult(
                        text = text,
                        confidence = 1.0f,
                        boundingBox = floatArrayOf(
                            0f,
                            0f,
                            region.width.toFloat(),
                            region.height.toFloat()
                        )
                    )
                } finally {
                    result.close()
                }
            } finally {
                inputs.values.forEach { it.close() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "extractText failed", e)
            null
        }
    }

    @Synchronized
    fun release() {
        try {
            session?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing session", e)
        }
        session = null
        modelPath = null
        characters = emptyList()
        needsFixed324 = false
        hasV = false
    }
}
