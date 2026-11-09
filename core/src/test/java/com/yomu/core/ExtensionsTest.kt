package com.yomu.core

import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ExtensionsTest {

    @Test
    fun toFileSizeString_returns_bytes() {
        assertEquals("500 B", 500L.toFileSizeString())
    }

    @Test
    fun toFileSizeString_returns_kilobytes() {
        assertEquals("1.00 KB", 1024L.toFileSizeString())
    }

    @Test
    fun toFileSizeString_returns_megabytes() {
        assertEquals("1.00 MB", (1024L * 1024).toFileSizeString())
    }

    @Test
    fun toFileSizeString_returns_gigabytes() {
        assertEquals("1.00 GB", (1024L * 1024 * 1024).toFileSizeString())
    }

    @Test
    fun toFileSizeString_returns_2gb() {
        assertEquals("2.00 GB", (2L * 1024 * 1024 * 1024).toFileSizeString())
    }

    @Test
    fun toFileSizeString_returns_0b() {
        assertEquals("0 B", 0L.toFileSizeString())
    }

    @Test
    fun handleErrors_catches_exception() = runTest {
        var caught: Throwable? = null
        val e = RuntimeException("test")
        val flow = flow<Int> { throw e }.handleErrors { caught = it }
        flow.collect {}
        assertEquals(e, caught)
    }

    @Test
    fun handleErrors_passes_through_values() = runTest {
        val values = mutableListOf<Int>()
        val flow = flowOf(1, 2, 3).handleErrors { }
        flow.collect { values.add(it) }
        assertEquals(listOf(1, 2, 3), values)
    }
}
