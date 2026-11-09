package com.yomu.core

import org.junit.Assert.assertEquals
import org.junit.Test

class NameGlossaryTest {

    @Test
    fun `the longest term wins where two terms overlap`() {
        val glossary = listOf(GlossaryEntry("ルフィ", "Luffy"), GlossaryEntry("ルフィ先輩", "Luffy-senpai"))

        assertEquals("Luffy-senpaiとLuffy", glossary.applyTo("ルフィ先輩とルフィ"))
    }

    @Test
    fun `an empty glossary leaves the source as it is`() {
        assertEquals("ルフィは…", emptyList<GlossaryEntry>().applyTo("ルフィは…"))
    }

    @Test
    fun `a rendering is never matched again by another term`() {
        val glossary = listOf(GlossaryEntry("ゾロ", "Zoro"), GlossaryEntry("Zo", "X"))

        assertEquals("Zoroだ", glossary.applyTo("ゾロだ"))
    }

    @Test
    fun `a rendering is inserted as written, even with replacement metacharacters`() {
        assertEquals("\$1 Kid", listOf(GlossaryEntry("キッド", "\$1 Kid")).applyTo("キッド"))
    }
}
