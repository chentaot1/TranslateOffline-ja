package com.yomu.ml

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class LetterboxTest {

    @Test
    fun portraitScreenKeepsItsAspectAndPadsTheShortSideToTheStride() {
        val box = Letterbox(width = 1280, height = 2856, inputSize = 1280)

        assertEquals(574, box.scaledWidth)
        assertEquals(1280, box.scaledHeight)
        assertEquals(576, box.inputWidth)
        assertEquals(1280, box.inputHeight)
    }

    @Test
    fun landscapeScreenFitsItsLongSideToTheInput() {
        val box = Letterbox(width = 2856, height = 1280, inputSize = 1280)

        assertEquals(1280, box.inputWidth)
        assertEquals(576, box.inputHeight)
    }

    @Test
    fun aBoxFoundInTheModelInputMapsBackToSourcePixels() {
        val box = Letterbox(width = 2560, height = 5120, inputSize = 1280)

        assertArrayEquals(
            floatArrayOf(400f, 800f, 1200f, 1600f),
            box.toSource(floatArrayOf(100f, 200f, 300f, 400f)),
            1e-3f
        )
    }
}
