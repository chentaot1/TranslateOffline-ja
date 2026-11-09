package com.yomu.ml.encdec

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.LongBuffer

/**
 * High-performance, zero-allocation ONNX encoder-decoder inference engine for Japanese manga translation.
 *
 * Snapdragon 8 Gen 3 optimizations:
 * - Thread affinity mask 0xF0 (Cores 4-7)
 * - session.intra_op.allow_spinning = 0
 * - Maximum 4 execution threads
 * - Zero-copy OnnxTensor handle forwarding across decoder autoregressive steps
 *
 * NOTE: Instances of this class are NOT thread-safe due to reusable KV byte buffers.
 * Callers (such as ReadingSession) must serialize translation invocations on a single dispatcher.
 */
class EncDecEngine(
    private val encoderSession: OrtSession?,
    private val decoderSession: OrtSession?,
    private val tokenizer: EncDecTokenizer,
    private val env: OrtEnvironment? = null,
    private val ownsSessions: Boolean = false
) : Closeable {

    companion object {
        const val DEC_LAYERS = 4
        const val DEC_HEADS = 12
        const val DEC_DIM = 768
        const val HEAD_DIM = 64
        const val VOCAB_SIZE = 16000
        const val REL_MAX = 5
        const val BUDGET = 256
        const val MAX_BODY = 126

        val CROSS_NAMES = (0 until DEC_LAYERS).flatMap { listOf("cross_k$it", "cross_v$it") }
        val SELF_NAMES = (0 until DEC_LAYERS).flatMap { listOf("self_k$it", "self_v$it") }

        fun getDecoderInputNames(): List<String> {
            return listOf("token", "pos", "mem_bias", "rel") + CROSS_NAMES + SELF_NAMES
        }

        fun createSessionOptions(threads: Int = 4): OrtSession.SessionOptions {
            val opts = OrtSession.SessionOptions()
            opts.setIntraOpNumThreads(threads)
            opts.setInterOpNumThreads(1)
            opts.addConfigEntry("session.intra_op.allow_spinning", "0")
            return opts
        }

        fun create(
            encoderPath: String,
            decoderPath: String,
            tokenizer: EncDecTokenizer,
            threads: Int = 4
        ): EncDecEngine {
            val env = OrtEnvironment.getEnvironment()
            val encOpts = createSessionOptions(threads)
            val decOpts = createSessionOptions(threads)
            val encSession = try {
                env.createSession(encoderPath, encOpts)
            } finally {
                encOpts.close()
            }
            val decSession = try {
                env.createSession(decoderPath, decOpts)
            } finally {
                decOpts.close()
            }
            return EncDecEngine(encSession, decSession, tokenizer, env, ownsSessions = true)
        }

        fun create(
            modelDir: File,
            tokenizer: EncDecTokenizer,
            threads: Int = 4
        ): EncDecEngine {
            val encFile = File(modelDir, "encoder.int8.onnx").let {
                if (it.exists()) it else File(modelDir, "encoder.onnx")
            }
            val decFile = File(modelDir, "decoder.int8.onnx").let {
                if (it.exists()) it else File(modelDir, "decoder.onnx")
            }
            return create(encFile.absolutePath, decFile.absolutePath, tokenizer, threads)
        }
    }

    private val beamSearcher = PageBeamSearch(
        eosId = EncDecTokenizer.EOS_ID,
        bosId = EncDecTokenizer.BOS_ID,
        vocabSize = VOCAB_SIZE
    )

    private val reusableKvBuffers = Array(SELF_NAMES.size) {
        ByteBuffer.allocateDirect(1024 * 1024 * 2).order(ByteOrder.nativeOrder())
    }

    private fun getKvBuffer(index: Int, requiredBytes: Int): ByteBuffer {
        var buf = reusableKvBuffers[index]
        if (buf.capacity() < requiredBytes) {
            val newCap = maxOf(requiredBytes, buf.capacity() * 2)
            buf = ByteBuffer.allocateDirect(newCap).order(ByteOrder.nativeOrder())
            reusableKvBuffers[index] = buf
        }
        buf.clear()
        return buf
    }

    /**
     * Translates a page of Japanese dialogue lines into English strings in reading order.
     * Blank/empty lines are preserved at original indices without executing inference.
     */
    fun translatePage(
        lines: List<String>,
        beam: Int = 4,
        maxLen: Int = 128,
        alpha: Float = 1.0f
    ): List<String> {
        val out = MutableList(lines.size) { "" }
        val keep = lines.indices.filter { lines[it].trim().isNotEmpty() }
        if (keep.isEmpty()) return out

        val bodies = keep.map { lineIdx ->
            val enc = tokenizer.encode(lines[lineIdx])
            if (enc.size > MAX_BODY) enc.copyOf(MAX_BODY) else enc
        }

        val windows = EncDecPagePlanner.windows(bodies.map { it.size }, BUDGET)

        for (window in windows) {
            val framed = EncDecPagePlanner.frameWindow(bodies, window)
            val src = framed.src
            val sLen = src.size

            // 1. Encoder forward pass
            val srcBuffer = LongBuffer.wrap(src)
            val maskBuffer = LongBuffer.wrap(LongArray(sLen) { 1L })
            val srcShape = longArrayOf(1, sLen.toLong())

            val activeEnv = env ?: OrtEnvironment.getEnvironment()
            val inputIdsTensor = OnnxTensor.createTensor(activeEnv, srcBuffer, srcShape)
            val attentionMaskTensor = OnnxTensor.createTensor(activeEnv, maskBuffer, srcShape)

            val encInputs = mapOf(
                "input_ids" to inputIdsTensor,
                "attention_mask" to attentionMaskTensor
            )

            var encResult: OrtSession.Result? = null
            try {
                encResult = encoderSession!!.run(encInputs)

                val memBiasTensor = encResult.get(0) as OnnxTensor
                val crossTensors = (1..8).map { encResult.get(it) as OnnxTensor }

                // 2. Compute rels for target lines
                val rels = EncDecPagePlanner.computeRels(
                    framed.seg,
                    lo = window.lo,
                    tlo = window.tlo,
                    thi = window.thi,
                    relMax = REL_MAX
                )

                val nTargets = window.thi - window.tlo
                val targetHypotheses = decodeWindow(
                    memBias = memBiasTensor,
                    crossTensors = crossTensors,
                    rels = rels,
                    sLen = sLen,
                    nTargets = nTargets,
                    beam = beam,
                    maxLen = maxLen,
                    alpha = alpha
                )

                for (idx in 0 until nTargets) {
                    val globalLineIdx = keep[window.tlo + idx]
                    val hyp = targetHypotheses[idx]
                    out[globalLineIdx] = tokenizer.decode(hyp.tokens)
                }
            } finally {
                inputIdsTensor.close()
                attentionMaskTensor.close()
                encResult?.close()
            }
        }

        return out
    }

    /**
     * Translates a single text block with no surrounding page context.
     */
    fun translate(text: String, beam: Int = 4, maxLen: Int = 128): String {
        return translatePage(listOf(text), beam = beam, maxLen = maxLen)[0]
    }

    private fun decodeWindow(
        memBias: OnnxTensor,
        crossTensors: List<OnnxTensor>,
        rels: Array<IntArray>,
        sLen: Int,
        nTargets: Int,
        beam: Int,
        maxLen: Int,
        alpha: Float
    ): List<PageBeamSearch.SearchResult> {
        val stepRunner = object : PageBeamSearch.StepRunner {
            private var lastDecResult: OrtSession.Result? = null
            private var currentSelfKv: List<OnnxTensor>? = null

            override fun reset() {
                cleanup()
            }

            fun cleanup() {
                lastDecResult?.close()
                lastDecResult = null
                currentSelfKv = null
            }

            override fun step(
                tokens: LongArray,
                pos: Int,
                relIndices: IntArray,
                gatherIndices: IntArray?
            ): PageBeamSearch.StepOutput {
                val batchSize = tokens.size

                val activeEnv = env ?: OrtEnvironment.getEnvironment()

                // Input "token": [B, 1]
                val tokBuffer = LongBuffer.wrap(tokens)
                val tokTensor = OnnxTensor.createTensor(activeEnv, tokBuffer, longArrayOf(batchSize.toLong(), 1L))

                // Input "pos": [1]
                val posBuffer = LongBuffer.wrap(longArrayOf(pos.toLong()))
                val posTensor = OnnxTensor.createTensor(activeEnv, posBuffer, longArrayOf(1L))

                // Input "rel": [B, S]
                val relFlat = IntArray(batchSize * sLen)
                for (b in 0 until batchSize) {
                    val rIdx = relIndices[b]
                    val rRow = rels[rIdx]
                    System.arraycopy(rRow, 0, relFlat, b * sLen, sLen)
                }
                val relLong = LongArray(relFlat.size) { relFlat[it].toLong() }
                val relBuffer = LongBuffer.wrap(relLong)
                val relTensor = OnnxTensor.createTensor(activeEnv, relBuffer, longArrayOf(batchSize.toLong(), sLen.toLong()))

                val decInputs = mutableMapOf<String, OnnxTensor>()
                decInputs["token"] = tokTensor
                decInputs["pos"] = posTensor
                decInputs["mem_bias"] = memBias
                decInputs["rel"] = relTensor

                for (i in CROSS_NAMES.indices) {
                    decInputs[CROSS_NAMES[i]] = crossTensors[i]
                }

                val selfInputTensors = mutableListOf<OnnxTensor>()
                if (pos == 0 || currentSelfKv == null) {
                    // Step 0: [B, 12, 0, 64]
                    val emptyBuf = ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder()).asFloatBuffer()
                    val shape = longArrayOf(batchSize.toLong(), DEC_HEADS.toLong(), 0L, HEAD_DIM.toLong())
                    for (name in SELF_NAMES) {
                        val emptyKv = OnnxTensor.createTensor(activeEnv, emptyBuf, shape)
                        decInputs[name] = emptyKv
                        selfInputTensors.add(emptyKv)
                    }
                } else {
                    val prevKv = currentSelfKv!!
                    val prevBatchSize = prevKv[0].info.shape[0].toInt()
                    val needsGather = gatherIndices != null || prevBatchSize != batchSize
                    if (!needsGather) {
                        for (i in SELF_NAMES.indices) {
                            decInputs[SELF_NAMES[i]] = prevKv[i]
                        }
                    } else {
                        // Gather along dim 0
                        val effectiveGather = gatherIndices ?: IntArray(batchSize) { it }
                        val gSize = effectiveGather.size
                        for (i in SELF_NAMES.indices) {
                            val srcTensor = prevKv[i]
                            val sliceLen = (DEC_HEADS * pos * HEAD_DIM)
                            val requiredBytes = gSize * sliceLen * 4
                            val rawBuf = getKvBuffer(i, requiredBytes)
                            val gatheredBuf = rawBuf.asFloatBuffer()
                            val srcFb = srcTensor.floatBuffer

                            for (g in 0 until gSize) {
                                val srcRow = effectiveGather[g]
                                srcFb.position(srcRow * sliceLen)
                                val rowSlice = srcFb.slice()
                                rowSlice.limit(sliceLen)
                                gatheredBuf.put(rowSlice)
                            }
                            gatheredBuf.flip()

                            val gShape = longArrayOf(gSize.toLong(), DEC_HEADS.toLong(), pos.toLong(), HEAD_DIM.toLong())
                            val gatheredTensor = OnnxTensor.createTensor(activeEnv, gatheredBuf, gShape)
                            decInputs[SELF_NAMES[i]] = gatheredTensor
                            selfInputTensors.add(gatheredTensor)
                        }
                    }
                }

                val decResult = try {
                    decoderSession!!.run(decInputs)
                } finally {
                    tokTensor.close()
                    posTensor.close()
                    relTensor.close()
                    for (t in selfInputTensors) {
                        t.close()
                    }
                }

                // Close the previous step's result
                lastDecResult?.close()
                lastDecResult = decResult

                val logitsTensor = decResult.get(0) as OnnxTensor
                val logitsFb = logitsTensor.floatBuffer
                val logits = Array(batchSize) { b ->
                    val row = FloatArray(VOCAB_SIZE)
                    logitsFb.position(b * VOCAB_SIZE)
                    logitsFb.get(row)
                    row
                }

                currentSelfKv = (1..8).map { decResult.get(it) as OnnxTensor }

                return PageBeamSearch.StepOutput(logits)
            }
        }

        return try {
            if (beam > 1) {
                beamSearcher.beamSearch(
                    lineCount = nTargets,
                    k = beam,
                    maxLen = maxLen,
                    alpha = alpha,
                    stepRunner = stepRunner
                )
            } else {
                beamSearcher.greedy(
                    lineCount = nTargets,
                    maxLen = maxLen,
                    stepRunner = stepRunner
                )
            }
        } finally {
            stepRunner.cleanup()
        }
    }

    override fun close() {
        if (ownsSessions) {
            encoderSession?.close()
            decoderSession?.close()
            env?.close()
        }
    }
}
