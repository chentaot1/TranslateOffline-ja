package io.github.ilovephos.translate

/** A package-owned file; names cannot escape the app's private model directory. */
data class AssetEntry(val name: String, val bytes: Long, val sha256: String) {
    init {
        require(name.matches(Regex("[a-z0-9][a-z0-9_.-]*")) && !name.contains("..")) { "Invalid model name" }
        require(bytes > 0) { "Empty model asset" }
        require(sha256.matches(Regex("[0-9a-f]{64}"))) { "Invalid model checksum" }
    }
}
