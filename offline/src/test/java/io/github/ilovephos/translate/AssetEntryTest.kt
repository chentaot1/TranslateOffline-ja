package io.github.ilovephos.translate

import org.junit.Assert.*
import org.junit.Test

class AssetEntryTest {
    @Test fun permitsKnownModelNamesAndLargeFiles() {
        val entry = AssetEntry("encoder.int8.onnx", 1298756608L, "a".repeat(64))
        assertEquals(1298756608L, entry.bytes)
    }

    @Test fun rejectsTraversalInvalidHashAndEmptyFiles() {
        for ((name, size, hash) in listOf(
            Triple("../escape", 1L, "a".repeat(64)),
            Triple("/escape", 1L, "a".repeat(64)),
            Triple("model.onnx", 0L, "a".repeat(64)),
            Triple("model.onnx", 1L, "not-a-hash")
        )) {
            try { AssetEntry(name, size, hash); fail("Accepted invalid entry") }
            catch (_: IllegalArgumentException) { }
        }
    }
}
