package core.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Target PCM of the shared contract (PROJECT_PLAN §5): mono 16 kHz float32 LE in [-1, 1]. */
const val TARGET_SAMPLE_RATE = 16_000
const val BYTES_PER_SAMPLE = 4

/** Interleaved 16-bit little-endian PCM → mono float, channels averaged. */
internal fun pcm16ToMono(bytes: ByteBuffer, channels: Int): FloatArray {
    val shorts = bytes.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
    val frames = shorts.remaining() / channels
    return FloatArray(frames) { f ->
        var sum = 0f
        for (c in 0 until channels) sum += shorts.get(f * channels + c)
        sum / channels / 32768f
    }
}

/** Interleaved float PCM → mono float, channels averaged, clamped to [-1, 1]. */
internal fun pcmFloatToMono(bytes: ByteBuffer, channels: Int): FloatArray {
    val floats = bytes.order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
    val frames = floats.remaining() / channels
    return FloatArray(frames) { f ->
        var sum = 0f
        for (c in 0 until channels) sum += floats.get(f * channels + c)
        (sum / channels).coerceIn(-1f, 1f)
    }
}

/**
 * Bytes the output PCM will take for [durationUs] of source audio, plus 10% headroom.
 * Null when the container does not report a duration.
 */
internal fun requiredPcmBytes(durationUs: Long?): Long? =
    durationUs?.takeIf { it > 0 }?.let { it * TARGET_SAMPLE_RATE / 1_000_000 * BYTES_PER_SAMPLE * 11 / 10 }
