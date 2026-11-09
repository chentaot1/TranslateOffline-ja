package io.github.ilovephos.translate

import org.junit.Assert.*
import org.junit.Test

class InstalledLanguageTest {
    @Test fun eachInstallerPinsItsSourceAndApplicationIdentity() {
        val language = SourceLanguage.valueOf(BuildConfig.SOURCE_LANGUAGE)
        when (BuildConfig.FLAVOR) {
            "japanese" -> {
                assertEquals(SourceLanguage.JAPANESE, language)
                assertEquals("io.github.ilovephos.translate", BuildConfig.APPLICATION_ID)
                assertEquals(0, language.requiredAssets.count { it.endsWith(".gguf") })
                assertEquals(1, language.requiredAssets.count { it == "encoder.int8.onnx" })
            }
            "chinese" -> {
                assertEquals(SourceLanguage.CHINESE, language)
                assertEquals("io.github.ilovephos.translate.chinese", BuildConfig.APPLICATION_ID)
                assertEquals(1, language.requiredAssets.count { it.endsWith(".gguf") })
            }
            else -> fail("Unexpected language package")
        }
    }
}
