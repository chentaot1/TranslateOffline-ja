package io.github.ilovephos.translate

import org.junit.Assert.*
import org.junit.Test

class ScreenSampleTest {
    private fun sample(pixels: IntArray, ignored: List<SampleRegion> = emptyList(), width: Int = 100) =
        ScreenSample.fromPixels(width, 100, pixels, 10, 10, ignored)

    @Test fun captureRoundingAndOneBlinkingPixelAreNotANewPage() {
        val source = IntArray(100) { 0xfff8f8f8.toInt() }
        val rounding = IntArray(100) { 0xfff7f7f7.toInt() }
        val blink = source.copyOf().apply { this[25] = 0xff000000.toInt() }
        assertTrue(sample(source).matches(sample(rounding)))
        assertTrue(sample(source).matches(sample(blink)))
    }

    @Test fun ownLetteringIsExcludedEvenWhenCaptureArrivalIsLateOrRepeated() {
        val source = IntArray(100) { 0xff101010.toInt() }
        val lettering = source.copyOf().apply { for (row in 2..5) for (column in 2..6) this[row * 10 + column] = -1 }
        val box = SampleRegion(20f, 20f, 70f, 60f)
        assertFalse(sample(source).matches(sample(lettering)))
        assertTrue(sample(source).matches(sample(lettering), listOf(box)))
    }

    @Test fun pageMovementOutsideLetteringIsStillDetected() {
        val original = IntArray(100) { -1 }
        val moved = original.copyOf().apply { for (index in 70..89) this[index] = 0xff202020.toInt() }
        assertFalse(sample(original).matches(sample(moved), listOf(SampleRegion(0f, 0f, 100f, 50f))))
    }

    @Test fun floatingControlsAndSystemBarsNeverDecideAMatch() {
        val original = IntArray(100) { -1 }
        val current = original.copyOf().apply { for (index in 0..19) this[index] = 0xff000000.toInt() }
        val bars = listOf(SampleRegion(0f, 0f, 100f, 20f))
        assertTrue(sample(original, bars).matches(sample(current, bars)))
        assertEquals(sample(original, bars).cacheKey, sample(current, bars).cacheKey)
    }

    @Test fun rotationAlwaysInvalidatesThePage() {
        val pixels = IntArray(100) { -1 }
        assertFalse(sample(pixels).matches(sample(pixels, width = 200)))
    }

    @Test fun ignoresWholeCellsTouchingAPaintedEdgeAndClipsOffscreenMasks() {
        val source = IntArray(100) { -1 }
        val current = source.copyOf().apply { for (index in 0..29) this[index] = 0xff000000.toInt() }
        assertTrue(sample(source).matches(sample(current), listOf(SampleRegion(-20f, -20f, 120f, 21f))))
    }

    @Test fun sourceSamplesAreImmutableAndInvalidShapesAreRejected() {
        val pixels = IntArray(100) { -1 }
        val source = sample(pixels)
        pixels.fill(0xff000000.toInt())
        assertTrue(source.matches(sample(IntArray(100) { -1 })))
        assertThrows(IllegalArgumentException::class.java) { sample(IntArray(99)) }
        assertThrows(IllegalArgumentException::class.java) { sample(IntArray(100), width = 0) }
    }
}
