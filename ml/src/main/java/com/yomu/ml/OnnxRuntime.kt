package com.yomu.ml

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.io.File
import java.nio.FloatBuffer

data class LayoutDetection(
    val classId: Int, // 0 = panel, 1 = text
    val confidence: Float,
    val bbox: FloatArray // [x1, y1, x2, y2]
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as LayoutDetection

        if (classId != other.classId) return false
        if (confidence != other.confidence) return false
        if (!bbox.contentEquals(other.bbox)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = classId
        result = 31 * result + confidence.hashCode()
        result = 31 * result + bbox.contentHashCode()
        return result
    }
}

class OnnxRuntime(private val context: Context) {

    private val loadedModels = mutableMapOf<String, OrtSession>()
    private var ortEnv: OrtEnvironment? = null

    companion object {
        private const val TAG = "OnnxRuntime"
        private const val YOLO_INPUT_SIZE = 1024
        private const val YOLO_FIELDS_PER_DETECTION = 6
    }

    data class Detection(
        val label: String,
        val confidence: Float,
        val bbox: FloatArray
    )

    fun init() {
        if (ortEnv == null) {
            ortEnv = OrtEnvironment.getEnvironment()
        }
    }

    fun loadModel(modelPath: String): Boolean {
        val modelFile = File(modelPath)
        if (!modelFile.exists()) return false

        return try {
            init()
            val env = ortEnv ?: return false
            val opts = OrtSession.SessionOptions()
            val session = env.createSession(modelFile.absolutePath, opts)
            loadedModels[modelPath] = session
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load model at $modelPath: ${e.message}", e)
            false
        }
    }

    fun isModelLoaded(modelName: String): Boolean {
        return loadedModels.containsKey(modelName)
    }

    fun runInference(modelName: String, input: FloatArray, inputShape: LongArray): FloatArray? {
        val session = loadedModels[modelName] ?: return null
        val env = ortEnv ?: return null

        return try {
            val inputTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(input), inputShape)
            try {
                val result = session.run(mapOf("images" to inputTensor))
                try {
                    val output = result.get("output0").orElse(null) as? OnnxTensor
                    output?.let { readFloatBuffer(it.getFloatBuffer()) }
                } finally {
                    result.close()
                }
            } finally {
                inputTensor.close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "runInference failed for $modelName: ${e.message}", e)
            null
        }
    }

    fun runInferenceNamed(
        modelPath: String,
        inputs: Map<String, FloatArray>,
        inputShapes: Map<String, LongArray>
    ): Map<String, FloatArray>? {
        val session = loadedModels[modelPath] ?: return null
        val env = ortEnv ?: return null
        val inputTensors = mutableMapOf<String, OnnxTensor>()

        return try {
            for ((name, data) in inputs) {
                val shape = inputShapes[name] ?: return null
                inputTensors[name] = OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape)
            }
            val result = session.run(inputTensors)
            try {
                readOutputs(session, result)
            } finally {
                result.close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "runInferenceNamed failed for $modelPath: ${e.message}", e)
            null
        } finally {
            closeTensors(inputTensors.values)
        }
    }

    /** Starts a [PastKeyValueDecode] of [modelPath] over this encoder output; null when the model is not loaded. */
    fun startPastKeyValueDecode(modelPath: String, encoderHiddenStates: FloatArray, shape: LongArray): PastKeyValueDecode? {
        val session = loadedModels[modelPath] ?: return null
        val env = ortEnv ?: return null
        return try {
            PastKeyValueDecode(env, session, OnnxTensor.createTensor(env, FloatBuffer.wrap(encoderHiddenStates), shape))
        } catch (e: Exception) {
            Log.e(TAG, "startPastKeyValueDecode failed for $modelPath: ${e.message}", e)
            null
        }
    }

    fun getInputNames(modelPath: String): List<String> {
        return loadedModels[modelPath]?.getInputNames()?.toList() ?: emptyList()
    }

    fun getInputShape(modelPath: String, inputName: String): LongArray? {
        val session = loadedModels[modelPath] ?: return null
        val info = session.getInputInfo()[inputName] ?: return null
        val tensorInfo = info.info as? TensorInfo ?: return null
        return tensorInfo.shape
    }

    fun getOutputNames(modelPath: String): List<String> {
        return loadedModels[modelPath]?.getOutputNames()?.toList() ?: emptyList()
    }

    fun getOutputShape(modelPath: String, outputName: String): LongArray? {
        val session = loadedModels[modelPath] ?: return null
        val info = session.getOutputInfo()[outputName] ?: return null
        val tensorInfo = info.info as? TensorInfo ?: return null
        return tensorInfo.shape
    }

    fun runLayoutInference(
        modelName: String,
        bitmap: Bitmap,
        confidenceThreshold: Float = 0.25f,
        iouThreshold: Float = 0.45f
    ): List<LayoutDetection> {
        val session = loadedModels[modelName] ?: return emptyList()
        val env = ortEnv ?: return emptyList()

        return try {
            val letterbox = Letterbox(bitmap.width, bitmap.height, YOLO_INPUT_SIZE, fixedSquare = true)
            val resized = Bitmap.createScaledBitmap(bitmap, letterbox.scaledWidth, letterbox.scaledHeight, true)
            val pixels = IntArray(letterbox.inputWidth * letterbox.inputHeight) { Letterbox.PAD_COLOR }
            resized.getPixels(
                pixels,
                letterbox.padY * letterbox.inputWidth + letterbox.padX,
                letterbox.inputWidth,
                0,
                0,
                letterbox.scaledWidth,
                letterbox.scaledHeight
            )
            val floatArray = bitmapPixelsToCHW(pixels, letterbox.inputWidth, letterbox.inputHeight)

            val shape = longArrayOf(1, 3, letterbox.inputHeight.toLong(), letterbox.inputWidth.toLong())
            val inputTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArray), shape)
            try {
                val inputName = session.getInputNames().first()
                val result = session.run(mapOf(inputName to inputTensor))
                try {
                    val outputName = session.getOutputNames().first()
                    val output = result.get(outputName).orElse(null) as? OnnxTensor

                    if (output != null) {
                        val outputArray = readFloatBuffer(output.getFloatBuffer())
                        val numAnchors = outputArray.size / YOLO_FIELDS_PER_DETECTION
                        val detections = parseDenseYoloLayoutDetections(
                            output = outputArray,
                            numAnchors = numAnchors,
                            confThreshold = confidenceThreshold,
                            iouThreshold = iouThreshold
                        )
                        detections.map { it.copy(bbox = letterbox.toSource(it.bbox)) }
                    } else {
                        Log.e(TAG, "YOLO output tensor was null for model $modelName")
                        emptyList()
                    }
                } finally {
                    result.close()
                }
            } finally {
                inputTensor.close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "runLayoutInference failed for $modelName: ${e.message}", e)
            emptyList()
        }
    }

    fun runBitmapInference(
        modelName: String,
        bitmap: Bitmap,
        confidenceThreshold: Float
    ): List<Detection> {
        return runLayoutInference(modelName, bitmap, confidenceThreshold)
            .filter { it.classId == 1 }
            .map { Detection(label = "bubble", confidence = it.confidence, bbox = it.bbox) }
    }

    private fun readOutputs(
        session: OrtSession,
        result: OrtSession.Result
    ): Map<String, FloatArray> {
        val outputs = mutableMapOf<String, FloatArray>()
        for (name in session.getOutputNames()) {
            val tensor = result.get(name).orElse(null) as? OnnxTensor ?: continue
            outputs[name] = readFloatBuffer(tensor.getFloatBuffer())
        }
        return outputs
    }

    fun releaseModel(modelName: String) {
        loadedModels.remove(modelName)?.close()
    }

    fun release() {
        for ((_, session) in loadedModels) {
            session.close()
        }
        loadedModels.clear()
        ortEnv?.close()
        ortEnv = null
    }

    private fun closeTensors(tensors: Collection<OnnxTensor>) {
        for (tensor in tensors) {
            try {
                tensor.close()
            } catch (_: Exception) {
            }
        }
    }
}

internal fun readFloatBuffer(buffer: FloatBuffer): FloatArray {
    val copy = buffer.duplicate()
    copy.rewind()
    val output = FloatArray(copy.remaining())
    copy.get(output)
    return output
}

internal fun bitmapPixelsToCHW(pixels: IntArray, width: Int, height: Int): FloatArray {
    val planeSize = width * height
    require(pixels.size == planeSize) {
        "pixels size ${pixels.size} must equal width*height ($planeSize)"
    }

    val floatArray = FloatArray(planeSize * 3)
    for (i in pixels.indices) {
        val pixel = pixels[i]
        floatArray[i] = ((pixel shr 16) and 0xFF) / 255f
        floatArray[planeSize + i] = ((pixel shr 8) and 0xFF) / 255f
        floatArray[2 * planeSize + i] = (pixel and 0xFF) / 255f
    }
    return floatArray
}

internal fun parseDenseYoloLayoutDetections(
    output: FloatArray,
    numAnchors: Int,
    confThreshold: Float = 0.25f,
    iouThreshold: Float = 0.45f
): List<LayoutDetection> {
    if (numAnchors <= 0 || output.size < numAnchors * 6) {
        return emptyList()
    }

    // Check if output is already End2End [x1, y1, x2, y2, conf, classId]
    // where candidate detections above confidence threshold have integer classId (0.0 or 1.0)
    // and valid x2 >= x1, y2 >= y1 boundaries.
    val isEnd2End = (0 until numAnchors).any { i ->
        val base = i * 6
        val conf = output[base + 4]
        val classVal = output[base + 5]
        conf >= confThreshold && (classVal == 0f || classVal == 1f) &&
            output[base + 2] >= output[base] && output[base + 3] >= output[base + 1]
    } && (0 until numAnchors).none { i ->
        val base = i * 6
        val conf = output[base + 4]
        val classVal = output[base + 5]
        conf >= confThreshold && classVal != 0f && classVal != 1f
    }

    val candidates = mutableListOf<LayoutDetection>()
    for (i in 0 until numAnchors) {
        val base = i * 6
        if (isEnd2End) {
            val conf = output[base + 4]
            if (conf >= confThreshold) {
                val classId = output[base + 5].toInt()
                val bbox = floatArrayOf(
                    output[base],
                    output[base + 1],
                    output[base + 2],
                    output[base + 3]
                )
                candidates.add(LayoutDetection(classId, conf, bbox))
            }
        } else {
            val score0 = output[base + 4]
            val score1 = output[base + 5]
            val (classId, conf) = if (score1 > score0) 1 to score1 else 0 to score0
            if (conf >= confThreshold) {
                val xc = output[base]
                val yc = output[base + 1]
                val w = output[base + 2]
                val h = output[base + 3]
                val bbox = floatArrayOf(
                    xc - w / 2f,
                    yc - h / 2f,
                    xc + w / 2f,
                    yc + h / 2f
                )
                candidates.add(LayoutDetection(classId, conf, bbox))
            }
        }
    }

    val panels = nmsForClass(candidates.filter { it.classId == 0 }, iouThreshold)
    val text = nmsForClass(candidates.filter { it.classId == 1 }, iouThreshold)

    return (panels + text).sortedByDescending { it.confidence }
}

private fun nmsForClass(
    detections: List<LayoutDetection>,
    iouThreshold: Float
): List<LayoutDetection> {
    if (detections.isEmpty()) return emptyList()
    val sorted = detections.sortedByDescending { it.confidence }
    val kept = mutableListOf<LayoutDetection>()

    for (candidate in sorted) {
        var suppressed = false
        for (selected in kept) {
            if (calculateIou(candidate.bbox, selected.bbox) >= iouThreshold) {
                suppressed = true
                break
            }
        }
        if (!suppressed) {
            kept.add(candidate)
        }
    }
    return kept
}

internal fun calculateIou(boxA: FloatArray, boxB: FloatArray): Float {
    val x1 = maxOf(boxA[0], boxB[0])
    val y1 = maxOf(boxA[1], boxB[1])
    val x2 = minOf(boxA[2], boxB[2])
    val y2 = minOf(boxA[3], boxB[3])

    val intersection = maxOf(0f, x2 - x1) * maxOf(0f, y2 - y1)
    if (intersection <= 0f) return 0f

    val areaA = maxOf(0f, boxA[2] - boxA[0]) * maxOf(0f, boxA[3] - boxA[1])
    val areaB = maxOf(0f, boxB[2] - boxB[0]) * maxOf(0f, boxB[3] - boxB[1])
    val union = areaA + areaB - intersection
    return if (union > 0f) intersection / union else 0f
}

internal fun parseYoloDetections(
    output: FloatArray,
    fieldsPerDetection: Int,
    confidenceThreshold: Float
): List<OnnxRuntime.Detection> {
    if (fieldsPerDetection <= 0 || output.size % fieldsPerDetection != 0) {
        return emptyList()
    }

    val detections = mutableListOf<OnnxRuntime.Detection>()
    val numDetections = output.size / fieldsPerDetection
    for (i in 0 until numDetections) {
        val base = i * fieldsPerDetection
        val confidence = output[base + 4]
        val classId = output[base + 5].toInt()
        if (confidence >= confidenceThreshold && classId == 0) {
            detections.add(
                OnnxRuntime.Detection(
                    label = "bubble",
                    confidence = confidence,
                    bbox = floatArrayOf(
                        output[base],
                        output[base + 1],
                        output[base + 2],
                        output[base + 3]
                    )
                )
            )
        }
    }
    return detections.sortedByDescending { it.confidence }
}
