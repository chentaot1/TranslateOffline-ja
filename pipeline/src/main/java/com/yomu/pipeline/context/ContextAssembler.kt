package com.yomu.pipeline.context

import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import com.yomu.pipeline.bubble.Bubble
import com.yomu.pipeline.ocr.OcrResult
import kotlin.math.ceil

data class ConversationBlock(
    val blockId: Int,
    val bubbles: List<Bubble>,
    val texts: List<OcrResult>,
    val readingOrder: List<Int>,
    val textByBubbleId: Map<Int, OcrResult> = emptyMap()
)

data class PageContext(
    val blocks: List<ConversationBlock>,
    val pageWidth: Int,
    val pageHeight: Int
)

/**
 * Groups a page's bubbles into panels in manga reading order; the engine renders panel
 * boundaries as prompt markers (ADR-0002), never as call boundaries.
 *
 * Panels come from the drawn gutters. The page is cut along paper rows into tiers, read top to
 * bottom, then along paper columns into panels, read right to left, recursively. Bubble boxes
 * alone cannot find them: on balloon-dense-dialogue two bubbles of one panel sit further apart
 * than the gutters between tiers, and grouping by box gaps merged the whole page into one panel.
 *
 * Straight gutters only. A slanted gutter, or art crossing one, keeps those panels
 * together and their bubbles fall back to row order; trace panel borders if that misreads pages.
 *
 * Evaluates paper gutters directly on [page] on-demand with zero extra memory allocations,
 * keeping the legacy 4.5 MB PaperMask allocator retired.
 */
class ContextAssembler {

    fun assemble(
        bubbles: List<Bubble>,
        ocrResults: Map<Int, OcrResult>,
        page: Bitmap
    ): PageContext {
        if (bubbles.isEmpty()) {
            return PageContext(
                blocks = emptyList(),
                pageWidth = page.width,
                pageHeight = page.height
            )
        }

        val region = readingArea(bubbles, page)
        val panels = panelsInReadingOrder(page, region, bubbles)

        return PageContext(
            blocks = panels.mapIndexed { index, panel -> createBlock(index + 1, panel, ocrResults) },
            pageWidth = page.width,
            pageHeight = page.height
        )
    }

    private fun readingArea(bubbles: List<Bubble>, page: Bitmap): Rect {
        val box = Rect()
        val area = Rect()
        for ((idx, bubble) in bubbles.withIndex()) {
            bubble.boundingBox.roundOut(box)
            if (idx == 0) area.set(box) else area.union(box)
        }
        area.intersect(0, 0, page.width, page.height)
        return area
    }

    private fun panelsInReadingOrder(page: Bitmap, region: Rect, bubbles: List<Bubble>): List<List<Bubble>> {
        if (bubbles.size < 2) return listOf(bubbles)

        val tiers = splitAtGutters(bubbles, { it.top }, { it.bottom }, region.top, region.bottom) { y ->
            isPaperRow(page, y, region.left, region.right)
        }
        if (tiers.size > 1) {
            return tiers.flatMap { tier ->
                panelsInReadingOrder(page, Rect(region.left, tier.from, region.right, tier.to), tier.bubbles)
            }
        }

        val columns = splitAtGutters(bubbles, { it.left }, { it.right }, region.left, region.right) { x ->
            isPaperColumn(page, x, region.top, region.bottom)
        }
        if (columns.size > 1) {
            return columns.asReversed().flatMap { column ->
                panelsInReadingOrder(page, Rect(column.from, region.top, column.to, region.bottom), column.bubbles)
            }
        }

        return listOf(inPanelOrder(bubbles))
    }

    /** Rows of vertically overlapping bubbles, top to bottom, each read right to left. */
    private fun inPanelOrder(bubbles: List<Bubble>): List<Bubble> =
        splitAtGutters(bubbles, { it.top }, { it.bottom }, 0, Int.MAX_VALUE, isGutter = { true })
            .flatMap { row -> row.bubbles.sortedByDescending { it.boundingBox.centerX() } }

    private fun isPaperRow(page: Bitmap, y: Int, fromX: Int, toX: Int): Boolean {
        if (y < 0 || y >= page.height) return false
        val startX = fromX.coerceIn(0, page.width - 1)
        val endX = toX.coerceIn(startX, page.width)
        for (x in startX until endX) {
            if (luminance(page.getPixel(x, y)) < PAPER_LUMINANCE) return false
        }
        return true
    }

    private fun isPaperColumn(page: Bitmap, x: Int, fromY: Int, toY: Int): Boolean {
        if (x < 0 || x >= page.width) return false
        val startY = fromY.coerceIn(0, page.height - 1)
        val endY = toY.coerceIn(startY, page.height)
        for (y in startY until endY) {
            if (luminance(page.getPixel(x, y)) < PAPER_LUMINANCE) return false
        }
        return true
    }

    private fun luminance(color: Int): Int {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        return (r * 299 + g * 587 + b * 114) / 1000
    }

    private class Strip(val from: Int, val to: Int, val bubbles: List<Bubble>)

    private fun splitAtGutters(
        bubbles: List<Bubble>,
        start: (RectF) -> Float,
        end: (RectF) -> Float,
        from: Int,
        to: Int,
        isGutter: (Int) -> Boolean
    ): List<Strip> {
        val sorted = bubbles.sortedBy { start(it.boundingBox) }
        val strips = mutableListOf<Strip>()
        var stripFrom = from
        var group = mutableListOf(sorted.first())
        var reach = end(sorted.first().boundingBox)

        for (bubble in sorted.drop(1)) {
            val gap = maxOf(ceil(reach).toInt(), from) until minOf(start(bubble.boundingBox).toInt(), to)
            val cut = gap.firstOrNull(isGutter)
            if (cut != null) {
                strips.add(Strip(stripFrom, cut, group))
                stripFrom = cut
                group = mutableListOf()
            }
            group.add(bubble)
            reach = maxOf(reach, end(bubble.boundingBox))
        }
        strips.add(Strip(stripFrom, to, group))
        return strips
    }

    private fun createBlock(
        blockId: Int,
        bubbles: List<Bubble>,
        ocrResults: Map<Int, OcrResult>
    ): ConversationBlock {
        val textByBubbleId = bubbles.mapNotNull { bubble ->
            ocrResults[bubble.id]?.let { result -> bubble.id to result }
        }.toMap()
        val texts = bubbles.mapNotNull { textByBubbleId[it.id] }
        val readingOrder = bubbles.map { it.id }

        return ConversationBlock(
            blockId = blockId,
            bubbles = bubbles,
            texts = texts,
            readingOrder = readingOrder,
            textByBubbleId = textByBubbleId
        )
    }

    companion object {
        private const val PAPER_LUMINANCE = 200
    }
}
