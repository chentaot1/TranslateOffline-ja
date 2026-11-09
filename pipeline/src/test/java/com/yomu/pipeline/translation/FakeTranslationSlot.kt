package com.yomu.pipeline.translation

import com.yomu.core.PageTranslation
import com.yomu.core.TranslatablePage
import com.yomu.core.TranslationSlot
import com.yomu.core.TranslationStatus

/** Answers every page with one canned reply, recording each page it was asked to translate. */
internal class FakeTranslationSlot(
    private val result: PageTranslation,
    private val reports: List<Pair<Int, String>> = emptyList()
) : TranslationSlot {
    override var status: TranslationStatus = TranslationStatus.Ready
    val pages = mutableListOf<TranslatablePage>()
    var endSessionCalls = 0

    /** Keeps the fake ready without loading model resources. */
    override suspend fun ensureReady(): Boolean = true

    /** Records the requested page, emits configured reports in order, and returns the canned final response. */
    override suspend fun translatePage(
        page: TranslatablePage,
        onBubble: (bubbleId: Int, text: String) -> Unit
    ): PageTranslation {
        pages += page
        reports.forEach { (id, text) -> onBubble(id, text) }
        return result
    }

    override fun endSession() {
        endSessionCalls++
    }

    override fun close() {
        status = TranslationStatus.NotReady
    }

    fun pagePairs(): List<List<Pair<Int, String>>> = pages.single().panels.map { panel ->
        panel.map { it.bubbleId to it.sourceText }
    }
}
