package com.yomu.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MangaSfxDictionaryTest {

    @Test
    fun lookup_resolvesCommonPunctuationAndReactions() {
        assertEquals("...", MangaSfxDictionary.lookup("…"))
        assertEquals("...", MangaSfxDictionary.lookup("……"))
        assertEquals("!", MangaSfxDictionary.lookup("!"))
        assertEquals("Ah!", MangaSfxDictionary.lookup("あっ"))
        assertEquals("Ah!", MangaSfxDictionary.lookup("アッ"))
        assertEquals("Eh?", MangaSfxDictionary.lookup("えっ"))
        assertEquals("*thump thump*", MangaSfxDictionary.lookup("ドキドキ"))
        assertEquals("*thump thump*", MangaSfxDictionary.lookup("どきどき"))
        assertEquals("*giggle*", MangaSfxDictionary.lookup("クスクス"))
        assertEquals("*chuckle*", MangaSfxDictionary.lookup("フフッ"))
        assertEquals("*boom!*", MangaSfxDictionary.lookup("ドン"))
        assertEquals("*slurp*", MangaSfxDictionary.lookup("ズズッ"))
    }

    @Test
    fun lookup_handlesWhitespaceAndFullWidthSpaces() {
        assertEquals("*thump thump*", MangaSfxDictionary.lookup(" ドキドキ "))
        assertEquals("*thump thump*", MangaSfxDictionary.lookup("　ドキドキ　"))
        assertEquals("*gasp!*", MangaSfxDictionary.lookup("　ハッ　"))
    }

    @Test
    fun lookup_closesItalicsForEepReaction() {
        assertEquals("*eep!*", MangaSfxDictionary.lookup("ヒッ"))
        assertEquals("*eep!*", MangaSfxDictionary.lookup("ひっ"))
    }

    @Test
    fun lookup_returnsNullForRegularDialogue() {
        assertNull(MangaSfxDictionary.lookup("こんにちは"))
        assertNull(MangaSfxDictionary.lookup("お前は誰だ？"))
        assertNull(MangaSfxDictionary.lookup("明日の朝に会おう"))
    }
}
