package com.yomu.core

/** One name the reader pins (#327): every [term] in a bubble's source text becomes [rendering], whatever the target language. */
data class GlossaryEntry(val term: String, val rendering: String)

/**
 * [source] with every entry's term replaced by its rendering: exact substring, longest term first
 * where two overlap, in one pass so a rendering is never matched again. No word boundaries and no
 * kana or width normalization. An empty glossary returns [source] unchanged.
 *
 * Not prompt tuning, so ADR-0015 is untouched: the glossary is reader data, and it changes only
 * the source text, which keeps every prompt template byte-identical.
 */
fun List<GlossaryEntry>.applyTo(source: String): String {
    if (isEmpty()) return source
    val renderings = associate { it.term to it.rendering }
    val terms = Regex(renderings.keys.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) })
    return terms.replace(source) { renderings.getValue(it.value) }
}
