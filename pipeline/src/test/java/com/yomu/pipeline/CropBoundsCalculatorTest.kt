package com.yomu.pipeline

import org.junit.Assert.assertEquals
import org.junit.Test

class CropBoundsCalculatorTest {

    @Test
    fun `clamps origin and keeps size within bitmap`() {
        val bounds = CropBoundsCalculator.clamp(
            bitmapWidth = 100,
            bitmapHeight = 80,
            left = 90,
            top = 70,
            width = 50,
            height = 30
        )

        assertEquals(90, bounds.x)
        assertEquals(70, bounds.y)
        assertEquals(10, bounds.width)
        assertEquals(10, bounds.height)
    }

    @Test
    fun `negative origin clamps to zero`() {
        val bounds = CropBoundsCalculator.clamp(
            bitmapWidth = 100,
            bitmapHeight = 80,
            left = -10,
            top = -15,
            width = 30,
            height = 40
        )

        assertEquals(0, bounds.x)
        assertEquals(0, bounds.y)
        assertEquals(30, bounds.width)
        assertEquals(40, bounds.height)
    }

    @Test
    fun `width and height are at least one`() {
        val bounds = CropBoundsCalculator.clamp(
            bitmapWidth = 100,
            bitmapHeight = 80,
            left = 99,
            top = 79,
            width = 0,
            height = 0
        )

        assertEquals(99, bounds.x)
        assertEquals(79, bounds.y)
        assertEquals(1, bounds.width)
        assertEquals(1, bounds.height)
    }
}
