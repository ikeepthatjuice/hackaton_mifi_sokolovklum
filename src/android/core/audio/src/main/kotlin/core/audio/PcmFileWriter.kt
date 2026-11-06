package core.audio

import android.system.ErrnoException
import android.system.OsConstants
import core.contracts.PipelineError
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Writes contract PCM (float32 LE, samples in [-1, 1]) to a file.
 * - Every sample is made finite and clamped: sinc resampling rings past ±1 on full-scale input,
 *   and float decoders can emit NaN/Inf. Non-finite values become 0.
 * - "No space" (ENOSPC/EDQUOT) on open, write, flush or close becomes
 *   AudioPreparationException(INSUFFICIENT_STORAGE); other IO errors pass through unchanged.
 *
 * @param expectedBytes size estimate for the whole file, used for requiredBytes; null if unknown.
 */
internal class PcmFileWriter(
    file: File,
    private val expectedBytes: Long?,
    private val freeBytes: () -> Long,
    open: (File) -> OutputStream = { FileOutputStream(it) },
) : Closeable {

    private val bytes = ByteBuffer.allocate(BUFFER).order(ByteOrder.LITTLE_ENDIAN)
    private val stream: OutputStream = guard { BufferedOutputStream(open(file), BUFFER) }

    var samplesWritten = 0L
        private set

    fun write(samples: FloatArray) {
        for (s in samples) {
            if (bytes.remaining() < BYTES_PER_SAMPLE) flush()
            bytes.putFloat(sanitize(s))
        }
        samplesWritten += samples.size
    }

    override fun close() {
        guard {
            flushBuffer()
            stream.close()
        }
    }

    private fun flush() = guard { flushBuffer() }

    private fun flushBuffer() {
        stream.write(bytes.array(), 0, bytes.position())
        bytes.clear()
    }

    private inline fun <T> guard(block: () -> T): T = try {
        block()
    } catch (e: IOException) {
        if (!isNoSpace(e)) throw e
        val required = expectedBytes ?: (samplesWritten * BYTES_PER_SAMPLE)
        val available = runCatching(freeBytes).getOrDefault(0L)
        throw AudioPreparationException(
            PipelineError.INSUFFICIENT_STORAGE,
            "No space for PCM: need ~$required bytes, $available free",
            e,
            requiredBytes = (required - available).coerceAtLeast(1),
        )
    }

    companion object {
        private const val BUFFER = 64 * 1024

        fun sanitize(s: Float): Float = if (s.isFinite()) s.coerceIn(-1f, 1f) else 0f

        /** ENOSPC/EDQUOT by errno anywhere in the cause chain; message fallback for wrapped errors. */
        fun isNoSpace(e: Throwable): Boolean = generateSequence(e) { it.cause }.any { t ->
            (t is ErrnoException && (t.errno == OsConstants.ENOSPC || t.errno == OsConstants.EDQUOT)) ||
                t.message?.let { "ENOSPC" in it || "EDQUOT" in it } == true
        }
    }
}
