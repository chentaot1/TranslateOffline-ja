package com.yomu.ml

import java.io.ByteArrayOutputStream

/**
 * Cuts streamed reply bytes into lines at newline bytes and decodes each line only once it is whole.
 * A newline byte never occurs inside a multi-byte UTF-8 sequence, so a character split across chunks
 * is whole again before it is decoded (#235). An unterminated tail is emitted only by [finish].
 */
internal class ReplyLineSplitter(private val onLine: (String) -> Unit) {
    private val pending = ByteArrayOutputStream()

    fun feed(bytes: ByteArray) {
        var start = 0
        bytes.forEachIndexed { index, byte ->
            if (byte == NEWLINE) {
                pending.write(bytes, start, index - start)
                onLine(decodeGenerated(pending.toByteArray()))
                pending.reset()
                start = index + 1
            }
        }
        pending.write(bytes, start, bytes.size - start)
    }

    /**
     * Emits the unterminated tail as the last line. Only for a reply the model ended: a reply cut off
     * mid-line must leave that line unanswered (#368).
     */
    fun finish() {
        if (pending.size() > 0) onLine(decodeGenerated(pending.toByteArray()))
        pending.reset()
    }

    private companion object {
        const val NEWLINE = '\n'.code.toByte()
    }
}
