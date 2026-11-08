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
import java.io.File
import java.io.IOException
import kotlin.coroutines.coroutineContext

/**
 * Audio preparation failed with a reason the pipeline maps to [core.contracts.PipelineState.Failed].
 * [requiredBytes] is set only for INSUFFICIENT_STORAGE: missing bytes (required − available).
 */
class AudioPreparationException(
    val error: PipelineError,
    message: String,
    cause: Throwable? = null,
    val requiredBytes: Long? = null,
) : Exception(message, cause)

private fun insufficientStorage(required: Long, available: Long, cause: Throwable? = null) =
    AudioPreparationException(
        PipelineError.INSUFFICIENT_STORAGE,
        "Need $required bytes for PCM, only $available free",
        cause,
        requiredBytes = (required - available).coerceAtLeast(1),
    )

/**
 * Turns a user-picked audio URI into the contract PCM file
 * `filesDir/jobs/<jobId>/audio.f32` (mono, 16 kHz, float32 LE) and its [AudioInput].
 *
 * durationMs is computed from the decoded samples, not container metadata.
 * Runs on Dispatchers.IO and is cancellable; a partial file is removed on any failure,
 * including a cancel that lands while the result is handed back from the IO dispatcher.
 */
class AudioPreparer(private val context: Context) {

    suspend fun prepare(audioUri: String, jobId: String): AudioInput {
        require(JOB_ID.matches(jobId)) { "jobId must match ${JOB_ID.pattern}: $jobId" }
        val out = File(context.filesDir, "jobs/$jobId/audio.f32")
        // Cleanup outside withContext: its prompt cancellation can throw after the body already finished.
        try {
            return withContext(Dispatchers.IO) { prepareOnIo(audioUri, out) }
        } catch (e: Throwable) {
            out.delete()
            throw e
        }
    }

    private suspend fun prepareOnIo(audioUri: String, out: File): AudioInput {
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
            val expectedBytes = requiredPcmBytes(durationUs)
            checkFreeSpace(expectedBytes)

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
                decodeTo(extractor, decoder, out, expectedBytes)
            } catch (e: CancellationException) {
                throw e // is an IllegalStateException; must not become DECODE_FAILED
            } catch (e: IllegalStateException) {
                throw AudioPreparationException(PipelineError.DECODE_FAILED, "Decoder failed", e)
            } catch (e: IllegalArgumentException) {
                throw AudioPreparationException(PipelineError.DECODE_FAILED, "Decoder failed", e)
            } catch (e: NullPointerException) {
                throw AudioPreparationException(PipelineError.DECODE_FAILED, "Decoder returned incomplete data", e)
            }
            return AudioInput(
                pcmFilePath = out.absolutePath,
                sampleRateHz = TARGET_SAMPLE_RATE,
                channels = 1,
                durationMs = samples * 1000 / TARGET_SAMPLE_RATE,
            )
        } finally {
            codec?.runCatching { stop() }
            codec?.runCatching { release() }
            extractor.release()
        }
    }

    /** Decodes the selected track into [out]; returns the number of 16 kHz samples written. */
    private suspend fun decodeTo(extractor: MediaExtractor, codec: MediaCodec, out: File, expectedBytes: Long?): Long {
        val info = MediaCodec.BufferInfo()
        var resampler: Resampler? = null
        var channels = 0
        var rate = 0
        var isFloat = false
        var inputDone = false
        var idleAfterInput = 0

        val writer = PcmFileWriter(out, expectedBytes, freeBytes = { StatFs(context.filesDir.path).availableBytes })
        writer.use {
            fun write(samples: FloatArray) = writer.write(samples)

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
                // Fill every free input buffer without waiting: with one frame per round the decoder
                // idled and each round waited TIMEOUT_US for output (~25 s on 73 s of AAC on A51).
                var queued = false
                while (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(0)
                    if (inIdx < 0) break
                    val size = extractor.readSampleData(codec.getInputBuffer(inIdx)!!, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                    queued = true
                }

                // Wait for output only when this round fed nothing; otherwise poll and go on feeding.
                val outIdx = codec.dequeueOutputBuffer(info, if (queued) 0 else TIMEOUT_US)
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
        }
        if (writer.samplesWritten == 0L) {
            throw AudioPreparationException(PipelineError.DECODE_FAILED, "Decoder produced no audio")
        }
        return writer.samplesWritten
    }

    private fun checkFreeSpace(required: Long?) {
        if (required == null) return
        val available = StatFs(context.filesDir.path).availableBytes
        if (available < required) throw insufficientStorage(required, available)
    }

    private companion object {
        const val TIMEOUT_US = 10_000L
        const val MAX_IDLE_POLLS = 500 // × TIMEOUT_US ≈ 5 s without output after input ended
        // jobId becomes a directory name; pipeline generates it, this guards against path escape.
        val JOB_ID = Regex("[A-Za-z0-9_-]{1,64}")
    }
}
