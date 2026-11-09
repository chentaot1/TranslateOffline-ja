package com.yomu.ml

import com.yomu.core.GenerationParams

/** Replays each successful reply to the partial-output listener in [chunkBytes]-byte chunks, as the decode loop would. */
internal class FakeLlamaBridge(
    private val chunkBytes: Int = 1,
    private val resultForPrompt: (String) -> GenerationResult
) : LlamaBridge(null) {
    val prompts = mutableListOf<String>()
    val grammars = mutableListOf<String>()
    val maxTokens = mutableListOf<Int>()
    val params = mutableListOf<GenerationParams>()
    val systemMessages = mutableListOf<String>()
    var releaseCalls = 0
    var clearMemoryCalls = 0
    val abortRequests = mutableListOf<Boolean>()
    /** Context tokens and threads of every native load, in order. */
    val loads = mutableListOf<Pair<Int, Int>>()
    /** Whether each native load was allowed to repack the weights, in order. */
    val repacks = mutableListOf<Boolean>()
    /** True only while [generate] runs, so a test can tell a report made mid-reply from one made after it. */
    var generating = false
        private set
    private var loaded = true

    override val isNativeAvailable: Boolean get() = true
    override val isModelLoaded: Boolean get() = loaded
    override fun loadModel(modelPath: String, nCtx: Int, nGpuLayers: Int): Boolean = true
    override fun loadModel(modelPath: String, nCtx: Int, nGpuLayers: Int, nThreads: Int, repackWeights: Boolean): Boolean {
        loads += nCtx to nThreads
        repacks += repackWeights
        loaded = true
        return true
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
        prompts += prompt
        grammars += grammar
        systemMessages += systemMessage
        this.maxTokens += maxTokens
        this.params += params
        val result = resultForPrompt(prompt)
        generating = true
        try {
            if (result is GenerationResult.Success && onPartial != null) {
                result.text.toByteArray(Charsets.UTF_8).asList().chunked(chunkBytes).forEach { onPartial(it.toByteArray()) }
            }
        } finally {
            generating = false
        }
        return result
    }

    override fun setAbortRequested(requested: Boolean) {
        abortRequests += requested
    }

    override fun release() {
        releaseCalls++
        loaded = false
    }

    override fun clearMemory() {
        clearMemoryCalls++
    }
}
