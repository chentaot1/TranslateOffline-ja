package com.yomu.ml

import org.junit.Assert.assertEquals
import org.junit.Test

class GeneratedTextDecodingTest {

    @Test
    fun validUtf8DecodesUnchanged() {
        assertEquals("こんにちは", decodeGenerated("こんにちは".toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun truncatedMultiByteTailBecomesReplacementCharacter() {
        val bytes = "Hi あ".toByteArray(Charsets.UTF_8).dropLast(1).toByteArray()
        assertEquals("Hi \uFFFD", decodeGenerated(bytes))
    }

    @Test
    fun invalidByteBecomesReplacementCharacter() {
        val bytes = byteArrayOf('a'.code.toByte(), 0xFF.toByte(), 'b'.code.toByte())
        assertEquals("a\uFFFDb", decodeGenerated(bytes))
    }

    @Test
    fun nullDecodesToEmpty() {
        assertEquals("", decodeGenerated(null))
    }
}
