package io.github.ilovephos.translate

/** The fixed source language of an installer and the exact package-owned files it may prepare. */
enum class SourceLanguage(val label: String, val model: String) {
    JAPANESE("Japanese → English", "encoder.int8.onnx"),
    CHINESE("Chinese → English", "hy_mt2.gguf");

    val requiredAssets: Set<String>
        get() = setOf("manga_panel_detector.onnx") + when (this) {
            JAPANESE -> setOf(
                "encoder.int8.onnx",
                "decoder.int8.onnx",
                "enc-vocab.json",
                "dec-vocab.json",
                "meta.json",
                "sfx-filter-v0.3.onnx",
                "manga_ocr_nar.onnx",
                "nar_vocabulary_6817.json",
                "chinese_detection.onnx",
                "manga_ocr_ar_encoder.onnx",
                "manga_ocr_ar_decoder.onnx"
            )
            CHINESE -> setOf(model, "chinese_detection.onnx", "chinese_recognition.onnx", "chinese_characters.json")
        }

    val retiredAssets: Set<String>
        get() = ((entries.flatMap { it.requiredAssets }.toSet() - requiredAssets) +
            setOf("bubble_detection.onnx", "manga_ocr_encoder.onnx", "manga_ocr_decoder.onnx", "vocab.txt", "cat_translate.gguf", "hy_mt2.gguf")) - requiredAssets

    fun accepts(assets: List<AssetEntry>): Boolean =
        assets.size == requiredAssets.size && assets.map { it.name }.toSet() == requiredAssets
}
