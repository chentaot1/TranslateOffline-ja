package com.yomu.core

sealed class TranslationStatus {
    data object NotReady : TranslationStatus()
    data object Downloading : TranslationStatus()
    data object Ready : TranslationStatus()
    data class Error(val reason: String) : TranslationStatus()
}

data class TranslatableBubble(val bubbleId: Int, val sourceText: String)

data class TranslatablePage(
    val panels: List<List<TranslatableBubble>>,
    val targetLanguage: TargetLanguage = TargetLanguage.ENGLISH
)

/** How a page-level translation call ended, typed at the translation boundary. */
enum class TranslationOutcome { SUCCESS, BLANK, TIMEOUT, OVERFLOW, ERROR, NOT_LOADED }

/** The slot could not be made ready because its weights are not on disk. */
const val ERROR_MODEL_MISSING = "model_missing"

/** An English-only model was asked for another [TargetLanguage] and refused, rather than write English. */
const val ERROR_ENGLISH_ONLY = "english_only"

data class PageTranslation(
    val byId: Map<Int, String>,
    val rawResponse: String,
    val durationMs: Long,
    val outcome: TranslationOutcome = TranslationOutcome.SUCCESS,
    /** Machine-readable cause, never prose: `load_failed`, `model_missing`, an exception class. */
    val errorCode: String? = null,
    val batchOverflowFallback: Boolean = false
) {
    companion object {
        /** The empty page every slot returns when it could not be made ready, with its reason. */
        fun notLoaded(status: TranslationStatus): PageTranslation = PageTranslation(
            emptyMap(),
            "",
            0L,
            TranslationOutcome.NOT_LOADED,
            (status as? TranslationStatus.Error)?.reason ?: "not_ready"
        )
    }
}

interface TranslationSlot {
    val status: TranslationStatus

    /** Attempts to prepare the selected model for translation and returns whether the slot is ready. */
    suspend fun ensureReady(): Boolean

    /**
     * [onBubble] hears each bubble at most once, as soon as its text is final, and never with text
     * that contradicts the returned [PageTranslation.byId].
     */
    suspend fun translatePage(
        page: TranslatablePage,
        onBubble: (bubbleId: Int, text: String) -> Unit = { _, _ -> }
    ): PageTranslation

    /** Ends the current reading session so session-specific state can be cleared. */
    fun endSession()

    /** Releases resources held by the translation slot. */
    fun close()
}
