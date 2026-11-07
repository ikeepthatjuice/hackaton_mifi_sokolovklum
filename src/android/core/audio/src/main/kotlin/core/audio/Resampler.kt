package core.audio

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin

/**
 * Streaming band-limited resampler for mono float samples (windowed-sinc, Hann window).
 * When downsampling, the low-pass cutoff sits below the output Nyquist, so content above it
 * is removed instead of aliasing into speech frequencies.
 *
 * Feed chunks with [process], then call [flush] once to emit the tail.
 * Output length is round(inputSamples * outRate / inRate).
 */
class Resampler(private val inRate: Int, private val outRate: Int) {
    init {
        require(inRate > 0 && outRate > 0) { "rates must be positive: $inRate -> $outRate" }
    }

    private val step = inRate.toDouble() / outRate // input samples per output sample
    private val cutoff = min(1.0, outRate.toDouble() / inRate) * CUTOFF_MARGIN // of input Nyquist
    private val halfLen = ceil(ZERO_CROSSINGS / cutoff).toInt() // filter half-length, input samples

    // Precomputed filter for PHASES fractional offsets; row p holds taps for offset p / PHASES.
    private val table: Array<FloatArray> = Array(PHASES + 1) { p ->
        val frac = p.toDouble() / PHASES
        FloatArray(2 * halfLen) { i -> kernel(i - halfLen + 1 - frac).toFloat() }
    }

    private var buf = FloatArray(4096)
    private var bufLen = 0
    private var bufStart = 0L // absolute index of buf[0]
    private var nextOut = 0L // index of the next output sample
    private var totalIn = 0L
    private val passthrough = inRate == outRate

    fun process(input: FloatArray, length: Int = input.size): FloatArray {
        if (passthrough) {
            totalIn += length
            return input.copyOf(length)
        }
        append(input, length)
        return emit(final = false)
    }

    fun flush(): FloatArray {
        if (passthrough) return FloatArray(0)
        return emit(final = true)
    }

    private fun append(input: FloatArray, length: Int) {
        if (bufLen + length > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, bufLen + length))
        System.arraycopy(input, 0, buf, bufLen, length)
        bufLen += length
        totalIn += length
    }

    private fun emit(final: Boolean): FloatArray {
        val outEnd = if (final) Math.round(totalIn * outRate.toDouble() / inRate) else Long.MAX_VALUE
        var out = FloatArray(256)
        var n = 0
        while (nextOut < outEnd) {
            val t = nextOut * step
            val base = floor(t).toLong()
            // Need input up to base + halfLen; past the end of the stream it is zero.
            if (!final && base + halfLen >= bufStart + bufLen) break
            val phase = ((t - base) * PHASES).toInt().coerceIn(0, PHASES)
            val taps = table[phase]
            var acc = 0f
            val first = base - halfLen + 1 - bufStart
            for (i in taps.indices) {
                val idx = first + i
                if (idx in 0 until bufLen) acc += buf[idx.toInt()] * taps[i]
            }
            if (n == out.size) out = out.copyOf(out.size * 2)
            out[n++] = acc
            nextOut++
        }
        dropConsumed()
        return out.copyOf(n)
    }

    // Keep only the history the next output still needs.
    private fun dropConsumed() {
        val keepFrom = floor(nextOut * step).toLong() - halfLen + 1
        val drop = (keepFrom - bufStart).coerceIn(0L, bufLen.toLong()).toInt()
        if (drop == 0) return
        System.arraycopy(buf, drop, buf, 0, bufLen - drop)
        bufLen -= drop
        bufStart += drop
    }

    private fun kernel(d: Double): Double {
        if (d <= -halfLen || d >= halfLen) return 0.0
        val x = cutoff * d
        val sinc = if (x == 0.0) 1.0 else sin(PI * x) / (PI * x)
        val window = 0.5 * (1 + cos(PI * d / halfLen))
        return cutoff * sinc * window
    }

    private companion object {
        const val ZERO_CROSSINGS = 16
        const val PHASES = 256
        const val CUTOFF_MARGIN = 0.9 // transition band below Nyquist
    }
}
