package com.yomu.ml.encdec

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PageBeamSearchTest {

    private val vocabSize = 100 // small vocab for testing
    private val eosId = 2L
    private val bosId = 1L

    @Test
    fun greedy_selectsArgmaxAndStopsOnEos() {
        val search = PageBeamSearch(eosId = eosId, bosId = bosId, vocabSize = vocabSize)

        // Line 0 target: 10 -> 20 -> EOS(2)
        // Line 1 target: 30 -> EOS(2)
        val stepRunner = object : PageBeamSearch.StepRunner {
            override fun step(
                tokens: LongArray,
                pos: Int,
                relIndices: IntArray,
                gatherIndices: IntArray?
            ): PageBeamSearch.StepOutput {
                val batchSize = tokens.size
                val logits = Array(batchSize) { FloatArray(vocabSize) { -10.0f } }

                for (b in 0 until batchSize) {
                    val rel = relIndices[b]
                    if (rel == 0) { // Line 0
                        when (pos) {
                            0 -> logits[b][10] = 10.0f
                            1 -> logits[b][20] = 10.0f
                            2 -> logits[b][eosId.toInt()] = 10.0f
                        }
                    } else { // Line 1
                        when (pos) {
                            0 -> logits[b][30] = 10.0f
                            1 -> logits[b][eosId.toInt()] = 10.0f
                        }
                    }
                }
                return PageBeamSearch.StepOutput(logits)
            }
        }

        val results = search.greedy(
            lineCount = 2,
            maxLen = 10,
            stepRunner = stepRunner
        )

        assertEquals(2, results.size)
        assertEquals(listOf(10L, 20L), results[0].tokens.toList())
        assertEquals(listOf(30L), results[1].tokens.toList())
    }

    @Test
    fun beam4_tracksHypothesesAndAppliesLengthPenalty() {
        val search = PageBeamSearch(eosId = eosId, bosId = bosId, vocabSize = vocabSize)

        // 1 line with branching:
        // Step 0: token 5 (score 5.0) and token 6 (score 4.0)
        // Step 1:
        //   token 5 -> token 7 (score 2.0) or EOS (score 1.0)
        //   token 6 -> EOS (score 8.0)
        // With alpha = 1.0:
        //   Path 6 -> EOS has cand = 4.0 + 8.0 = 12.0, len = 1 -> norm = 12.0 / 2 = 6.0
        //   Path 5 -> 7 -> EOS has lower or higher norm depending on scores
        val stepRunner = object : PageBeamSearch.StepRunner {
            override fun step(
                tokens: LongArray,
                pos: Int,
                relIndices: IntArray,
                gatherIndices: IntArray?
            ): PageBeamSearch.StepOutput {
                val batchSize = tokens.size
                val logits = Array(batchSize) { FloatArray(vocabSize) { -20.0f } }

                for (b in 0 until batchSize) {
                    val tok = tokens[b]
                    if (pos == 0) {
                        logits[b][5] = 5.0f
                        logits[b][6] = 4.0f
                    } else if (pos == 1) {
                        if (tok == 5L) {
                            logits[b][7] = 2.0f
                            logits[b][eosId.toInt()] = 1.0f
                        } else if (tok == 6L) {
                            logits[b][eosId.toInt()] = 8.0f
                        }
                    }
                }
                return PageBeamSearch.StepOutput(logits)
            }
        }

        val results = search.beamSearch(
            lineCount = 1,
            k = 4,
            maxLen = 10,
            alpha = 1.0f,
            stepRunner = stepRunner
        )

        assertEquals(1, results.size)
        // Path [6] ending in EOS should win due to high score (8.0)
        assertEquals(listOf(6L), results[0].tokens.toList())
    }

    @Test
    fun adaptive_promotesToBeamWhenConfidenceIsLow() {
        val search = PageBeamSearch(eosId = eosId, bosId = bosId, vocabSize = vocabSize)

        // Confident runner: top logit is very high, confidence ~ -0.01 > -1.15
        val confidentRunner = object : PageBeamSearch.StepRunner {
            override fun step(
                tokens: LongArray,
                pos: Int,
                relIndices: IntArray,
                gatherIndices: IntArray?
            ): PageBeamSearch.StepOutput {
                val logits = Array(tokens.size) { FloatArray(vocabSize) { 0.0f } }
                for (b in tokens.indices) {
                    if (pos == 0) logits[b][50] = 50.0f
                    else logits[b][eosId.toInt()] = 50.0f
                }
                return PageBeamSearch.StepOutput(logits)
            }
        }

        var beamCalled = false
        val monitoringRunner = object : PageBeamSearch.StepRunner {
            override fun step(
                tokens: LongArray,
                pos: Int,
                relIndices: IntArray,
                gatherIndices: IntArray?
            ): PageBeamSearch.StepOutput {
                if (tokens.size > 1) {
                    beamCalled = true
                }
                return confidentRunner.step(tokens, pos, relIndices, gatherIndices)
            }
        }

        val res1 = search.adaptiveSearch(
            lineCount = 1,
            stepRunner = monitoringRunner,
            threshold = -1.15f
        )
        assertEquals(listOf(50L), res1[0].tokens.toList())
        assertTrue("Beam search should NOT be called for high confidence", !beamCalled)
    }
}
