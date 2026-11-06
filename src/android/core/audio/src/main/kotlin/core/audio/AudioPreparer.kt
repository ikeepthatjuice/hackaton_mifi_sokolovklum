package core.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.StatFs
import core.contracts.AudioInput
import core.contracts.PipelineError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext

/** Decoding failed with a reason the pipeline maps to UI state. */
class AudioPreparationException(val error: PipelineError, message: String, cause: Throwable? = null) :
    Exception(message, cause)

/**
 * Not enough free space in filesDir for the decoded PCM.
 * Separate type until PipelineError gets a storage code (contract change).
 */
class InsufficientStorageException(val requiredBytes: Long, val availableBytes: Long) :
    Exception("Need $requiredBytes bytes for PCM, only $availableBytes free")

/**
 * Turns a user-picked audio URI into the contract PCM file
 * `filesDir/jobs/<jobId>/audio.f32` (mono, 16 kHz, float32 LE) and its [AudioInput].
 *
 * durationMs is computed from decoded source frames, not container metadata.
 * Runs on Dispatchers.IO and is cancellable; a partial file is removed on any failure.
 */
class AudioPreparer(private val context: Context) {

    suspend fun prepare(audioUri: String, jobId: String): AudioInput = withContext(Dispatchers.IO) {
        val out = File(context.filesDir, "jobs/$jobId/audio.f32")
        out.parentFile?.mkdirs()
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            try {
                extractor.setDataSource(context, Uri.parse(audioUri), null)
            } catch (e: IOException) {
                throw AudioPreparationException(PipelineError.UNSUPPORTED_FORMAT, "Cannot open audio", e)
            } catch (e: IllegalArgumentException) {
                throw AudioPreparationException(PipelineError.UNSUPPORTED_FORMAT, "Cannot open audio", e)
            }
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw AudioPreparationException(PipelineError.UNSUPPORTED_FORMAT, "No audio track")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)

            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else null
            checkFreeSpace(requiredPcmBytes(durationUs))

            val mime = format.getString(MediaFormat.KEY_MIME)!!
            codec = try {
                MediaCodec.createDecoderByType(mime).apply { configure(format, null, null, 0); start() }
            } catch (e: Exception) {
                throw AudioPreparationException(PipelineError.UNSUPPORTED_FORMAT, "No decoder for $mime", e)
            }

            // Only CodecException: a broad IllegalStateException catch would swallow CancellationException.
            val frames = try {
                decodeTo(extractor, codec, out)
            } catch (e: MediaCodec.CodecException) {
                throw AudioPreparationException(PipelineError.DECODE_FAILED, "Decoder failed", e)
            }
            AudioInput(
                pcmFilePath = out.absolutePath,
                sampleRateHz = TARGET_SAMPLE_RATE,
                channels = 1,
                durationMs = frames.sourceFrames * 1000 / frames.sourceRate,
            )
        } catch (e: Throwable) {
            out.delete()
            throw e
        } finally {
            codec?.runCatching { stop() }
            codec?.runCatching { release() }
            extractor.release()
        }
    }

    private class Frames(val sourceFrames: Long, val sourceRate: Int)

    private suspend fun decodeTo(extractor: MediaExtractor, codec: MediaCodec, out: File): Frames {
        val info = MediaCodec.BufferInfo()
        var resampler: Resampler? = null
        var channels = 1
        var rate = 0
        var isFloat = false
        var sourceFrames = 0L
        var inputDone = false
        val bytes = ByteBuffer.allocate(64 * 1024).order(ByteOrder.LITTLE_ENDIAN)

        BufferedOutputStream(FileOutputStream(out), 1 shl 16).use { stream ->
            fun write(samples: FloatArray) {
                for (s in samples) {
                    if (bytes.remaining() < BYTES_PER_SAMPLE) flushBytes(bytes, stream)
                    bytes.putFloat(s)
                }
            }

            fun updateFormat(f: MediaFormat) {
                channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                isFloat = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                    f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                // Rate changes mid-stream are not expected for files; restart the filter if it happens.
                resampler = Resampler(rate, TARGET_SAMPLE_RATE)
            }

            while (true) {
                coroutineContext.ensureActive()
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIdx >= 0) {
                        val size = extractor.readSampleData(codec.getInputBuffer(inIdx)!!, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> updateFormat(codec.outputFormat)
                    outIdx >= 0 -> {
                        if (resampler == null) updateFormat(codec.outputFormat)
                        val buf = codec.getOutputBuffer(outIdx)!!.apply {
                            position(info.offset); limit(info.offset + info.size)
                        }.slice()
                        val mono = if (isFloat) pcmFloatToMono(buf, channels) else pcm16ToMono(buf, channels)
                        sourceFrames += mono.size
                        write(resampler!!.process(mono))
                        codec.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
            resampler?.let { write(it.flush()) }
            flushBytes(bytes, stream)
        }
        if (rate == 0) throw AudioPreparationException(PipelineError.DECODE_FAILED, "Decoder produced no output format")
        return Frames(sourceFrames, rate)
    }

    private fun flushBytes(bytes: ByteBuffer, stream: BufferedOutputStream) {
        val pending = bytes.position()
        try {
            stream.write(bytes.array(), 0, pending)
        } catch (e: IOException) {
            // Disk filled up mid-write (duration unknown or estimate too low); other IO errors pass through.
            val available = StatFs(context.filesDir.path).availableBytes
            if (available < MIN_FREE_BYTES) throw InsufficientStorageException(pending.toLong(), available).apply { initCause(e) }
            throw e
        }
        bytes.clear()
    }

    private fun checkFreeSpace(required: Long?) {
        if (required == null) return
        val available = StatFs(context.filesDir.path).availableBytes
        if (available < required) throw InsufficientStorageException(required, available)
    }

    private companion object {
        const val TIMEOUT_US = 10_000L
        const val MIN_FREE_BYTES = 1L shl 20
    }
}
