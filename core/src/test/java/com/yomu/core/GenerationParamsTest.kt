package com.yomu.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationParamsTest {

    /**
     * The C++ side reads this array by index against its own SamplerIndex enum, so a reordering
     * here is a silent behaviour change. Indices are looked up through SAMPLER_INDEX rather than
     * written as literals, so that constant is the one place the order is stated on this side.
     */
    @Test
    fun `sampler array matches the documented index order`() {
        val params = GenerationParams(
            temperature = 1f,
            topK = 2,
            topP = 3f,
            penaltyLastN = 4,
            penaltyRepeat = 5f,
            penaltyFreq = 6f,
            penaltyPresent = 7f
        )
        val array = params.samplerArray()

        assertEquals(GenerationParams.SAMPLER_INDEX.size, array.size)
        assertEquals(1f, array[indexOf("temperature")], 0f)
        assertEquals(2f, array[indexOf("topK")], 0f)
        assertEquals(3f, array[indexOf("topP")], 0f)
        assertEquals(4f, array[indexOf("penaltyLastN")], 0f)
        assertEquals(5f, array[indexOf("penaltyRepeat")], 0f)
        assertEquals(6f, array[indexOf("penaltyFreq")], 0f)
        assertEquals(7f, array[indexOf("penaltyPresent")], 0f)
    }

    @Test
    fun `defaults match the values that were hardcoded before they moved here`() {
        val params = GenerationParams()
        assertEquals(0.2f, params.temperature, 0f)
        assertEquals(40, params.topK)
        assertEquals(0.9f, params.topP, 0f)
        assertEquals(1.0f, params.penaltyRepeat, 0f)
        assertEquals(64, params.penaltyLastN)
        assertEquals(0.0f, params.penaltyFreq, 0f)
        assertEquals(0.0f, params.penaltyPresent, 0f)
        assertEquals(256, params.maxTokens)
        // LLAMA_DEFAULT_SEED is 0xFFFFFFFF; as a signed Int that is -1.
        assertEquals(-1, params.seed)
        // #214: measured on the reference phone and shipped on.
        assertEquals(true, params.lineExcludesIdBracket)
    }

    @Test
    fun `only temperature, top-k and top-p are reader-facing, with the shipped defaults`() {
        assertEquals(
            listOf(GenerationBound.TEMPERATURE, GenerationBound.TOP_K, GenerationBound.TOP_P),
            GenerationBound.entries
        )
        val shipped = GenerationParams()
        assertEquals(shipped.temperature, GenerationBound.TEMPERATURE.default, 0f)
        assertEquals(shipped.topK.toFloat(), GenerationBound.TOP_K.default, 0f)
        assertEquals(shipped.topP, GenerationBound.TOP_P.default, 0f)
        GenerationBound.entries.forEach { assertTrue(it.name, it.accepts(it.default)) }
    }

    @Test
    fun `declared ranges and steps match the spec`() {
        assertBounds(GenerationBound.TEMPERATURE, 0f, 1f, 0.1f)
        assertBounds(GenerationBound.TOP_K, 1f, 100f, 1f)
        assertBounds(GenerationBound.TOP_P, 0f, 1f, 0.05f)
    }

    @Test
    fun `edges validate and values just outside do not`() {
        GenerationBound.entries.forEach { bound ->
            assertTrue(bound.name, bound.accepts(bound.min))
            assertTrue(bound.name, bound.accepts(bound.max))
            assertFalse(bound.name, bound.accepts(Math.nextDown(bound.min)))
            assertFalse(bound.name, bound.accepts(Math.nextUp(bound.max)))
        }
    }

    @Test
    fun `non-finite values do not validate`() {
        GenerationBound.entries.forEach { bound ->
            assertFalse(bound.accepts(Float.NaN))
            assertFalse(bound.accepts(Float.POSITIVE_INFINITY))
            assertFalse(bound.accepts(Float.NEGATIVE_INFINITY))
        }
    }

    @Test
    fun `a bound reads and writes its own field only`() {
        val params = GenerationParams()
            .let { GenerationBound.TEMPERATURE.write(it, 0.7f) }
            .let { GenerationBound.TOP_K.write(it, 12f) }
            .let { GenerationBound.TOP_P.write(it, 0.5f) }

        assertEquals(GenerationParams(temperature = 0.7f, topK = 12, topP = 0.5f), params)
        assertEquals(12f, GenerationBound.TOP_K.read(params), 0f)
    }

    @Test
    fun `withinBounds replaces only the out-of-range fields with their defaults`() {
        val unsafe = GenerationParams(temperature = Float.NaN, topK = 500, topP = 0.5f, seed = 7)

        assertEquals(GenerationParams(topP = 0.5f, seed = 7), unsafe.withinBounds())
    }

    private fun assertBounds(bound: GenerationBound, min: Float, max: Float, step: Float) {
        assertEquals(min, bound.min, 0f)
        assertEquals(max, bound.max, 0f)
        assertEquals(step, bound.step, 0f)
    }

    private fun indexOf(field: String): Int =
        GenerationParams.SAMPLER_INDEX.indexOf(field).also { require(it >= 0) { "unknown: $field" } }
}
