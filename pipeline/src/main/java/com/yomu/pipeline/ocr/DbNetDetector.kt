package com.yomu.pipeline.ocr

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import java.io.File
import java.nio.FloatBuffer

/** Scene/caption DBNet text region detector. Calls are serialized with release to protect native sessions. */
class DbNetDetector {
    private var detector: OrtSession? = null
    private var detectorModelPath: String? = null

    /** Loads the DBNet text detection model, reusing an already-loaded session for the same path. */
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
            Log.e("DbNetDetector", "Text detector could not be loaded", error)
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
            detected.lines.mapNotNull { line ->
                dbNetLineBounds(line, detected.mapWidth, detected.mapHeight, bitmap.width, bitmap.height)
            }
        } catch (error: Exception) {
            Log.e("DbNetDetector", "Page text detection failed", error)
            emptyList()
        }
    }

    /** Closes this detector's graph; the shared ONNX environment remains owned by the runtime. */
    @Synchronized
    fun release() {
        val active = detector
        detector = null
        detectorModelPath = null
        active?.let { closeSession(it) }
    }

    private fun closeSession(session: OrtSession) {
        try {
            session.close()
        } catch (error: Exception) {
            Log.w("DbNetDetector", "Could not close detector session", error)
        }
    }

    private data class DetectedLines(val lines: List<DbNetLine>, val mapWidth: Int, val mapHeight: Int)

    private fun detectLines(session: OrtSession, bitmap: Bitmap): DetectedLines {
        val detection = infer(session, dbNetImageTensor(bitmap))
        require(detection.shape.size == 4 && detection.shape[0] == 1L && detection.shape[1] == 1L)
        val height = detection.shape[2].toInt()
        val width = detection.shape[3].toInt()
        return DetectedLines(detectDbNetLines(detection.values, width, height), width, height)
    }

    private fun validateDetector(session: OrtSession) {
        require(session.inputInfo.size == 1 && session.outputInfo.size == 1)
        val input = session.inputInfo.values.single().info as? TensorInfo
            ?: error("Detector input must be a tensor")
        require(input.type == OnnxJavaType.FLOAT && input.shape.size == 4)
        require(input.shape[0] in longArrayOf(-1, 1) && input.shape[1] == 3L)
        require(input.shape[2] < 0 && input.shape[3] < 0)

        val output = session.outputInfo.values.single().info as? TensorInfo
            ?: error("Detector output must be a tensor")
        require(output.type == OnnxJavaType.FLOAT && output.shape.size == 4 && output.shape[1] == 1L)
    }

    private fun infer(session: OrtSession, input: DbNetImageTensor): DbNetImageTensor {
        val environment = OrtEnvironment.getEnvironment()
        OnnxTensor.createTensor(environment, FloatBuffer.wrap(input.values), input.shape).use { tensor ->
            session.run(mapOf(session.inputNames.single() to tensor)).use { result ->
                val output = result[0] as? OnnxTensor ?: error("Detector output must be a tensor")
                val buffer = output.floatBuffer.duplicate()
                buffer.rewind()
                val values = FloatArray(buffer.remaining())
                buffer.get(values)
                return DbNetImageTensor(values, output.info.shape.copyOf())
            }
        }
    }
}
