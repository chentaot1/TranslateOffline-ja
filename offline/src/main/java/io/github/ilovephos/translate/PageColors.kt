package io.github.ilovephos.translate

import kotlin.math.pow

/** Adapted from BubbleColorSampler/BubbleTextColorResolver, jedzqer/manga-translator-android,
 * commit b729828 (Copyright 2026 jedzqer, AGPL-3.0). Simplified to captured-page samples.
 */
object PageColors {
    fun background(samples: IntArray): Int {
        if (samples.isEmpty()) return -1
        fun median(shift: Int): Int {
            val sorted = samples.map { (it ushr shift) and 255 }.sorted()
            return (sorted[(sorted.size - 1) / 2] + sorted[sorted.size / 2]) / 2
        }
        return 0xff000000.toInt() or (median(16) shl 16) or (median(8) shl 8) or median(0)
    }

    fun text(background: Int): Int {
        fun linear(shift: Int): Double {
            val value = ((background ushr shift) and 255) / 255.0
            return if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
        }
        val luminance = 0.2126 * linear(16) + 0.7152 * linear(8) + 0.0722 * linear(0)
        return if (1.05 / (luminance + 0.05) > (luminance + 0.05) / 0.05) -1 else 0xff000000.toInt()
    }
}
