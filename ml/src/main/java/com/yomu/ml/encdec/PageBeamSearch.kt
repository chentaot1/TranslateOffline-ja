package com.yomu.ml.encdec

import java.util.PriorityQueue
import kotlin.math.ln
import kotlin.math.pow

/**
 * Fast Greedy (K=1) and Length-Normalized Beam Search (K=4) decoder for manga page translation.
 */
class PageBeamSearch(
    val eosId: Long = 2L,
    val bosId: Long = 1L,
    val vocabSize: Int = 16000
) {

    data class StepOutput(
        val logits: Array<FloatArray> // [B, V]
    )

    interface StepRunner {
        fun step(
            tokens: LongArray,        // [B] input tokens for current step
            pos: Int,                 // current step position 0, 1, ...
            relIndices: IntArray,     // [B] maps each element in batch to line index
            gatherIndices: IntArray?  // [B] indices for permutation of KV cache from previous step (null at step 0)
        ): StepOutput

        fun reset() {}
    }

    data class SearchResult(
        val tokens: LongArray,
        val score: Double,
        val normalizedScore: Double
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is SearchResult) return false
            return tokens.contentEquals(other.tokens) &&
                    score == other.score &&
                    normalizedScore == other.normalizedScore
        }

        override fun hashCode(): Int {
            var result = tokens.contentHashCode()
            result = 31 * result + score.hashCode()
            result = 31 * result + normalizedScore.hashCode()
            return result
        }
    }

    /**
     * Fast Greedy K=1 search with zero allocation argmax and confidence tracking.
     */
    fun greedy(
        lineCount: Int,
        maxLen: Int = 128,
        stepRunner: StepRunner
    ): List<SearchResult> {
        if (lineCount <= 0) return emptyList()

        stepRunner.reset()

        var alive = IntArray(lineCount) { it }
        var toks = LongArray(lineCount) { bosId }
        var relIndices = alive.clone()
        var gatherIndices: IntArray? = null

        val completed = arrayOfNulls<SearchResult>(lineCount)
        val lineTokens = Array(lineCount) { mutableListOf<Long>() }
        val lineLogProbs = DoubleArray(lineCount) { 0.0 }

        for (t in 0 until maxLen) {
            val output = stepRunner.step(toks, t, relIndices, gatherIndices)
            val logits = output.logits

            val nextAlive = mutableListOf<Int>()
            val nextToks = mutableListOf<Long>()
            val nextRel = mutableListOf<Int>()
            val nextGather = mutableListOf<Int>()

            for (b in alive.indices) {
                val lineIdx = alive[b]
                val row = logits[b]

                var bestV = 0
                var maxVal = row[0]
                for (v in 1 until row.size) {
                    if (row[v] > maxVal) {
                        maxVal = row[v]
                        bestV = v
                    }
                }

                // Log-softmax calculation for confidence tracking
                var sumExp = 0.0
                for (v in row.indices) {
                    sumExp += Math.exp((row[v] - maxVal).toDouble())
                }
                val logProb = -ln(sumExp)
                lineLogProbs[lineIdx] += logProb

                if (bestV.toLong() == eosId) {
                    val finalTokens = lineTokens[lineIdx].toLongArray()
                    val score = lineLogProbs[lineIdx]
                    val normScore = score / Math.max(1, finalTokens.size)
                    completed[lineIdx] = SearchResult(finalTokens, score, normScore)
                } else {
                    lineTokens[lineIdx].add(bestV.toLong())
                    nextAlive.add(lineIdx)
                    nextToks.add(bestV.toLong())
                    nextRel.add(lineIdx)
                    nextGather.add(b)
                }
            }

            if (nextAlive.isEmpty()) break

            gatherIndices = if (nextGather.size < alive.size) nextGather.toIntArray() else null
            alive = nextAlive.toIntArray()
            toks = nextToks.toLongArray()
            relIndices = nextRel.toIntArray()
        }

        for (lineIdx in alive) {
            if (completed[lineIdx] == null) {
                val finalTokens = lineTokens[lineIdx].toLongArray()
                val score = lineLogProbs[lineIdx]
                val normScore = score / Math.max(1, finalTokens.size)
                completed[lineIdx] = SearchResult(finalTokens, score, normScore)
            }
        }

        return completed.map { it ?: SearchResult(LongArray(0), 0.0, 0.0) }
    }

    private data class Candidate(val beamIdx: Int, val token: Int, val score: Double) : Comparable<Candidate> {
        override fun compareTo(other: Candidate): Int = this.score.compareTo(other.score)
    }

    /**
     * Beam Search with length penalty and KV cache permutation tracking.
     */
    fun beamSearch(
        lineCount: Int,
        k: Int = 4,
        maxLen: Int = 128,
        alpha: Float = 1.0f,
        stepRunner: StepRunner
    ): List<SearchResult> {
        if (lineCount <= 0) return emptyList()

        stepRunner.reset()

        val seqs = Array(lineCount) { mutableListOf(emptyList<Long>()) }
        val scores = Array(lineCount) { doubleArrayOf(0.0) }
        val finished = Array(lineCount) { mutableListOf<SearchResult>() }

        var alive = IntArray(lineCount) { it }
        var toks = LongArray(lineCount) { bosId }
        var relIndices = alive.clone()
        var gatherIndices: IntArray? = null

        for (t in 0 until maxLen) {
            val r = stepRunner.step(toks, t, relIndices, gatherIndices)
            val logits = r.logits
            val totalBatch = logits.size

            // Compute log_softmax across each row
            val lp = Array(totalBatch) { b ->
                val row = logits[b]
                var maxVal = row[0]
                for (v in 1 until row.size) {
                    if (row[v] > maxVal) maxVal = row[v]
                }
                var sumExp = 0.0
                for (v in row.indices) {
                    sumExp += Math.exp((row[v] - maxVal).toDouble())
                }
                val logSumExp = maxVal + ln(sumExp)
                DoubleArray(row.size) { v -> row[v] - logSumExp }
            }

            val nextAlive = mutableListOf<Int>()
            val gather = mutableListOf<Int>()
            val nextToks = mutableListOf<Long>()
            val nextRel = mutableListOf<Int>()

            var off = 0
            for (li in alive) {
                val nb = seqs[li].size
                val lineScores = scores[li]

                // Find top 2k candidates using a min-heap
                val pq = PriorityQueue<Candidate>(2 * k)
                for (b in 0 until nb) {
                    val baseScore = lineScores[b]
                    val rowLp = lp[off + b]
                    for (v in rowLp.indices) {
                        val candScore = baseScore + rowLp[v]
                        if (pq.size < 2 * k) {
                            pq.add(Candidate(b, v, candScore))
                        } else if (candScore > pq.peek()!!.score) {
                            pq.poll()
                            pq.add(Candidate(b, v, candScore))
                        }
                    }
                }

                val topList = mutableListOf<Candidate>()
                while (pq.isNotEmpty()) {
                    val cand = pq.poll() ?: break
                    topList.add(cand)
                }
                topList.sortByDescending { it.score }

                val ns = mutableListOf<List<Long>>()
                val nsc = mutableListOf<Double>()
                val src = mutableListOf<Int>()

                for (cand in topList) {
                    val b = cand.beamIdx
                    val tk = cand.token.toLong()
                    if (tk == eosId) {
                        val hyp = seqs[li][b]
                        val norm = cand.score / Math.max(1, hyp.size + 1).toDouble().pow(alpha.toDouble())
                        finished[li].add(SearchResult(hyp.toLongArray(), cand.score, norm))
                    } else {
                        ns.add(seqs[li][b] + listOf(tk))
                        nsc.add(cand.score)
                        src.add(off + b)
                    }
                    if (ns.size == k) break
                }

                val maxFinishedNorm = finished[li].maxOfOrNull { it.normalizedScore } ?: Double.NEGATIVE_INFINITY
                val maxNsc = if (nsc.isNotEmpty()) nsc.maxOrNull()!! else Double.NEGATIVE_INFINITY
                val done = finished[li].size >= k && maxFinishedNorm >= maxNsc / (t + 1).toDouble().pow(alpha.toDouble())

                seqs[li] = ns
                scores[li] = nsc.toDoubleArray()

                if (!done && ns.isNotEmpty()) {
                    nextAlive.add(li)
                    gather.addAll(src)
                    for (s in ns) nextToks.add(s.last())
                    for (s in ns) nextRel.add(li)
                }

                off += nb
            }

            alive = nextAlive.toIntArray()
            if (alive.isEmpty()) break

            gatherIndices = gather.toIntArray()
            toks = nextToks.toLongArray()
            relIndices = nextRel.toIntArray()
        }

        val results = mutableListOf<SearchResult>()
        for (li in 0 until lineCount) {
            if (finished[li].isEmpty()) {
                val bestIdx = if (scores[li].isNotEmpty()) {
                    scores[li].indices.maxByOrNull { scores[li][it] } ?: 0
                } else 0
                val bestSeq = if (seqs[li].isNotEmpty() && bestIdx < seqs[li].size) seqs[li][bestIdx] else emptyList()
                val score = if (scores[li].isNotEmpty() && bestIdx < scores[li].size) scores[li][bestIdx] else 0.0
                val norm = score / Math.max(1, bestSeq.size).toDouble().pow(alpha.toDouble())
                results.add(SearchResult(bestSeq.toLongArray(), score, norm))
            } else {
                results.add(finished[li].maxByOrNull { it.normalizedScore }!!)
            }
        }
        return results
    }

    /**
     * Adaptive execution: runs Fast Greedy, promotes to Beam Search if normalized log-prob < [threshold].
     */
    fun adaptiveSearch(
        lineCount: Int,
        k: Int = 4,
        maxLen: Int = 128,
        alpha: Float = 1.0f,
        threshold: Float = -1.15f,
        stepRunner: StepRunner
    ): List<SearchResult> {
        val greedyResults = greedy(lineCount, maxLen, stepRunner)
        val avgConfidence = if (greedyResults.isNotEmpty()) {
            greedyResults.map { it.normalizedScore }.average()
        } else 0.0

        if (avgConfidence >= threshold) {
            return greedyResults
        }

        return beamSearch(lineCount, k, maxLen, alpha, stepRunner)
    }
}
