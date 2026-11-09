package com.yomu.ml

import com.yomu.core.GenerationParams

sealed class GenerationResult {
    abstract val durationMs: Long

    /**
     * [endedByModel] is true only when the model closed the reply with end-of-generation, so its last
     * line is whole; a reply the deadline or the token cap cut off can stop mid-line.
     */
    data class Success(
        val text: String,
        override val durationMs: Long,
        val endedByModel: Boolean = false
    ) : GenerationResult()

    data class NotLoaded(
        override val durationMs: Long
    ) : GenerationResult()

    data class Blank(
        override val durationMs: Long
    ) : GenerationResult()

    /**
     * The prompt did not fit the decode budget, typed at `llama_jni.cpp` rather than inferred from
     * an empty reply (#149). The page-level batch path falls back to per-line on this and only on
     * this: falling back on any blank reply would retry every empty completion as N calls.
     */
    data class Overflow(
        override val durationMs: Long
    ) : GenerationResult()

    /** The deadline fired inside `llama_decode` before any text came back. */
    data class Timeout(
        override val durationMs: Long
    ) : GenerationResult()

    data class Error(
        val reason: String,
        override val durationMs: Long
    ) : GenerationResult()
}

interface TextGenerationBridge {
    companion object {
        const val DEFAULT_TIMEOUT_MS = 60_000
    }

    val isNativeAvailable: Boolean
    val isModelLoaded: Boolean

    fun loadModel(modelPath: String, nCtx: Int = 2048, nGpuLayers: Int = 0): Boolean
    /**
     * [grammar] is GBNF constraining the sampler; empty means unconstrained.
     * [systemMessage] is the deliverable's own system turn (#287); empty sends none.
     * [onPartial] hears the reply's bytes as each token is decoded, so a chunk may end mid-character;
     * the returned result is the same with or without it.
     */
    fun generate(
        prompt: String,
        params: GenerationParams = GenerationParams(),
        maxTokens: Int = params.maxTokens,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
        grammar: String = "",
        systemMessage: String = "",
        onPartial: ((ByteArray) -> Unit)? = null
    ): GenerationResult
    fun release()
}
