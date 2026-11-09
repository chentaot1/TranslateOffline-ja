package io.github.ilovephos.translate

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream

class AssetInstallerTest {
    @get:Rule val temporary = TemporaryFolder()
    private val data = "hello".toByteArray()
    private val entry = AssetEntry("model.gguf", 5, "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824")
    private val identity = "a".repeat(64)

    @Test fun installsVerifiedBytesAndReusesValidDestination() = runBlocking {
        val root = temporary.newFolder()
        val progress = mutableListOf<Int>()
        installer(root).prepare(progress::add)
        assertArrayEquals(data, File(root, entry.name).readBytes())
        assertEquals(identity, File(root, "ready.sha256").readText())
        assertEquals(100, progress.last())
        assertTrue(progress.zipWithNext().all { (first, second) -> second >= first })
        installer(root, open = { error("Valid destinations must be reused") }, space = { 0 }).prepare { }
    }

    @Test fun repairsSameLengthCorruptionDespiteReadyMarker() = runBlocking {
        val root = temporary.newFolder()
        installer(root).prepare { }
        File(root, entry.name).writeText("jello")
        installer(root).prepare { }
        assertArrayEquals(data, File(root, entry.name).readBytes())
    }

    @Test fun repairsTruncatedDestination() = runBlocking {
        val root = temporary.newFolder()
        File(root, entry.name).writeText("he")
        installer(root).prepare { }
        assertArrayEquals(data, File(root, entry.name).readBytes())
    }

    @Test fun reclaimsOnlyOwnedTemporaryFilesBeforeCheckingFreeSpace() = runBlocking {
        val root = temporary.newFolder()
        val abandoned = File(root, "${entry.name}.part").apply { writeBytes(data) }
        val markerPart = File(root, "ready.sha256.part").apply { writeText(identity) }
        val unrelated = File(root, "notes.part").apply { writeText("keep") }
        installer(root, space = { if (abandoned.exists() || markerPart.exists()) 0 else 9 }).prepare { }
        assertArrayEquals(data, File(root, entry.name).readBytes())
        assertEquals("keep", unrelated.readText())
        assertFalse(abandoned.exists())
        assertFalse(markerPart.exists())
    }

    @Test fun insufficientSpacePreservesDestinationAndAllowsRetry() = runBlocking {
        val root = temporary.newFolder()
        val destination = File(root, entry.name).apply { writeText("old") }
        File(root, "ready.sha256").writeText(identity)
        expectFailure { installer(root, space = { 8 }).prepare { } }
        assertEquals("old", destination.readText())
        assertFalse(File(root, "${entry.name}.part").exists())
        assertFalse(File(root, "ready.sha256").exists())
        installer(root, space = { 9 }).prepare { }
        assertArrayEquals(data, destination.readBytes())
    }

    @Test fun rejectsWrongHashAndLeavesDestinationAvailableForRetry() = runBlocking {
        val root = temporary.newFolder()
        val destination = File(root, entry.name).apply { writeText("old") }
        val progress = mutableListOf<Int>()
        expectFailure { installer(root, open = { ByteArrayInputStream("jello".toByteArray()) }).prepare(progress::add) }
        assertEquals("old", destination.readText())
        assertFalse(progress.contains(100))
        assertFalse(File(root, "${entry.name}.part").exists())
        assertFalse(File(root, "ready.sha256").exists())
        installer(root).prepare { }
        assertArrayEquals(data, destination.readBytes())
    }

    @Test fun cancellationRemovesPartialCopyAndRetryInstallsIt() {
        val root = temporary.newFolder()
        File(root, entry.name).writeText("old")
        try {
            runBlocking {
                val job = currentCoroutineContext().job
                val input = object : ByteArrayInputStream(data) {
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        val count = super.read(buffer, offset, 1)
                        job.cancel()
                        return count
                    }
                }
                installer(root, open = { input }).prepare { }
            }
            fail("Cancellation was swallowed")
        } catch (_: CancellationException) { }
        assertEquals("old", File(root, entry.name).readText())
        assertFalse(File(root, "${entry.name}.part").exists())
        assertFalse(File(root, "ready.sha256").exists())
        runBlocking { installer(root).prepare { } }
        assertArrayEquals(data, File(root, entry.name).readBytes())
    }

    @Test fun cancellationAtEndOfInputCannotPublishReadyState() {
        val root = temporary.newFolder()
        File(root, entry.name).writeText("old")
        try {
            runBlocking {
                val job = currentCoroutineContext().job
                val input = object : ByteArrayInputStream(data) {
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        val count = super.read(buffer, offset, length)
                        if (count < 0) job.cancel()
                        return count
                    }
                }
                installer(root, open = { input }).prepare { }
            }
            fail("Cancellation was swallowed")
        } catch (_: CancellationException) { }
        assertEquals("old", File(root, entry.name).readText())
        assertFalse(File(root, "ready.sha256").exists())
        assertFalse(File(root, "${entry.name}.part").exists())
        runBlocking { installer(root).prepare { } }
        assertArrayEquals(data, File(root, entry.name).readBytes())
    }

    private fun installer(
        root: File,
        open: (String) -> InputStream = { ByteArrayInputStream(data) },
        space: () -> Long = { Long.MAX_VALUE }
    ) = AssetInstaller(root, listOf(entry), identity, open, space, reserveBytes = 4)

    private suspend fun expectFailure(action: suspend () -> Unit) {
        try { action(); fail("Expected preparation failure") }
        catch (_: IllegalStateException) { }
    }
}
