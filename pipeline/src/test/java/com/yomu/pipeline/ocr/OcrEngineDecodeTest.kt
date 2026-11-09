package com.yomu.pipeline.ocr

import org.junit.Assert.assertEquals
import org.junit.Test

class OcrEngineDecodeTest {

    private val vocab = mapOf(
        0 to "[PAD]",
        1 to "[CLS]",
        2 to "[SEP]",
        3 to "[UNK]",
        10 to "私",
        11 to "が",
        12 to "全",
        13 to "部",
        20 to "play",
        21 to "##ing"
    )

    @Test
    fun decode_skipsSpecialTokens() {
        val ids = listOf(1, 10, 11, 12, 13, 2, 0, 0)
        assertEquals("私が全部", decodeTokens(ids, vocab))
    }

    @Test
    fun decode_joinsJapaneseWithoutSpaces() {
        assertEquals("私が", decodeTokens(listOf(10, 11), vocab))
    }

    @Test
    fun decode_mergesWordpieceContinuations() {
        assertEquals("playing", decodeTokens(listOf(20, 21), vocab))
    }

    @Test
    fun decode_skipsUnknownIds() {
        assertEquals("私", decodeTokens(listOf(10, 999), vocab))
    }

    @Test
    fun decode_emptyWhenOnlySpecialTokens() {
        assertEquals("", decodeTokens(listOf(1, 2, 0), vocab))
    }

    @Test
    fun choose_takesTheHighestLogitAndItsSoftmaxProbability() {
        // The second step of a two-step logits buffer: softmax(1, 3, 2) is 0.090, 0.665, 0.245.
        val logits = floatArrayOf(9f, 0f, 0f, 1f, 3f, 2f)

        val choice = chooseToken(logits, start = 3, count = 3)

        assertEquals(1, choice.id)
        assertEquals(0.665f, choice.probability, 0.001f)
    }

    @Test
    fun greedy_confidenceIsTheMeanProbabilityOfEveryChoiceIncludingTheStop() {
        val script = ArrayDeque(listOf(TokenChoice(10, 1.0f), TokenChoice(11, 0.5f), TokenChoice(3, 0.9f)))

        val decoded = decodeGreedy<String>(stopTokenId = 3) { _, _ -> DecoderStep(script.removeFirst(), emptyMap()) }

        assertEquals(listOf(10, 11), decoded.tokenIds)
        assertEquals(0.8f, decoded.confidence, 0.0001f)
    }

    @Test
    fun greedy_confidenceIsZeroWhenTheDecoderNeverAnswers() {
        assertEquals(0f, decodeGreedy<String>(stopTokenId = 3) { _, _ -> null }.confidence)
    }

    @Test
    fun greedy_sendsOneTokenPerStepFromTheDecoderStartTokenUntilTheStopToken() {
        val script = ArrayDeque(listOf(10, 11, 3, 12))
        val seenTokens = mutableListOf<Long>()

        val generated = decodeGreedy<String>(stopTokenId = 3) { token, _ ->
            seenTokens.add(token)
            DecoderStep(TokenChoice(script.removeFirst(), 1f), emptyMap())
        }

        assertEquals(listOf(10, 11), generated.tokenIds)
        // 2 is [CLS], the model's decoder_start_token_id.
        assertEquals(listOf(2L, 10L, 11L), seenTokens)
    }

    @Test
    fun greedy_handsEachStepThePreviousStatesKeepingStateALaterStepLeftOut() {
        // The cache branch returns empty cross-attention presents, which the step leaves out,
        // so the first step's must carry through.
        val states = ArrayDeque(
            listOf(
                mapOf("cross" to "cross-1", "self" to "self-1"),
                mapOf("self" to "self-2"),
                mapOf("self" to "self-3")
            )
        )
        val seenPasts = mutableListOf<Map<String, String>>()

        decodeGreedy(stopTokenId = 3) { _, past ->
            seenPasts.add(past)
            val state = states.removeFirst()
            DecoderStep(TokenChoice(if (states.isEmpty()) 3 else 10, 1f), state)
        }

        assertEquals(
            listOf(
                emptyMap(),
                mapOf("cross" to "cross-1", "self" to "self-1"),
                mapOf("cross" to "cross-1", "self" to "self-2")
            ),
            seenPasts
        )
    }
}
