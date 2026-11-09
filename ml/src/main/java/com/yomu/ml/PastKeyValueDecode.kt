package com.yomu.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.util.Log
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * One decode of a merged decoder exported with past key values (#393): each [step] sends one token and
 * the key/values earlier steps returned, which stay in native memory between steps. Close it after the
 * decode; that frees the encoder output and every step's results.
 */
class PastKeyValueDecode internal constructor(
    private val env: OrtEnvironment,
    private val session: OrtSession,
    private val encoderHiddenStates: OnnxTensor
) : AutoCloseable {

    /** One key or value tensor a step returned. It stays owned by the decode, which closes it. */
    class State internal constructor(internal val tensor: OnnxTensor)

    /** A step's logits for the token it read, and its non-empty presents keyed by the past input they feed. */
    class Step(val logits: FloatArray, val presents: Map<String, State>)

    private val pastNames = session.inputNames.filter { it.startsWith(PAST_PREFIX) }
    // [batch, heads, sequence, head size], read from the model; the first step's pasts hold no positions yet.
    private val emptyPast = run {
        val shape = pastNames.firstOrNull()?.let { (session.inputInfo.getValue(it).info as TensorInfo).shape }
        OnnxTensor.createTensor(env, FloatBuffer.allocate(0), longArrayOf(1, shape?.get(1) ?: 0, 0, shape?.get(3) ?: 0))
    }
    private val results = mutableListOf<OrtSession.Result>()

    /** Runs [token] against [past], the presents of earlier steps; an empty [past] runs the first step. */
    fun step(token: Long, past: Map<String, State>): Step? = try {
        OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(token)), longArrayOf(1, 1)).use { inputIds ->
            OnnxTensor.createTensor(env, booleanArrayOf(past.isNotEmpty())).use { useCacheBranch ->
                val inputs = mapOf(
                    "input_ids" to inputIds,
                    "encoder_hidden_states" to encoderHiddenStates,
                    "use_cache_branch" to useCacheBranch
                ) + pastNames.associateWith { past[it]?.tensor ?: emptyPast }
                val result = session.run(inputs).also { results += it }
                val logits = readFloatBuffer((result.get("logits").get() as OnnxTensor).floatBuffer)
                // The cache branch returns the cross-attention presents empty; the caller keeps the first step's.
                val presents = result.filter { it.key.startsWith(PRESENT_PREFIX) }
                    .associate { it.key.replaceFirst(PRESENT_PREFIX, PAST_PREFIX) to it.value as OnnxTensor }
                    .filterValues { tensor -> tensor.info.shape.none { it == 0L } }
                    .mapValues { State(it.value) }
                // The next step's pasts come from the first result (cross-attention) and this one (self-attention).
                while (results.size > 2) results.removeAt(1).close()
                Step(logits, presents)
            }
        }
    } catch (e: Exception) {
        Log.e(TAG, "Decoder step failed: ${e.message}", e)
        null
    }

    override fun close() {
        results.forEach { it.close() }
        results.clear()
        emptyPast.close()
        encoderHiddenStates.close()
    }

    private companion object {
        const val TAG = "PastKeyValueDecode"
        const val PAST_PREFIX = "past_key_values."
        const val PRESENT_PREFIX = "present."
    }
}
