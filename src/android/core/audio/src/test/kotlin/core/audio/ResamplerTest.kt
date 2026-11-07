package core.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

// Synthetic signals only.
class ResamplerTest {

    private fun sine(freq: Double, rate: Int, seconds: Double) =
        FloatArray((rate * seconds).toInt()) { sin(2 * PI * freq * it / rate).toFloat() * 0.5f }

    private fun resampleAll(input: FloatArray, inRate: Int, outRate: Int, chunk: Int = 1000): FloatArray {
        val r = Resampler(inRate, outRate)
        val parts = input.toList().chunked(chunk).map { r.process(it.toFloatArray()) } + listOf(r.flush())
        return parts.reduce { a, b -> a + b }
    }

    // RMS of the middle part, away from filter edges.
    private fun rms(x: FloatArray): Double {
        val mid = x.copyOfRange(x.size / 4, x.size * 3 / 4)
        return sqrt(mid.sumOf { it.toDouble() * it } / mid.size)
    }

    @Test
    fun outputLengthMatchesRateRatio() {
        for (inRate in listOf(8000, 22050, 44100, 48000)) {
            val out = resampleAll(FloatArray(inRate * 3), inRate, 16000)
            assertEquals("from $inRate", 48000, out.size)
        }
    }

    @Test
    fun speechBandToneKeepsAmplitude() {
        val out = resampleAll(sine(1000.0, 48000, 1.0), 48000, 16000)
        assertEquals(0.5 / sqrt(2.0), rms(out), 0.01)
    }

    @Test
    fun toneAboveOutputNyquistIsRemovedNotAliased() {
        // 12 kHz is above 8 kHz Nyquist of 16 kHz; naive decimation would fold it to 4 kHz.
        val out = resampleAll(sine(12000.0, 48000, 1.0), 48000, 16000)
        assertTrue("rms=${rms(out)}", rms(out) < 0.01)
    }

    @Test
    fun upsamplingKeepsTone() {
        val out = resampleAll(sine(1000.0, 8000, 1.0), 8000, 16000)
        assertEquals(16000, out.size)
        assertEquals(0.5 / sqrt(2.0), rms(out), 0.01)
    }

    @Test
    fun chunkSizeDoesNotChangeResult() {
        val input = sine(440.0, 44100, 0.5)
        val a = resampleAll(input, 44100, 16000, chunk = 37)
        val b = resampleAll(input, 44100, 16000, chunk = 10_000)
        assertEquals(a.size, b.size)
        assertTrue(a.indices.all { abs(a[it] - b[it]) < 1e-5f })
    }

    @Test
    fun sameRateIsPassthrough() {
        val input = floatArrayOf(0.1f, -0.2f, 0.3f)
        val r = Resampler(16000, 16000)
        assertArrayEquals(input, r.process(input) + r.flush(), 0f)
    }
}
