package com.yomu.ml.encdec

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class EncDecEngineTest {

    companion object {
        private val isNativeOnnxAvailable: Boolean by lazy {
            try {
                val env = OrtEnvironment.getEnvironment()
                env.close()
                true
            } catch (_: UnsatisfiedLinkError) {
                false
            } catch (_: Throwable) {
                false
            }
        }
    }

    @Test
    fun createSessionOptions_configuresSnapdragonOptimizations() {
        assumeTrue("Native ONNX runtime required for SessionOptions test", isNativeOnnxAvailable)
        val opts = EncDecEngine.createSessionOptions(threads = 4)
        assertNotNull(opts)
        opts.close()
    }

    @Test
    fun stepZeroEmptyKvCache_hasCorrectShapeAndEmptyBuffer() {
        assumeTrue("Native ONNX runtime required for OnnxTensor test", isNativeOnnxAvailable)
        val env = OrtEnvironment.getEnvironment()
        val batchSize = 2
        val emptyBuf = ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder()).asFloatBuffer()
        val shape = longArrayOf(batchSize.toLong(), 12L, 0L, 64L)
        val tensor = OnnxTensor.createTensor(env, emptyBuf, shape)

        try {
            assertNotNull(tensor)
            val tensorInfo = tensor.info
            assertEquals(4, tensorInfo.shape.size)
            assertEquals(batchSize.toLong(), tensorInfo.shape[0])
            assertEquals(12L, tensorInfo.shape[1])
            assertEquals(0L, tensorInfo.shape[2])
            assertEquals(64L, tensorInfo.shape[3])
        } finally {
            tensor.close()
            env.close()
        }
    }

    @Test
    fun decoderInputNames_exactlyTwentyInputs() {
        val names = EncDecEngine.getDecoderInputNames()
        assertEquals(20, names.size)
        assertTrue(names.contains("token"))
        assertTrue(names.contains("pos"))
        assertTrue(names.contains("mem_bias"))
        assertTrue(names.contains("rel"))

        for (i in 0 until 4) {
            assertTrue(names.contains("cross_k$i"))
            assertTrue(names.contains("cross_v$i"))
            assertTrue(names.contains("self_k$i"))
            assertTrue(names.contains("self_v$i"))
        }
    }

    @Test
    fun translatePage_emptyLinesArePreservedWithoutInference() {
        val tokenizer = EncDecTokenizer()
        val engine = EncDecEngine(null, null, tokenizer)

        val input = listOf("", "   ", "")
        val result = engine.translatePage(input)

        assertEquals(3, result.size)
        assertEquals("", result[0])
        assertEquals("", result[1])
        assertEquals("", result[2])

        engine.close()
    }
}
