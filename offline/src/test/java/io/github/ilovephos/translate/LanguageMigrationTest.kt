package io.github.ilovephos.translate

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File

class LanguageMigrationTest {
    @get:Rule val temporary = TemporaryFolder()
    private val entry = AssetEntry("model.gguf", 5, "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824")

    @Test fun verifiesSelectedModelsThenRetiresOnlyObsoleteLanguageFiles() = runBlocking {
        val root = temporary.newFolder()
        val old = File(root, "other.gguf").apply { writeText("old") }
        val partial = File(root, "other.gguf.part").apply { writeText("old partial") }
        val unrelated = File(root, "notes.txt").apply { writeText("keep") }
        val installer = AssetInstaller(root, listOf(entry), "identity",
            { ByteArrayInputStream("hello".toByteArray()) }, { Long.MAX_VALUE },
            retiredNames = setOf("other.gguf"))
        installer.prepare { }
        assertEquals("hello", File(root, entry.name).readText())
        assertFalse(old.exists())
        assertFalse(partial.exists())
        assertEquals("keep", unrelated.readText())
    }

    @Test fun failedPreparationPreservesLegacyModelsForRetry() = runBlocking {
        val root = temporary.newFolder()
        val old = File(root, "other.gguf").apply { writeText("old") }
        val installer = AssetInstaller(root, listOf(entry), "identity",
            { ByteArrayInputStream("wrong".toByteArray()) }, { Long.MAX_VALUE },
            retiredNames = setOf("other.gguf"))
        try { installer.prepare { }; fail("Expected checksum failure") } catch (_: IllegalStateException) { }
        assertEquals("old", old.readText())
    }

    @Test fun rejectsRetirementOfActiveOrOutsideFiles() {
        val root = temporary.newFolder()
        for (name in listOf(entry.name, "../outside.gguf", "ready.sha256", "${entry.name}.part")) {
            assertThrows(IllegalArgumentException::class.java) {
                AssetInstaller(root, listOf(entry), "identity", { ByteArrayInputStream(ByteArray(0)) },
                    { Long.MAX_VALUE }, retiredNames = setOf(name))
            }
        }
    }
}
