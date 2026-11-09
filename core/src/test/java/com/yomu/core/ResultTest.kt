package com.yomu.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResultTest {

    @Test
    fun success_holds_value() {
        val result = Result.Success(42)
        assertTrue(result is Result.Success)
        assertEquals(42, (result as Result.Success).data)
    }

    @Test
    fun error_holds_exception() {
        val e = RuntimeException("test error")
        val result = Result.Error(e)
        assertTrue(result is Result.Error)
        assertEquals(e, (result as Result.Error).exception)
    }

    @Test
    fun loading_is_loading() {
        assertTrue(Result.Loading is Result.Loading)
    }

    @Test
    fun isSuccess_returns_true_for_success() {
        assertTrue(Result.Success("ok").isSuccess)
    }

    @Test
    fun isSuccess_returns_false_for_error() {
        assertTrue(!Result.Error(RuntimeException()).isSuccess)
    }

    @Test
    fun isSuccess_returns_false_for_loading() {
        assertTrue(!Result.Loading.isSuccess)
    }

    @Test
    fun isError_returns_true_for_error() {
        assertTrue(Result.Error(RuntimeException()).isError)
    }

    @Test
    fun isError_returns_false_for_success() {
        assertTrue(!Result.Success("ok").isError)
    }

    @Test
    fun isError_returns_false_for_loading() {
        assertTrue(!Result.Loading.isError)
    }

    @Test
    fun isLoading_returns_true_for_loading() {
        assertTrue(Result.Loading.isLoading)
    }

    @Test
    fun isLoading_returns_false_for_success() {
        assertTrue(!Result.Success("ok").isLoading)
    }

    @Test
    fun getOrNull_returns_value_for_success() {
        assertEquals("hello", Result.Success("hello").getOrNull())
    }

    @Test
    fun getOrNull_returns_null_for_error() {
        assertNull(Result.Error(RuntimeException()).getOrNull())
    }

    @Test
    fun getOrNull_returns_null_for_loading() {
        assertNull(Result.Loading.getOrNull())
    }

    @Test(expected = RuntimeException::class)
    fun getOrThrow_throws_for_error() {
        Result.Error(RuntimeException("fail")).getOrThrow()
    }

    @Test(expected = IllegalStateException::class)
    fun getOrThrow_throws_for_loading() {
        Result.Loading.getOrThrow()
    }

    @Test
    fun getOrThrow_returns_value_for_success() {
        assertEquals(99, Result.Success(99).getOrThrow())
    }

    @Test
    fun map_transforms_success_value() {
        val result = Result.Success(10).map { it * 2 }
        assertEquals(20, result.getOrNull())
    }

    @Test
    fun map_preserves_error() {
        val e = RuntimeException("original")
        val result = Result.Error(e).map<Int> { 0 }
        assertTrue(result is Result.Error)
        assertEquals(e, (result as Result.Error).exception)
    }

    @Test
    fun map_preserves_loading() {
        val result = Result.Loading.map<Int> { 0 }
        assertTrue(result is Result.Loading)
    }
}
