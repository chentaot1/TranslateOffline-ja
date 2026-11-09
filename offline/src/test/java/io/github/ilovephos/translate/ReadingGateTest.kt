package io.github.ilovephos.translate

import org.junit.Assert.*
import org.junit.Test

class ReadingGateTest {
    private fun sample(color: Int) = ScreenSample.fromPixels(100, 100, IntArray(100) { color }, 10, 10)

    @Test fun waitsForSettledFrameAndSubmitsOnlyOnceDespiteCaptureNoise() {
        val gate = ReadingGate(700)
        assertFalse(gate.observe(sample(0xfff8f8f8.toInt()), 0))
        assertFalse(gate.observe(sample(0xfff7f7f7.toInt()), 699))
        assertTrue(gate.observe(sample(0xfff8f8f8.toInt()), 700))
        assertFalse(gate.observe(sample(0xfff7f7f7.toInt()), 1500))
    }

    @Test fun movementRestartsWaitingAndResetAllowsSamePage() {
        val gate = ReadingGate(700)
        gate.observe(sample(-1), 0)
        assertFalse(gate.observe(sample(0xff101010.toInt()), 600))
        assertFalse(gate.observe(sample(0xff101010.toInt()), 900))
        assertTrue(gate.observe(sample(0xff101010.toInt()), 1300))
        gate.reset()
        assertFalse(gate.observe(sample(0xff101010.toInt()), 1400))
        assertTrue(gate.observe(sample(0xff101010.toInt()), 2100))
    }

    @Test fun oldGenerationCannotRenderAfterScrollOrStop() {
        val generations = RenderGeneration()
        val old = generations.advance()
        assertTrue(generations.accepts(old))
        val current = generations.advance()
        assertFalse(generations.accepts(old))
        assertTrue(generations.accepts(current))
        generations.stop()
        assertFalse(generations.accepts(current))
    }
}
