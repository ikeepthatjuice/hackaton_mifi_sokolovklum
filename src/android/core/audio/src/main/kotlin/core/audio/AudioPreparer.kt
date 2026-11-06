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
import kotlinx.coroutines.CancellationException
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
 * durationMs is computed from the decoded samples, not container metadata.
 * Runs on Dispatchers.IO and is cancellable; a partial file is removed on any failure.
 */
class AudioPreparer(private val context: Context) {

    suspend fun prepare(audioUri: String, jobId: String): AudioInput = withContext(Dispatchers.IO) {
        require(JOB_ID.matches(jobId)) { "jobId must match ${JOB_ID.pattern}: $jobId" }
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
            } catch (e: SecurityException) {
                throw AudioPreparationException(PipelineError.UNSUPPORTED_FORMAT, "No read access to audio URI", e)
            }
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw AudioPreparationException(PipelineError.UNSUPPORTED_FORMAT, "No audio track")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)

            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else null
            checkFreeSpace(requiredPcmBytes(durationUs))

            val mime = format.getString(MediaFormat.KEY_MIME)!!
            // Assign before configure so finally releases the decoder even if configure/start throws.
            val decoder = try {
                MediaCodec.createDecoderByType(mime)
            } catch (e: Exception) {
                throw AudioPreparationException(PipelineError.UNSUPPORTED_FORMAT, "No decoder for $mime", e)
            }
            codec = decoder
            try {
                decoder.configure(format, null, null, 0)
                decoder.start()
            } catch (e: Exception) {
                throw AudioPreparationException(PipelineError.UNSUPPORTED_FORMAT, "Decoder rejected $mime", e)
            }

            val samples = try {
                decodeTo(extractor, decoder, out)
            } catch (e: CancellationException) {
                throw e // is an IllegalStateException; must not become DECODE_FAILED
            } catch (e: IllegalStateException) {
                throw AudioPreparationException(PipelineError.DECODE_FAILED, "Decoder failed", e)
            } catch (e: IllegalArgumentException) {
                throw AudioPreparationException(PipelineError.DECODE_FAILED, "Decoder failed", e)
            } catch (e: NullPointerException) {
                throw AudioPreparationException(PipelineError.DECODE_FAILED, "Decoder returned incomplete data", e)
            }
            AudioInput(
                pcmFilePath = out.absolutePath,
                sampleRateHz = TARGET_SAMPLE_RATE,
                channels = 1,
                durationMs = samples * 1000 / TARGET_SAMPLE_RATE,
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

    /** Decodes the selected track into [out]; returns the number of 16 kHz samples written. */
    private suspend fun decodeTo(extractor: MediaExtractor, codec: MediaCodec, out: File): Long {
        val info = MediaCodec.BufferInfo()
        var resampler: Resampler? = null
        var channels = 0
        var rate = 0
        var isFloat = false
        var written = 0L
        var inputDone = false
        var idleAfterInput = 0
        val bytes = ByteBuffer.allocate(64 * 1024).order(ByteOrder.LITTLE_ENDIAN)

        BufferedOutputStream(FileOutputStream(out), 1 shl 16).use { stream ->
            fun write(samples: FloatArray) {
                for (s in samples) {
                    if (bytes.remaining() < BYTES_PER_SAMPLE) flushBytes(bytes, stream)
                    bytes.putFloat(s)
                }
                written += samples.size
            }

            fun updateFormat(f: MediaFormat) {
                val newChannels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                val newRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                val encoding = if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                    f.getInteger(MediaFormat.KEY_PCM_ENCODING)
                } else {
                    AudioFormat.ENCODING_PCM_16BIT // decoder default
                }
                if (encoding != AudioFormat.ENCODING_PCM_16BIT && encoding != AudioFormat.ENCODING_PCM_FLOAT) {
                    throw AudioPreparationException(PipelineError.UNSUPPORTED_FORMAT, "PCM encoding $encoding")
                }
                isFloat = encoding == AudioFormat.ENCODING_PCM_FLOAT
                channels = newChannels
                // Same rate: keep filter state. New rate (e.g. HE-AAC SBR): emit the old tail first.
                if (newRate != rate) {
                    resampler?.let { write(it.flush()) }
                    resampler = Resampler(newRate, TARGET_SAMPLE_RATE)
                    rate = newRate
                }
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
                        idleAfterInput = 0
                        if (resampler == null) updateFormat(codec.outputFormat)
                        val buf = codec.getOutputBuffer(outIdx)!!.apply {
                            position(info.offset); limit(info.offset + info.size)
                        }.slice()
                        val mono = if (isFloat) pcmFloatToMono(buf, channels) else pcm16ToMono(buf, channels)
                        write(resampler!!.process(mono))
                        codec.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                    // Some decoders never signal EOS; do not spin forever after input ended.
                    inputDone && ++idleAfterInput > MAX_IDLE_POLLS ->
                        throw AudioPreparationException(PipelineError.DECODE_FAILED, "Decoder stalled without EOS")
                }
            }
            resampler?.let { write(it.flush()) }
            flushBytes(bytes, stream)
        }
        if (written == 0L) throw AudioPreparationException(PipelineError.DECODE_FAILED, "Decoder produced no audio")
        return written
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
        const val MAX_IDLE_POLLS = 500 // × TIMEOUT_US ≈ 5 s without output after input ended
        const val MIN_FREE_BYTES = 1L shl 20
        // jobId becomes a directory name; pipeline generates it, this guards against path escape.
        val JOB_ID = Regex("[A-Za-z0-9_-]{1,64}")
    }
}
