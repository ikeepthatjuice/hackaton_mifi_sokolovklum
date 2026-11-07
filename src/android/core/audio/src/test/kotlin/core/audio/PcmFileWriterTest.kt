package core.audio

import core.contracts.PipelineError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

// Synthetic signals only; failures are injected through the stream factory.
class PcmFileWriterTest {

    private val dummy = File("unused.f32")
    private val noSpace = IOException("write failed: ENOSPC (No space left on device)")

    private fun floats(bytes: ByteArray): FloatArray {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(b.remaining()) { b.get(it) }
    }

    private fun collect(block: (PcmFileWriter) -> Unit): FloatArray {
        val sink = ByteArrayOutputStream()
        PcmFileWriter(dummy, expectedBytes = null, freeBytes = { 0 }, open = { sink }).use(block)
        return floats(sink.toByteArray())
    }

    /** Accepts writes, fails like a full disk on close (the last buffered bytes reach the disk there). */
    private class FailOnClose(private val error: IOException) : OutputStream() {
        override fun write(b: Int) = Unit
        override fun write(b: ByteArray, off: Int, len: Int) = Unit
        override fun close() = throw error
    }

    @Test
    fun noSpaceOnFinalCloseIsInsufficientStorageWithMissingBytes() {
        val writer = PcmFileWriter(dummy, expectedBytes = 1000, freeBytes = { 400 }, open = { FailOnClose(noSpace) })
        writer.write(FloatArray(10))
        val e = assertThrows(AudioPreparationException::class.java) { writer.close() }
        assertEquals(PipelineError.INSUFFICIENT_STORAGE, e.error)
        assertEquals(600L, e.requiredBytes)
    }

    @Test
    fun failedFinalFlushStillClosesTheFile() {
        var closed = false
        val stream = object : OutputStream() {
            override fun write(b: Int) = Unit
            override fun write(b: ByteArray, off: Int, len: Int) = throw noSpace
            override fun close() { closed = true }
        }
        val writer = PcmFileWriter(dummy, expectedBytes = 100, freeBytes = { 0 }, open = { stream })
        writer.write(FloatArray(4)) // stays in the buffer until close
        val e = assertThrows(AudioPreparationException::class.java) { writer.close() }
        assertEquals(PipelineError.INSUFFICIENT_STORAGE, e.error)
        assertTrue("underlying stream must be closed", closed)
    }

    @Test
    fun noSpaceOnOpenIsInsufficientStorage() {
        val e = assertThrows(AudioPreparationException::class.java) {
            PcmFileWriter(dummy, expectedBytes = 1000, freeBytes = { 0 }, open = { throw noSpace })
        }
        assertEquals(PipelineError.INSUFFICIENT_STORAGE, e.error)
        assertEquals(1000L, e.requiredBytes)
    }

    @Test
    fun quotaExceededCountsAsNoSpace() {
        assertTrue(PcmFileWriter.isNoSpace(IOException("wrapped", IOException("write failed: EDQUOT (Quota exceeded)"))))
    }

    @Test
    fun otherIoErrorPassesThroughUntyped() {
        val writer = PcmFileWriter(dummy, expectedBytes = null, freeBytes = { 0 }, open = { FailOnClose(IOException("EIO")) })
        val e = assertThrows(IOException::class.java) { writer.close() }
        assertEquals("EIO", e.message)
    }

    @Test
    fun nonFiniteSamplesBecomeZeroAndOutOfRangeIsClamped() {
        val out = collect { it.write(floatArrayOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 1.5f, -2f, 0.25f)) }
        assertEquals(listOf(0f, 0f, 0f, 1f, -1f, 0.25f), out.toList())
    }

    @Test
    fun fullScaleSineAndStepStayInUnitRangeAfterResampling() {
        // Sinc ringing on full-scale input overshoots ±1 (≈1.18 on a step 48→16 kHz) before the writer clamps it.
        val sine = FloatArray(48000) { sin(2 * PI * 7000.0 * it / 48000).toFloat() }
        val step = FloatArray(48000) { if ((it / 2400) % 2 == 0) 1f else -1f }
        for (input in listOf(sine, step)) {
            val r = Resampler(48000, 16000)
            val resampled = r.process(input) + r.flush()
            val out = collect { it.write(resampled) }
            assertEquals(16000, out.size)
            assertTrue("max=${out.maxOf { abs(it) }}", out.all { it.isFinite() && it in -1f..1f })
        }
    }
}
