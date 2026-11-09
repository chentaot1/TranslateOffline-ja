package io.github.ilovephos.translate

import org.junit.Assert.*
import org.junit.Test

class PageColorsTest {
    @Test fun minorityInkDoesNotTintColoredBubble() {
        val blue = 0xffbfdfff.toInt()
        assertEquals(blue, PageColors.background(intArrayOf(blue, 0xff000000.toInt(), blue, blue, blue)))
    }
    @Test fun choosesLegibleTextForDarkAndLightBubbles() {
        assertEquals(-1, PageColors.text(0xff202040.toInt()))
        assertEquals(0xff000000.toInt(), PageColors.text(0xffeeeeee.toInt()))
    }
    @Test fun evenSampleMedianAndEmptyFallbackAreStable() {
        assertEquals(0xff808080.toInt(), PageColors.background(intArrayOf(0xff606060.toInt(), 0xffa0a0a0.toInt())))
        assertEquals(-1, PageColors.background(intArrayOf()))
    }
}
