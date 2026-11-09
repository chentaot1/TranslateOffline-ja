package com.yomu.ml.encdec

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EncDecTokenizerTest {

    @Test
    fun specialTokenIds_areAsymmetricBetweenEncoderAndDecoder() {
        assertEquals(3L, EncDecTokenizer.ENC_PAD_ID)
        assertEquals(0L, EncDecTokenizer.DEC_PAD_ID)

        assertEquals(0L, EncDecTokenizer.ENC_UNK_ID)
        assertEquals(3L, EncDecTokenizer.DEC_UNK_ID)

        assertEquals(1L, EncDecTokenizer.BOS_ID)
        assertEquals(2L, EncDecTokenizer.EOS_ID)
        assertEquals(4L, EncDecTokenizer.ENC_SEP_ID)

        assertEquals(15L, EncDecTokenizer.ENC_BYTE_BASE)
        assertEquals(4L, EncDecTokenizer.DEC_BYTE_BASE)
    }

    @Test
    fun decode_flatArrayReassemblesSpacesAndStripsSpecialTokens() {
        // Mock a 16,000 vocab array with basic pieces
        val vocab = Array(16000) { "" }
        vocab[0] = "<pad>"
        vocab[1] = "<s>"
        vocab[2] = "</s>"
        vocab[3] = "<unk>"
        // byte tokens 4..259
        for (b in 0..255) {
            vocab[4 + b] = String.format("<0x%02X>", b)
        }
        vocab[260] = "▁Hello"
        vocab[261] = "▁world"
        vocab[262] = "!"

        val tokenizer = EncDecTokenizer()
        tokenizer.loadDecoderVocab(vocab)

        // Tokens: [<s>, ▁Hello, ▁world, !, </s>]
        val tokens = longArrayOf(1L, 260L, 261L, 262L, 2L)
        val decoded = tokenizer.decode(tokens)

        assertEquals("Hello world!", decoded)
    }

    @Test
    fun decode_contiguousBytesAccumulateAndFlushOnEOS() {
        val vocab = Array(16000) { "" }
        vocab[0] = "<pad>"
        vocab[1] = "<s>"
        vocab[2] = "</s>"
        vocab[3] = "<unk>"
        for (b in 0..255) {
            vocab[4 + b] = String.format("<0x%02X>", b)
        }
        vocab[260] = "▁Yes"

        val tokenizer = EncDecTokenizer()
        tokenizer.loadDecoderVocab(vocab)

        // Wave dash "〜" in UTF-8 is 0xE3 0x80 0x9C (bytes 227, 128, 156)
        // In decoder vocab, byte IDs are 4 + byteVal:
        // 0xE3 = 227 -> token 231
        // 0x80 = 128 -> token 132
        // 0x9C = 156 -> token 160
        val tokensEndingInBytes = longArrayOf(1L, 260L, 231L, 132L, 160L, 2L)
        val decoded = tokenizer.decode(tokensEndingInBytes)

        assertEquals("Yes〜", decoded)
    }

    @Test
    fun decode_trailingBytesFlushOnLoopTerminationWithoutExplicitEOS() {
        val vocab = Array(16000) { "" }
        vocab[0] = "<pad>"
        vocab[1] = "<s>"
        vocab[2] = "</s>"
        vocab[3] = "<unk>"
        for (b in 0..255) {
            vocab[4 + b] = String.format("<0x%02X>", b)
        }
        vocab[260] = "▁Hi"

        val tokenizer = EncDecTokenizer()
        tokenizer.loadDecoderVocab(vocab)

        // Ellipsis "…" in UTF-8 is 0xE2 0x80 0xA6 (226, 128, 166 -> tokens 230, 132, 170)
        // Array ends without token 2
        val tokens = longArrayOf(260L, 230L, 132L, 170L)
        val decoded = tokenizer.decode(tokens)

        assertEquals("Hi…", decoded)
    }

    @Test
    fun encode_japaneseViterbiMatchesTrieAndDoesNotPrependDummySpace() {
        val tokenizer = EncDecTokenizer()
        // Provide mini Japanese unigram pieces:
        // "▁" (space replacement): -2.0
        // "これ": -3.0
        // "は": -2.0
        // "テスト": -4.0
        // "です": -3.0
        val pieces = listOf(
            "▁" to -2.0f,
            "これ" to -3.0f,
            "は" to -2.0f,
            "テスト" to -4.0f,
            "です" to -3.0f
        )
        tokenizer.loadEncoderVocab(pieces)

        val tokens = tokenizer.encode("これはテストです")
        assertTrue("Tokens must not be empty", tokens.isNotEmpty())
        // Ensure no synthetic leading space token
        assertFalse("Must not prepend dummy space", tokens.first() == tokenizer.pieceToId("▁"))

        val decodedPieces = tokens.map { tokenizer.idToEncoderPiece(it) }
        assertEquals(listOf("これ", "は", "テスト", "です"), decodedPieces)
    }

    @Test
    fun encode_byteFallbackForUnseenGlyphs() {
        val tokenizer = EncDecTokenizer()
        val pieces = listOf(
            "テスト" to -2.0f
        )
        tokenizer.loadEncoderVocab(pieces)

        // "A" in UTF-8 is 0x41 (65 decimal). Encoder byte base is 15.
        // Token for 'A' via byte fallback must be 15 + 65 = 80L
        val tokens = tokenizer.encode("Aテスト")
        assertEquals(80L, tokens[0])
        val rest = tokens.drop(1).map { tokenizer.idToEncoderPiece(it) }
        assertEquals(listOf("テスト"), rest)
    }

    @Test
    fun realVocabFiles_canBeLoadedDirectly() {
        val encFile = File("enc_vocab_sample.json")
        val decFile = File("dec_vocab_sample.json")
        if (encFile.exists() && decFile.exists()) {
            val tokenizer = EncDecTokenizer()
            tokenizer.loadEncoderVocabFile(encFile)
            tokenizer.loadDecoderVocabFile(decFile)

            assertEquals(16000, tokenizer.decoderVocabSize)
            assertEquals(102400, tokenizer.encoderVocabSize)

            val enc = tokenizer.encode("こんにちは")
            assertTrue(enc.isNotEmpty())

            val dec = tokenizer.decode(longArrayOf(1L, 260L, 2L))
            assertTrue(dec.isNotBlank())
        }
    }
}
