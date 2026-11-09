package com.yomu.ml

import android.content.Context
import android.util.Log
import com.yomu.core.GenerationParams
import java.io.File

open class LlamaBridge(private val context: Context?) : TextGenerationBridge {

    companion object {
        private const val TAG = "LlamaBridge"
        private val DEFAULT_N_THREADS = Runtime.getRuntime().availableProcessors().coerceAtMost(4)
        private var nativeLoaded = false

        // Mirrors GenerationStatus in llama_jni.cpp; the two must be edited together.
        private const val STATUS_OVERFLOW = 1
        private const val STATUS_TIMEOUT = 2
        private const val STATUS_ENDED = 3

        init {
            try {
                System.loadLibrary("llama_jni")
                nativeLoaded = true
            } catch (e: UnsatisfiedLinkError) {
                nativeLoaded = false
            }
        }
    }

    private var isLoaded = false

    override val isNativeAvailable: Boolean get() = nativeLoaded

    override val isModelLoaded: Boolean get() = isLoaded

    fun loadModel(modelPath: String): Boolean {
        return loadModel(modelPath, nCtx = 2048, nGpuLayers = 0)
    }

    override fun loadModel(modelPath: String, nCtx: Int, nGpuLayers: Int): Boolean {
        return loadModel(modelPath, nCtx, nGpuLayers, DEFAULT_N_THREADS, repackWeights = true)
    }

    open fun loadModel(modelPath: String, nCtx: Int, nGpuLayers: Int, nThreads: Int, repackWeights: Boolean): Boolean {
        if (!nativeLoaded) {
            Log.w(TAG, "loadModel skipped native_unavailable")
            return false
        }

        val modelFile = File(modelPath)
        if (!modelFile.exists()) {
            Log.w(TAG, "loadModel skipped model_missing")
            return false
        }

        isLoaded = nativeLoadModel(
            modelFile.absolutePath,
            nCtx,
            nGpuLayers,
            nThreads,
            repackWeights
        )
        Log.i(TAG, "loadModel completed loaded=$isLoaded repackWeights=$repackWeights")
        return isLoaded
    }

    open fun clearMemory() {
        if (isLoaded) {
            nativeClearMemory()
        }
    }

    /** Raised mid-[generate] from another thread to stop it at the next decode step; cleared by the caller (#76). */
    open fun setAbortRequested(requested: Boolean) {
        if (nativeLoaded) {
            nativeSetAbortRequested(requested)
        }
    }

    override fun generate(
        prompt: String,
        params: GenerationParams,
        maxTokens: Int,
        timeoutMs: Int,
        grammar: String,
        systemMessage: String,
        onPartial: ((ByteArray) -> Unit)?
    ): GenerationResult {
        if (!isLoaded) {
            Log.w(TAG, "generate skipped model_not_loaded")
            return GenerationResult.NotLoaded(durationMs = 0L)
        }
        val startMs = System.currentTimeMillis()
        try {
            val result = nativeGenerate(
                prompt,
                maxTokens,
                timeoutMs,
                params.samplerArray(),
                params.seed,
                grammar,
                systemMessage,
                onPartial
            )
            val durationMs = System.currentTimeMillis() - startMs
            val text = decodeGenerated(result)
            val status = nativeLastStatus()
            if (text.isBlank()) {
                Log.w(TAG, "generate completed status=empty native=$status durationMs=$durationMs")
                return when (status) {
                    STATUS_OVERFLOW -> GenerationResult.Overflow(durationMs = durationMs)
                    STATUS_TIMEOUT -> GenerationResult.Timeout(durationMs = durationMs)
                    else -> GenerationResult.Blank(durationMs = durationMs)
                }
            }
            val endedByModel = status == STATUS_ENDED
            Log.i(
                TAG,
                "generate completed status=success durationMs=$durationMs responseLength=${text.length} " +
                    "endedByModel=$endedByModel"
            )
            return GenerationResult.Success(text = text, durationMs = durationMs, endedByModel = endedByModel)
        } catch (e: Exception) {
            val durationMs = System.currentTimeMillis() - startMs
            val reason = e::class.simpleName ?: "Exception"
            Log.e(TAG, "generate completed status=error durationMs=$durationMs reason=$reason")
            return GenerationResult.Error(reason = reason, durationMs = durationMs)
        }
    }

    override fun release() {
        if (isLoaded) {
            nativeRelease()
            isLoaded = false
        }
        Log.i(TAG, "release completed")
    }

    private external fun nativeLoadModel(path: String, nCtx: Int, nGpuLayers: Int, nThreads: Int, repackWeights: Boolean): Boolean
    // samplerParams carries the sampler knobs in GenerationParams.SAMPLER_INDEX order; seed is a
    // separate Int because 0xFFFFFFFF (LLAMA_DEFAULT_SEED) has no exact Float representation.
    private external fun nativeGenerate(
        prompt: String,
        maxTokens: Int,
        timeoutMs: Int,
        samplerParams: FloatArray,
        seed: Int,
        grammar: String,
        systemMessage: String,
        onPartial: ((ByteArray) -> Unit)?
    ): ByteArray?
    private external fun nativeSetAbortRequested(requested: Boolean)
    private external fun nativeLastStatus(): Int
    private external fun nativeClearMemory()
    private external fun nativeRelease()
}

// Malformed or truncated UTF-8 from the model decodes to U+FFFD instead of aborting (#235).
internal fun decodeGenerated(bytes: ByteArray?): String = bytes?.toString(Charsets.UTF_8).orEmpty()

