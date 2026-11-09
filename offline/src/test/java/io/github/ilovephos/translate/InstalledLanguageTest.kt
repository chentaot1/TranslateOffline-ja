package io.github.ilovephos.translate

import org.junit.Assert.*
import org.junit.Test

class InstalledLanguageTest {
    @Test fun installerPinsJapaneseSourceAndApplicationIdentity() {
        val language = SourceLanguage.valueOf(BuildConfig.SOURCE_LANGUAGE)
        assertEquals("japanese", BuildConfig.FLAVOR)
        assertEquals(SourceLanguage.JAPANESE, language)
        assertEquals("io.github.ilovephos.translate", BuildConfig.APPLICATION_ID)
        assertEquals(0, language.requiredAssets.count { it.endsWith(".gguf") })
        assertEquals(1, language.requiredAssets.count { it == "encoder.int8.onnx" })
    }
}
