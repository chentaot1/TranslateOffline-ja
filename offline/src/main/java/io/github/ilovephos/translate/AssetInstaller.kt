package io.github.ilovephos.translate

import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/** Installs verified package files, preserving destinations until a replacement is complete. */
internal class AssetInstaller(
    private val root: File,
    private val entries: List<AssetEntry>,
    private val identity: String,
    private val openAsset: (String) -> InputStream,
    private val availableBytes: () -> Long,
    private val reserveBytes: Long = 64L * 1024 * 1024,
    private val retiredNames: Set<String> = emptySet()
) {
    companion object {
        private val lock = Mutex()
        private const val MARKER = "ready.sha256"
    }

    init {
        val names = entries.map { it.name }.toSet()
        require(entries.isNotEmpty() && names.size == entries.size) { "Invalid asset manifest" }
        require(names.none { it == MARKER || it == "$MARKER.part" || "$it.part" in names }) {
            "Asset names overlap installer files"
        }
        require(reserveBytes >= 0) { "Invalid storage reserve" }
        require(retiredNames.all { name ->
            name.matches(Regex("[a-z0-9][a-z0-9_.-]*")) && !name.contains("..") &&
                name !in names && names.none { name == "$it.part" || "$name.part" == it } &&
                name != MARKER && name != "$MARKER.part"
        }) { "Invalid retired model name" }
    }

    /** Hashes every existing destination and reclaims abandoned copies before checking space. */
    suspend fun prepare(progress: (Int) -> Unit) = lock.withLock {
        coroutineContext.ensureActive()
        check(root.isDirectory || root.mkdirs()) { "Could not create private model storage" }
        entries.forEach { ownedFile(it.name) }
        val marker = ownedFile(MARKER)
        Files.deleteIfExists(marker.toPath())
        val markerPart = ownedFile("$MARKER.part")
        Files.deleteIfExists(markerPart.toPath())
        for (entry in entries) {
            coroutineContext.ensureActive()
            Files.deleteIfExists(ownedFile("${entry.name}.part").toPath())
        }
        val invalid = entries.filter { entry ->
            val file = ownedFile(entry.name)
            !file.isFile || file.length() != entry.bytes || hashFile(file) != entry.sha256
        }.toSet()
        val required = invalid.fold(reserveBytes) { bytes, entry -> Math.addExact(bytes, entry.bytes) }
        if (invalid.isNotEmpty()) {
            check(availableBytes() >= required) {
                "Need ${(required + 1024 * 1024 - 1) / (1024 * 1024)} MB of free space to prepare the models"
            }
        }
        val total = entries.fold(0L) { bytes, entry -> Math.addExact(bytes, entry.bytes) }
        var completed = 0L
        for (entry in entries) {
            coroutineContext.ensureActive()
            if (entry in invalid) {
                check(availableBytes() >= Math.addExact(entry.bytes, reserveBytes)) {
                    "Not enough storage for ${entry.name}"
                }
                install(entry) { written -> progress(percent(completed + written, total)) }
            }
            completed += entry.bytes
            progress(percent(completed, total))
        }
        coroutineContext.ensureActive()
        // An upgrade releases the other language's private copies only after the selected bundle verifies.
        for (name in retiredNames) {
            coroutineContext.ensureActive()
            Files.deleteIfExists(ownedFile(name).toPath())
            Files.deleteIfExists(ownedFile("$name.part").toPath())
        }
        try {
            FileOutputStream(markerPart).use { output ->
                output.write(identity.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            coroutineContext.ensureActive()
            replace(markerPart, marker)
        } finally {
            Files.deleteIfExists(markerPart.toPath())
        }
        progress(100)
    }

    private suspend fun install(entry: AssetEntry, progress: (Long) -> Unit) {
        val temporary = ownedFile("${entry.name}.part")
        try {
            val checksum = MessageDigest.getInstance("SHA-256")
            var written = 0L
            openAsset(entry.name).use { input ->
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(1024 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        coroutineContext.ensureActive()
                        if (count < 0) break
                        check(count.toLong() <= entry.bytes - written) { "Bundled model is oversized: ${entry.name}" }
                        output.write(buffer, 0, count)
                        checksum.update(buffer, 0, count)
                        written += count
                        progress(written)
                    }
                    output.fd.sync()
                }
            }
            check(written == entry.bytes && hex(checksum.digest()) == entry.sha256) {
                "Bundled model failed verification: ${entry.name}"
            }
            coroutineContext.ensureActive()
            replace(temporary, ownedFile(entry.name))
        } finally {
            Files.deleteIfExists(temporary.toPath())
        }
    }

    private suspend fun hashFile(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                coroutineContext.ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        coroutineContext.ensureActive()
        return hex(digest.digest())
    }

    private fun ownedFile(name: String): File {
        val file = File(root, name)
        check(!Files.isSymbolicLink(file.toPath()) && file.canonicalFile.parentFile == root.canonicalFile) {
            "Model path escapes private storage: $name"
        }
        return file
    }

    private fun replace(source: File, destination: File) {
        try {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun percent(completed: Long, total: Long): Int = (completed.toDouble() / total * 100).toInt().coerceIn(0, 99)
    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
}
