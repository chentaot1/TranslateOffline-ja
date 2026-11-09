package com.yomu.ml.encdec

/**
 * Receptive field windowing, sequence framing, and relative position encoding calculation
 * for Japanese -> English manga page translation.
 */
object EncDecPagePlanner {

    data class Window(
        val lo: Int,
        val hi: Int,
        val tlo: Int,
        val thi: Int
    )

    data class FramedWindow(
        val src: LongArray,
        val seg: IntArray,
        val window: Window
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is FramedWindow) return false
            return src.contentEquals(other.src) &&
                    seg.contentEquals(other.seg) &&
                    window == other.window
        }

        override fun hashCode(): Int {
            var result = src.contentHashCode()
            result = 31 * result + seg.contentHashCode()
            result = 31 * result + window.hashCode()
            return result
        }
    }

    data class PagePlan(
        val rawLineCount: Int,
        val keepIndices: List<Int>,
        val bodies: List<LongArray>,
        val windows: List<Window>
    )

    /**
     * Splits page lines into overlapping context windows that fit within [budget] tokens.
     * Each window covers receptive lines [lo until hi], decoding target lines [tlo until thi].
     */
    fun windows(lens: List<Int>, budget: Int = 256): List<Window> {
        val n = lens.size
        if (n == 0) return emptyList()

        val cost = lens.map { it + 1 }
        if (1 + cost.sum() <= budget) {
            return listOf(Window(lo = 0, hi = n, tlo = 0, thi = n))
        }

        val out = mutableListOf<Window>()
        var i = 0
        while (i < n) {
            var j = i
            var c = 0
            while (j < n && (j == i || c + cost[j] <= budget / 2)) {
                c += cost[j]
                j++
            }
            var lo = i
            var hi = j
            var tot = c + 1
            var grew = true
            while (grew) {
                grew = false
                if (hi < n && tot + cost[hi] <= budget) {
                    tot += cost[hi]
                    hi++
                    grew = true
                }
                if (lo > 0 && tot + cost[lo - 1] <= budget) {
                    tot += cost[lo - 1]
                    lo--
                    grew = true
                }
            }
            out.add(Window(lo = lo, hi = hi, tlo = i, thi = j))
            i = j
        }
        return out
    }

    /**
     * Constructs the framed source token IDs and segment IDs for a given window:
     * <s> line_lo <sep> line_lo+1 ... </s>
     */
    fun frameWindow(bodies: List<LongArray>, window: Window): FramedWindow {
        val srcList = mutableListOf<Long>()
        val segList = mutableListOf<Int>()

        // <s>
        srcList.add(EncDecTokenizer.BOS_ID)
        segList.add(0)

        for (j in window.lo until window.hi) {
            val body = bodies[j]
            for (tok in body) {
                srcList.add(tok)
                segList.add(j - window.lo)
            }
            val sep = if (j < window.hi - 1) EncDecTokenizer.ENC_SEP_ID else EncDecTokenizer.EOS_ID
            srcList.add(sep)
            segList.add(j - window.lo)
        }

        if (segList.isNotEmpty()) {
            segList[segList.size - 1] = window.hi - 1 - window.lo
        }

        return FramedWindow(
            src = srcList.toLongArray(),
            seg = segList.toIntArray(),
            window = window
        )
    }

    /**
     * Computes the relative position matrix `rel[B, S]` clipped to `[-relMax, relMax] + relMax`.
     */
    fun computeRels(
        seg: IntArray,
        lo: Int,
        tlo: Int,
        thi: Int,
        relMax: Int = 5
    ): Array<IntArray> {
        val targetCount = thi - tlo
        val rels = Array(targetCount) { IntArray(seg.size) }

        for (idx in 0 until targetCount) {
            val t = tlo + idx
            val targetOffset = t - lo
            val row = rels[idx]
            for (s in seg.indices) {
                val diff = seg[s] - targetOffset
                val clipped = Math.min(relMax, Math.max(-relMax, diff))
                row[s] = clipped + relMax
            }
        }
        return rels
    }

    /**
     * Filters blank lines, truncates line bodies to [maxBody], and computes page execution windows.
     */
    fun plan(
        rawLines: List<String>,
        encodedBodies: List<LongArray>,
        maxBody: Int = 126,
        budget: Int = 256
    ): PagePlan {
        val keepIndices = rawLines.indices.filter { rawLines[it].trim().isNotEmpty() }
        val bodies = keepIndices.mapIndexed { idx, _ ->
            val b = encodedBodies[idx]
            if (b.size > maxBody) b.copyOf(maxBody) else b
        }
        val windows = windows(bodies.map { it.size }, budget)
        return PagePlan(
            rawLineCount = rawLines.size,
            keepIndices = keepIndices,
            bodies = bodies,
            windows = windows
        )
    }
}
