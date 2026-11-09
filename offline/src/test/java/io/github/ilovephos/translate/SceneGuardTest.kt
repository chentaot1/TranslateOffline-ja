package io.github.ilovephos.translate

import org.junit.Assert.*
import org.junit.Test

class SceneGuardTest {
    private fun sample(color: Int) = ScreenSample.fromPixels(100, 100, IntArray(100) { color }, 10, 10)

    @Test fun pageMovementRequiresConfirmationAndClearsDuringInference() {
        val guard = SceneGuard()
        guard.expect(sample(-1))
        assertFalse(guard.changed(sample(0xff202020.toInt())))
        assertTrue(guard.changed(sample(0xff202020.toInt())))
    }

    @Test fun lateEnglishFramesNeverCauseClearRecallFlicker() {
        val pixels = IntArray(100) { 0xff101010.toInt() }
        val source = ScreenSample.fromPixels(100, 100, pixels, 10, 10)
        val english = ScreenSample.fromPixels(100, 100, pixels.copyOf().apply {
            for (row in 2..5) for (column in 2..6) this[row * 10 + column] = -1
        }, 10, 10)
        val guard = SceneGuard()
        guard.expect(source)
        guard.ignoreTranslations(listOf(SampleRegion(20f, 20f, 70f, 60f)))
        repeat(60) {
            assertFalse(guard.changed(if (it % 2 == 0) source else english))
        }
    }

    @Test fun oneTransientFrameDoesNotClearFinishedTranslation() {
        val guard = SceneGuard()
        val source = sample(-1)
        guard.expect(source)
        assertFalse(guard.changed(sample(0xff202020.toInt())))
        assertFalse(guard.changed(source))
        assertFalse(guard.changed(sample(0xff202020.toInt())))
    }

    @Test fun movementOutsideTheTranslatedBoxesStillClearsThePage() {
        val guard = SceneGuard()
        guard.expect(sample(-1))
        guard.ignoreTranslations(listOf(SampleRegion(0f, 0f, 100f, 50f)))
        val moved = sample(0xff202020.toInt())
        assertFalse(guard.changed(moved))
        assertTrue(guard.changed(moved))
    }

    @Test fun resetStopsComparisonUntilAnotherPageIsSubmitted() {
        val guard = SceneGuard()
        guard.expect(sample(-1))
        guard.reset()
        repeat(3) { assertFalse(guard.changed(sample(0xff202020.toInt()))) }
        guard.expect(sample(0xff202020.toInt()))
        assertFalse(guard.changed(sample(-1)))
        assertTrue(guard.changed(sample(-1)))
    }
}
