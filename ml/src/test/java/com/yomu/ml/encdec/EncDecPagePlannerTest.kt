package com.yomu.ml.encdec

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EncDecPagePlannerTest {

    @Test
    fun windows_singleWindowWhenWithinBudget() {
        val lens = listOf(10, 20, 15, 30) // costs = 11 + 21 + 16 + 31 = 79 + 1 (BOS) <= 256
        val windows = EncDecPagePlanner.windows(lens, budget = 256)

        assertEquals(1, windows.size)
        assertEquals(EncDecPagePlanner.Window(lo = 0, hi = 4, tlo = 0, thi = 4), windows[0])
    }

    @Test
    fun windows_splitsAndOverlapsWhenExceedingBudget() {
        // 10 lines of 50 tokens each. Each cost is 51. Total cost 511 > 256.
        val lens = List(10) { 50 }
        val windows = EncDecPagePlanner.windows(lens, budget = 256)

        assertTrue("Should produce multiple windows", windows.size > 1)

        // Verify full coverage of target lines without gaps or duplicates
        var expectedTlo = 0
        for (w in windows) {
            assertEquals("Target lines must be contiguous", expectedTlo, w.tlo)
            assertTrue("Receptive field must cover target lines lo <= tlo", w.lo <= w.tlo)
            assertTrue("Receptive field must cover target lines thi <= hi", w.thi <= w.hi)
            expectedTlo = w.thi
        }
        assertEquals("All 10 lines must be covered", 10, expectedTlo)
    }

    @Test
    fun frameWindow_constructsSrcAndSegWithCorrectSeparators() {
        val body0 = longArrayOf(100L, 101L)
        val body1 = longArrayOf(200L, 201L, 202L)
        val bodies = listOf(body0, body1)

        val window = EncDecPagePlanner.Window(lo = 0, hi = 2, tlo = 0, thi = 2)
        val framed = EncDecPagePlanner.frameWindow(bodies, window)

        // Expected src: [<s>(1), 100, 101, <sep>(4), 200, 201, 202, </s>(2)]
        val expectedSrc = longArrayOf(1L, 100L, 101L, 4L, 200L, 201L, 202L, 2L)
        assertEquals(expectedSrc.toList(), framed.src.toList())

        // Expected seg:
        // <s> -> 0
        // body0 (100, 101) + <sep> -> 0, 0, 0
        // body1 (200, 201, 202) + </s> -> 1, 1, 1, 1
        val expectedSeg = intArrayOf(0, 0, 0, 0, 1, 1, 1, 1)
        assertEquals(expectedSeg.toList(), framed.seg.toList())
    }

    @Test
    fun computeRels_clipsOffsetsToRange0To10() {
        // seg: line 0 has 2 tokens, line 1 has 2 tokens, line 6 has 2 tokens
        // seg values: [0, 0, 1, 1, 6, 6]
        val seg = intArrayOf(0, 0, 1, 1, 6, 6)
        val lo = 0
        val tlo = 0
        val thi = 2 // target lines 0 and 1
        val relMax = 5

        val rels = EncDecPagePlanner.computeRels(seg, lo = lo, tlo = tlo, thi = thi, relMax = relMax)

        assertEquals("Should have 2 rows for 2 target lines", 2, rels.size)
        // Row 0 (t = 0):
        // seg=0 -> 0 - 0 = 0 -> +5 = 5
        // seg=1 -> 1 - 0 = 1 -> +5 = 6
        // seg=6 -> 6 - 0 = 6 -> clipped to 5 -> +5 = 10
        val expectedRow0 = intArrayOf(5, 5, 6, 6, 10, 10)
        assertEquals(expectedRow0.toList(), rels[0].toList())

        // Row 1 (t = 1):
        // seg=0 -> 0 - 1 = -1 -> +5 = 4
        // seg=1 -> 1 - 1 = 0 -> +5 = 5
        // seg=6 -> 6 - 1 = 5 -> +5 = 10
        val expectedRow1 = intArrayOf(4, 4, 5, 5, 10, 10)
        assertEquals(expectedRow1.toList(), rels[1].toList())
    }

    @Test
    fun planPage_skipsBlankLinesAndLimitsBodyTo126() {
        val lines = listOf(
            "Line 1",
            "",
            "   ",
            "Line 4"
        )
        // Mock tokenizer encoding: each word is 1 token, long string is 150 tokens
        val encoded = listOf(
            longArrayOf(10L, 11L),
            LongArray(150) { 99L }
        )
        val plan = EncDecPagePlanner.plan(
            rawLines = lines,
            encodedBodies = encoded,
            maxBody = 126,
            budget = 256
        )

        assertEquals(listOf(0, 3), plan.keepIndices)
        assertEquals(4, plan.rawLineCount)
        assertEquals(1, plan.windows.size)
        // Body for line 4 must be truncated to 126
        assertEquals(126, plan.bodies[1].size)
    }
}
