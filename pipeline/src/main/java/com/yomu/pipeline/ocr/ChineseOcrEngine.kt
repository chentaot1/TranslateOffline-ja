package com.yomu.pipeline.ocr

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import org.json.JSONArray
import java.io.File
import java.nio.FloatBuffer

/** Two-stage PP-OCRv6 Chinese OCR. Calls are serialized with release to protect native sessions. */
class ChineseOcrEngine {
    private var detector: OrtSession? = null
    private var detectorModelPath: String? = null
    private var recognizer: OrtSession? = null
    private var characters: List<String> = emptyList()

    /** Loads pinned detector/recognizer graphs and a JSON array derived from the recognizer's character_dict. */
    @Synchronized
    fun loadModel(detectorPath: String, recognizerPath: String, dictionaryPath: String): Boolean {
        release()
        if (!listOf(detectorPath, recognizerPath, dictionaryPath).all { File(it).isFile }) return false
        return try {
            val dictionary = JSONArray(File(dictionaryPath).readText(Charsets.UTF_8))
            val loadedCharacters = (0 until dictionary.length()).map { dictionary.getString(it) }
            require(loadedCharacters.isNotEmpty() && loadedCharacters.none { it.isEmpty() })
            val environment = OrtEnvironment.getEnvironment()
            OrtSession.SessionOptions().use { options ->
                options.setIntraOpNumThreads(2)
                detector = environment.createSession(detectorPath, options)
                recognizer = environment.createSession(recognizerPath, options)
            }
            validateDetector(requireNotNull(detector))
            validateRecognizer(requireNotNull(recognizer), loadedCharacters.size + 1)
            detectorModelPath = detectorPath
            characters = loadedCharacters
            true
        } catch (error: Exception) {
            Log.e("ChineseOcrEngine", "Chinese OCR model contract could not be loaded", error)
            release()
            false
        }
    }

    /** Loads detection alone, preserving an already loaded recognizer and reusing the same detector path. */
    @Synchronized
    fun loadDetector(path: String): Boolean {
        if (detector != null && detectorModelPath == path) return true
        if (!File(path).isFile) return false
        return try {
            val loaded = OrtSession.SessionOptions().use { options ->
                options.setIntraOpNumThreads(2)
                OrtEnvironment.getEnvironment().createSession(path, options)
            }
            try {
                validateDetector(loaded)
            } catch (error: Exception) {
                closeSession(loaded)
                throw error
            }
            val previous = detector
            detector = loaded
            detectorModelPath = path
            previous?.let { closeSession(it) }
            true
        } catch (error: Exception) {
            Log.e("ChineseOcrEngine", "Text detector could not be loaded", error)
            false
        }
    }

    /** Detects text lines anywhere in an image, in original-image pixels; never recycles the caller bitmap. */
    @Synchronized
    fun detectRegions(bitmap: Bitmap): List<RectF> {
        val session = detector ?: return emptyList()
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return emptyList()
        return try {
            val detected = detectLines(session, bitmap)
            detected.lines.mapNotNull { line -> chineseLineBounds(line, detected.mapWidth,
                detected.mapHeight, bitmap.width, bitmap.height) }
        } catch (error: Exception) {
            Log.e("ChineseOcrEngine", "Page text detection failed", error)
            emptyList()
        }
    }

    /** Detects and recognizes every text line in the caller-owned bubble, returning null on no read or failure. */
    @Synchronized
    fun extractText(region: Bitmap): OcrResult? {
        val detectionSession = detector ?: return null
        val recognitionSession = recognizer ?: return null
        if (region.isRecycled || region.width <= 0 || region.height <= 0) return null
        return try {
            val detected = detectLines(detectionSession, region)
            val reads = detected.lines.mapNotNull { line ->
                val crop = chineseLineCrop(region, line, detected.mapWidth, detected.mapHeight)
                try {
                    val recognition = infer(recognitionSession, chineseImageTensor(crop, true))
                    require(recognition.shape.size == 3 && recognition.shape[0] == 1L)
                    val read = decodeChineseCtc(recognition.values, recognition.shape[2].toInt(), characters)
                    read.takeIf { it.text.isNotBlank() }
                } finally {
                    crop.recycle()
                }
            }
            if (reads.isEmpty()) return null
            val characterCount = reads.sumOf { it.characterCount }
            OcrResult(
                text = reads.joinToString("\n") { it.text.trim() },
                confidence = (reads.sumOf { it.confidence.toDouble() * it.characterCount } / characterCount).toFloat(),
                boundingBox = floatArrayOf(0f, 0f, region.width.toFloat(), region.height.toFloat())
            )
        } catch (error: Exception) {
            Log.e("ChineseOcrEngine", "Chinese OCR inference failed", error)
            null
        }
    }

    /** Closes this engine's graphs; the shared ONNX environment remains owned by the runtime. */
    @Synchronized
    fun release() {
        val sessions = listOfNotNull(detector, recognizer)
        detector = null
        detectorModelPath = null
        recognizer = null
        characters = emptyList()
        sessions.forEach { closeSession(it) }
    }

    private fun closeSession(session: OrtSession) {
        try {
            session.close()
        } catch (error: Exception) {
            Log.w("ChineseOcrEngine", "Could not close OCR session", error)
        }
    }

    private data class DetectedLines(val lines: List<ChineseLine>, val mapWidth: Int, val mapHeight: Int)

    private fun detectLines(session: OrtSession, bitmap: Bitmap): DetectedLines {
        val detection = infer(session, chineseImageTensor(bitmap, false))
        require(detection.shape.size == 4 && detection.shape[0] == 1L && detection.shape[1] == 1L)
        val height = detection.shape[2].toInt()
        val width = detection.shape[3].toInt()
        return DetectedLines(detectChineseLines(detection.values, width, height), width, height)
    }

    private fun validateImageInput(session: OrtSession): TensorInfo {
        require(session.inputInfo.size == 1 && session.outputInfo.size == 1)
        val info = session.inputInfo.values.single().info as? TensorInfo
            ?: error("OCR input must be a tensor")
        require(info.type == OnnxJavaType.FLOAT && info.shape.size == 4)
        require(info.shape[0] in longArrayOf(-1, 1) && info.shape[1] == 3L)
        return info
    }

    private fun outputInfo(session: OrtSession): TensorInfo {
        val output = session.outputInfo.values.single().info as? TensorInfo
            ?: error("OCR output must be a tensor")
        require(output.type == OnnxJavaType.FLOAT)
        return output
    }

    private fun validateDetector(session: OrtSession) {
        val input = validateImageInput(session)
        require(input.shape[2] < 0 && input.shape[3] < 0)
        val output = outputInfo(session)
        require(output.shape.size == 4 && output.shape[1] == 1L)
    }

    private fun validateRecognizer(session: OrtSession, classes: Int) {
        val input = validateImageInput(session)
        require(input.shape[2] == 48L && input.shape[3] < 0)
        val output = outputInfo(session)
        require(output.shape.size == 3 && output.shape[2] == classes.toLong())
    }

    private fun infer(session: OrtSession, input: ChineseImageTensor): ChineseImageTensor {
        val environment = OrtEnvironment.getEnvironment()
        OnnxTensor.createTensor(environment, FloatBuffer.wrap(input.values), input.shape).use { tensor ->
            session.run(mapOf(session.inputNames.single() to tensor)).use { result ->
                val output = result[0] as? OnnxTensor ?: error("OCR output must be a tensor")
                val buffer = output.floatBuffer.duplicate()
                buffer.rewind()
                val values = FloatArray(buffer.remaining())
                buffer.get(values)
                return ChineseImageTensor(values, output.info.shape.copyOf())
            }
        }
    }
}
