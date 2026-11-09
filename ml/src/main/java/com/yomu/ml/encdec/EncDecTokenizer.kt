package com.yomu.ml.encdec

import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader

/**
 * Pure Kotlin Unigram Viterbi tokenizer and flat array decoder for ModernBERT-ja / Fumeto pipeline.
 * Zero SentencePiece C++ native library dependency.
 */
class EncDecTokenizer {

    companion object {
        const val ENC_UNK_ID = 0L
        const val BOS_ID = 1L
        const val EOS_ID = 2L
        const val ENC_PAD_ID = 3L
        const val ENC_SEP_ID = 4L
        const val ENC_BYTE_BASE = 15L

        const val DEC_PAD_ID = 0L
        const val DEC_UNK_ID = 3L
        const val DEC_BYTE_BASE = 4L

        private const val METASPACE = '\u2581'
        private const val BYTE_FALLBACK_SCORE_PER_BYTE = -100.0f

        fun load(encoderVocabFile: File, decoderVocabFile: File): EncDecTokenizer {
            val tokenizer = EncDecTokenizer()
            tokenizer.loadEncoderVocabFile(encoderVocabFile)
            tokenizer.loadDecoderVocabFile(decoderVocabFile)
            return tokenizer
        }
    }

    private class TrieNode {
        var tokenId: Long = -1L
        var score: Float = Float.NEGATIVE_INFINITY
        val children = HashMap<Char, TrieNode>()
    }

    private val rootNode = TrieNode()
    private val pieceToIdMap = HashMap<String, Long>()
    private val idToPieceList = ArrayList<String>()

    private var _decoderVocab: Array<String> = emptyArray()

    val encoderVocabSize: Int get() = idToPieceList.size
    val decoderVocabSize: Int get() = _decoderVocab.size

    fun pieceToId(piece: String): Long? = pieceToIdMap[piece]

    fun idToEncoderPiece(id: Long): String? {
        val i = id.toInt()
        return if (i in 0 until idToPieceList.size) {
            idToPieceList[i]
        } else if (id in ENC_BYTE_BASE until (ENC_BYTE_BASE + 256)) {
            String.format("<0x%02X>", id - ENC_BYTE_BASE)
        } else {
            null
        }
    }

    fun loadDecoderVocab(vocab: Array<String>) {
        _decoderVocab = vocab
    }

    fun loadDecoderVocab(vocab: List<String>) {
        _decoderVocab = vocab.toTypedArray()
    }

    fun loadDecoderVocabFile(file: File) {
        FileInputStream(file).use { stream ->
            loadDecoderVocabStream(stream)
        }
    }

    fun loadDecoderVocabStream(stream: InputStream) {
        val reader = BufferedReader(InputStreamReader(stream, Charsets.UTF_8), 65536)
        val pieces = ArrayList<String>(16000)

        // Find "pieces"
        skipUntilKey(reader, "pieces")
        // Find '['
        skipUntilChar(reader, '[')

        while (true) {
            val c = skipWhitespace(reader)
            if (c == -1 || c == ']'.code) break
            if (c == ','.code) continue
            if (c == '"'.code) {
                pieces.add(readJsonString(reader))
            }
        }
        _decoderVocab = pieces.toTypedArray()
    }

    fun loadEncoderVocab(pieces: List<Pair<String, Float>>) {
        clearEncoder()
        for ((index, pair) in pieces.withIndex()) {
            addEncoderPiece(pair.first, pair.second, index.toLong())
        }
    }

    fun loadEncoderVocabFile(file: File) {
        FileInputStream(file).use { stream ->
            loadEncoderVocabStream(stream)
        }
    }

    fun loadEncoderVocabStream(stream: InputStream) {
        clearEncoder()
        val reader = BufferedReader(InputStreamReader(stream, Charsets.UTF_8), 65536)

        // Find "vocab"
        skipUntilKey(reader, "vocab")
        // Find outer '['
        skipUntilChar(reader, '[')

        var index = 0L
        while (true) {
            val c = skipWhitespace(reader)
            if (c == -1 || c == ']'.code) break
            if (c == ','.code) continue
            if (c == '['.code) {
                // Read [piece, score]
                val qc = skipWhitespace(reader)
                if (qc != '"'.code) break
                val piece = readJsonString(reader)
                skipUntilChar(reader, ',')
                val score = readJsonFloat(reader)
                addEncoderPiece(piece, score, index)
                index++
            }
        }
    }

    private fun clearEncoder() {
        rootNode.children.clear()
        rootNode.tokenId = -1L
        rootNode.score = Float.NEGATIVE_INFINITY
        pieceToIdMap.clear()
        idToPieceList.clear()
    }

    private fun addEncoderPiece(piece: String, score: Float, tokenId: Long) {
        pieceToIdMap[piece] = tokenId
        while (idToPieceList.size <= tokenId) {
            idToPieceList.add("")
        }
        idToPieceList[tokenId.toInt()] = piece

        var curr = rootNode
        for (ch in piece) {
            curr = curr.children.getOrPut(ch) { TrieNode() }
        }
        curr.tokenId = tokenId
        curr.score = score
    }

    /**
     * Segments Japanese text into token IDs using Viterbi trellis dynamic programming.
     */
    fun encode(text: String): LongArray {
        if (text.isEmpty()) return LongArray(0)

        val s = text.replace(' ', METASPACE)
        val n = s.length

        val bestScore = FloatArray(n + 1) { Float.NEGATIVE_INFINITY }
        val bestPrev = IntArray(n + 1) { -1 }
        val bestTokens = Array<LongArray?>(n + 1) { null }

        bestScore[0] = 0.0f

        for (i in 0 until n) {
            if (bestScore[i] == Float.NEGATIVE_INFINITY) continue

            // 1. Trie prefix matching
            var node: TrieNode? = rootNode
            for (j in i until n) {
                node = node?.children?.get(s[j])
                if (node == null) break
                if (node.tokenId != -1L) {
                    val len = j + 1 - i
                    val candScore = bestScore[i] + node.score
                    if (candScore > bestScore[i + len]) {
                        bestScore[i + len] = candScore
                        bestPrev[i + len] = i
                        bestTokens[i + len] = longArrayOf(node.tokenId)
                    }
                }
            }

            // 2. Byte fallback edge for code point at i
            val codePoint = s.codePointAt(i)
            val charCount = Character.charCount(codePoint)
            val charStr = s.substring(i, i + charCount)
            val bytes = charStr.toByteArray(Charsets.UTF_8)
            val byteTokens = LongArray(bytes.size) { ENC_BYTE_BASE + (bytes[it].toInt() and 0xFF) }
            val fallbackScore = bestScore[i] + (BYTE_FALLBACK_SCORE_PER_BYTE * bytes.size)

            if (fallbackScore > bestScore[i + charCount]) {
                bestScore[i + charCount] = fallbackScore
                bestPrev[i + charCount] = i
                bestTokens[i + charCount] = byteTokens
            }
        }

        // Backtrack
        var curr = n
        val revSegments = mutableListOf<LongArray>()
        while (curr > 0) {
            val prev = bestPrev[curr]
            val tokens = bestTokens[curr] ?: break
            revSegments.add(tokens)
            curr = prev
        }
        revSegments.reverse()

        var totalLen = 0
        for (seg in revSegments) totalLen += seg.size
        val result = LongArray(totalLen)
        var offset = 0
        for (seg in revSegments) {
            System.arraycopy(seg, 0, result, offset, seg.size)
            offset += seg.size
        }
        return result
    }

    /**
     * Decodes English token IDs back into string, accumulating contiguous byte tokens,
     * stripping special tokens, and restoring metaspaces.
     */
    fun decode(tokens: LongArray): String {
        val sb = StringBuilder()
        val byteBuffer = ByteArrayOutputStream()

        fun flushBytes() {
            if (byteBuffer.size() > 0) {
                sb.append(byteBuffer.toString(Charsets.UTF_8.name()))
                byteBuffer.reset()
            }
        }

        for (token in tokens) {
            if (token in DEC_BYTE_BASE until (DEC_BYTE_BASE + 256)) {
                byteBuffer.write((token - DEC_BYTE_BASE).toInt())
            } else {
                flushBytes()
                if (token == EOS_ID) {
                    break
                }
                if (token == DEC_PAD_ID || token == BOS_ID || token == DEC_UNK_ID) {
                    continue
                }
                val id = token.toInt()
                if (id in _decoderVocab.indices) {
                    sb.append(_decoderVocab[id])
                }
            }
        }
        flushBytes()

        var text = sb.toString().replace(METASPACE, ' ')
        if (text.startsWith(" ")) {
            text = text.substring(1)
        }
        return text
    }

    // --- Lightweight streaming JSON helpers ---

    private fun skipUntilKey(reader: Reader, key: String) {
        val target = "\"$key\""
        var matchIdx = 0
        while (true) {
            val c = reader.read()
            if (c == -1) break
            if (c == target[matchIdx].code) {
                matchIdx++
                if (matchIdx == target.length) return
            } else {
                matchIdx = if (c == target[0].code) 1 else 0
            }
        }
    }

    private fun skipUntilChar(reader: Reader, char: Char) {
        while (true) {
            val c = reader.read()
            if (c == -1 || c == char.code) return
        }
    }

    private fun skipWhitespace(reader: Reader): Int {
        while (true) {
            val c = reader.read()
            if (c == -1 || !Character.isWhitespace(c.toChar())) return c
        }
    }

    private fun readJsonString(reader: Reader): String {
        val sb = StringBuilder()
        while (true) {
            val c = reader.read()
            if (c == -1 || c == '"'.code) break
            if (c == '\\'.code) {
                val esc = reader.read()
                when (esc) {
                    '"'.code -> sb.append('"')
                    '\\'.code -> sb.append('\\')
                    '/'.code -> sb.append('/')
                    'b'.code -> sb.append('\b')
                    'f'.code -> sb.append('\u000C')
                    'n'.code -> sb.append('\n')
                    'r'.code -> sb.append('\r')
                    't'.code -> sb.append('\t')
                    'u'.code -> {
                        val hex = CharArray(4)
                        var readChars = 0
                        while (readChars < 4) {
                            val r = reader.read(hex, readChars, 4 - readChars)
                            if (r == -1) break
                            readChars += r
                        }
                        sb.append(String(hex).toInt(16).toChar())
                    }
                    else -> sb.append(esc.toChar())
                }
            } else {
                sb.append(c.toChar())
            }
        }
        return sb.toString()
    }

    private fun readJsonFloat(reader: Reader): Float {
        val sb = StringBuilder()
        while (true) {
            val c = reader.read()
            if (c == -1 || c == ']'.code || c == ','.code) break
            if (!Character.isWhitespace(c.toChar())) {
                sb.append(c.toChar())
            }
        }
        return sb.toString().toFloat()
    }
}
