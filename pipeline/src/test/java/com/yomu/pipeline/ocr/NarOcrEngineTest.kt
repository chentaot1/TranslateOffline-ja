package com.yomu.pipeline.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NarOcrEngineTest {

    @Test
    fun computeGrid_fitsWithinMaxBudget() {
        val engine = NarOcrEngine()
        val grid = engine.computeGrid(200, 400)

        assertTrue("Grid area ${grid.gridH * grid.gridW} exceeds budget 324", grid.gridH * grid.gridW <= 324)
        assertTrue(grid.gridH > 0)
        assertTrue(grid.gridW > 0)
        assertTrue(grid.resizedH > 0)
        assertTrue(grid.resizedW > 0)
    }

    @Test
    fun computeGrid_preservesAspectRatioApproximately() {
        val engine = NarOcrEngine()
        val grid = engine.computeGrid(100, 300)

        assertTrue(grid.gridH > grid.gridW)
        assertTrue(grid.gridH * grid.gridW <= 324)
    }

    @Test
    fun computeGrid_handlesSquareImage() {
        val engine = NarOcrEngine()
        val grid = engine.computeGrid(250, 250)

        assertEquals(grid.gridH, grid.gridW)
        assertTrue(grid.gridH * grid.gridW <= 324)
    }
}
