package engine.asr

import core.contracts.AsrEngine
import core.contracts.AsrWord
import core.contracts.AudioInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Configuration for GigaAM ASR Engine via sherpa-onnx NeMo Transducer.
 *
 * @param encoderPath Path to quantized Conformer encoder (encoder.int8.onnx)
 * @param decoderPath Path to RNN-T decoder (decoder.onnx)
 * @param joinerPath Path to RNN-T joint network (joiner.onnx)
 * @param tokensPath Path to BPE vocabulary (tokens.txt)
 * @param baseDir Optional base directory to resolve relative model paths
 * @param language Language code ("ru" by default; non-ru routes to whisperFallback)
 * @param numThreads CPU threads (recommend 4 on big cores)
 * @param maxChunkDurationMs Maximum audio slice duration in ms (model limit <= 25s, default 20s)
 * @param isFloat32Pcm True if raw PCM is 32-bit float LE, false if 16-bit signed int LE
 */
data class GigaAmConfig(
    val encoderPath: String = "models/gigaam-v3-punct-encoder.int8.onnx",
    val decoderPath: String = "models/gigaam-v3-punct-decoder.onnx",
    val joinerPath: String = "models/gigaam-v3-punct-joiner.onnx",
    val tokensPath: String = "models/gigaam-v3-punct-tokens.txt",
    val baseDir: File? = null,
    val language: String = "ru",
    val numThreads: Int = 3, // A51: 3 threads beat 4 (4 threads on 3 available big cores contend).
    val maxChunkDurationMs: Long = 20_000L,
    val isFloat32Pcm: Boolean = true
)

/**
 * Offline Automatic Speech Recognition Engine based on GigaAM-v3 Transducer.
 *
 * Slices long audio into chunks <= 20s, extracts token-level timestamps,
 * reconstructs words with punctuation attached, and routes non-Russian audio to Whisper.
 */
class GigaAmAsrEngine(
    private val config: GigaAmConfig = GigaAmConfig(),
    private val whisperFallback: AsrEngine? = null,
    private val mockTokenProvider: ((AudioInput) -> List<GigaAmToken>)? = null
) : AsrEngine {

    private var contextHandle: Long = 0L

    fun resolveFile(path: String): File {
        val file = File(path)
        return if (file.isAbsolute) {
            file
        } else if (config.baseDir != null) {
            File(config.baseDir, path)
        } else {
            file
        }
    }

    @Synchronized
    fun initialize() {
        if (contextHandle == 0L) {
            if (!GigaAmNative.isNativeLoaded()) {
                if (mockTokenProvider != null) return
                throw IllegalStateException("MODEL_LOAD_FAILED: Native gigaam library not loaded: ${GigaAmNative.getLoadError()?.message}")
            }

            val enc = resolveFile(config.encoderPath)
            val dec = resolveFile(config.decoderPath)
            val joi = resolveFile(config.joinerPath)
            val tok = resolveFile(config.tokensPath)

            if (!enc.exists() || !dec.exists() || !joi.exists() || !tok.exists()) {
                if (mockTokenProvider != null) return
                throw IllegalStateException("MODEL_LOAD_FAILED: GigaAM model files missing in ${config.baseDir?.absolutePath ?: "models"}")
            }

            contextHandle = GigaAmNative.initRecognizer(
                encoderPath = enc.absolutePath,
                decoderPath = dec.absolutePath,
                joinerPath = joi.absolutePath,
                tokensPath = tok.absolutePath,
                numThreads = config.numThreads
            )

            if (contextHandle == 0L) {
                throw IllegalStateException("MODEL_LOAD_FAILED: Failed to initialize SherpaOnnx OfflineRecognizer for GigaAM")
            }
        }
    }

    fun release() {
        if (contextHandle != 0L) {
            GigaAmNative.freeRecognizer(contextHandle)
            contextHandle = 0L
        }
    }

    override suspend fun transcribe(input: AudioInput): List<AsrWord> = withContext(Dispatchers.Default) {
        if (input.durationMs <= 0) {
            return@withContext emptyList()
        }

        // 1. Language Routing: ru -> GigaAM; non-ru -> Whisper fallback
        if (config.language.lowercase() != "ru") {
            if (whisperFallback != null) {
                return@withContext whisperFallback.transcribe(input)
            }
        }

        // 2. Mock mode for unit tests
        if (mockTokenProvider != null) {
            val rawTokens = mockTokenProvider.invoke(input)
            return@withContext reconstructWords(rawTokens, chunkStartOffsetMs = 0L, chunkDurationMs = input.durationMs)
        }

        // 3. Fallback to whisper if native is not loaded and fallback is available
        if (!GigaAmNative.isNativeLoaded()) {
            if (whisperFallback != null) {
                return@withContext whisperFallback.transcribe(input)
            }
            throw IllegalStateException("MODEL_LOAD_FAILED: Native gigaam library not loaded: ${GigaAmNative.getLoadError()?.message}")
        }

        ensureInitialized()

        val allSamples = readPcmSamples(input)
        if (allSamples.isEmpty()) {
            return@withContext emptyList()
        }

        // 4. Chunk audio into slices <= 20 seconds
        val sampleRate = input.sampleRateHz
        val maxSamplesPerChunk = (config.maxChunkDurationMs * sampleRate / 1000L).toInt()
        val minSearchSamples = ((config.maxChunkDurationMs - 5000L) * sampleRate / 1000L).toInt().coerceAtLeast(maxSamplesPerChunk / 2)

        val words = mutableListOf<AsrWord>()
        var chunkStartSample = 0
        var prevWordEndMs = 0L

        while (chunkStartSample < allSamples.size) {
            val remainingSamples = allSamples.size - chunkStartSample
            val chunkLength = if (remainingSamples <= maxSamplesPerChunk) {
                remainingSamples
            } else {
                findOptimalSplitPoint(allSamples, chunkStartSample + minSearchSamples, chunkStartSample + maxSamplesPerChunk) - chunkStartSample
            }

            val chunkSamples = allSamples.copyOfRange(chunkStartSample, chunkStartSample + chunkLength)
            val chunkStartOffsetMs = (chunkStartSample.toLong() * 1000L) / sampleRate
            val chunkDurationMs = (chunkLength.toLong() * 1000L) / sampleRate

            val nativeTokens = GigaAmNative.transcribePcm(
                contextHandle = contextHandle,
                samples = chunkSamples,
                sampleRate = sampleRate
            )

            if (nativeTokens != null && nativeTokens.isNotEmpty()) {
                val chunkWords = reconstructWords(
                    tokens = nativeTokens.toList(),
                    chunkStartOffsetMs = chunkStartOffsetMs,
                    chunkDurationMs = chunkDurationMs
                )

                for (w in chunkWords) {
                    val safeStart = maxOf(prevWordEndMs, w.startMs)
                    val safeEnd = maxOf(safeStart + 50L, w.endMs).coerceAtMost(input.durationMs)
                    if (safeStart < safeEnd) {
                        words.add(w.copy(startMs = safeStart, endMs = safeEnd))
                        prevWordEndMs = safeEnd
                    }
                }
            }

            chunkStartSample += chunkLength
        }

        words
    }

    @Synchronized
    private fun ensureInitialized() {
        if (contextHandle == 0L) {
            initialize()
        }
    }

    /**
     * Finds the quietest point (lowest RMS energy) in the search window to split without cutting mid-word.
     */
    internal fun findOptimalSplitPoint(samples: FloatArray, searchStart: Int, searchEnd: Int): Int {
        val windowSize = 1600 // 100 ms at 16 kHz
        var bestIndex = searchEnd
        var minEnergy = Float.MAX_VALUE

        val step = 400 // Check every 25 ms
        var i = searchStart
        while (i + windowSize <= searchEnd && i + windowSize <= samples.size) {
            var sumSquare = 0.0f
            for (j in 0 until windowSize) {
                val s = samples[i + j]
                sumSquare += s * s
            }
            val energy = sqrt(sumSquare / windowSize)
            if (energy < minEnergy) {
                minEnergy = energy
                bestIndex = i + (windowSize / 2)
            }
            i += step
        }

        return bestIndex.coerceIn(searchStart, searchEnd)
    }

    /**
     * Reconstructs words from BPE subwords and attaches punctuation to the preceding word.
     * Enforces monotonic timestamps bounded within chunk duration.
     */
    internal fun reconstructWords(
        tokens: List<GigaAmToken>,
        chunkStartOffsetMs: Long,
        chunkDurationMs: Long
    ): List<AsrWord> {
        if (tokens.isEmpty()) return emptyList()

        val punctuationSet = setOf(',', '.', '!', '?', ':', ';', '—', '-', '…', '«', '»', '"')

        class PendingWord(
            var text: String,
            val startMs: Long,
            var endMs: Long
        )

        val words = mutableListOf<PendingWord>()
        var current: PendingWord? = null

        for (i in tokens.indices) {
            val token = tokens[i]
            val raw = token.text
            if (raw.isEmpty()) continue

            val isSpacePrefixed = raw.startsWith('\u2581') || raw.startsWith(' ')
            val clean = raw.trimStart('\u2581', ' ')
            val isPurePunctuation = clean.isNotEmpty() && clean.all { it in punctuationSet }

            val nextTimeMs = if (i + 1 < tokens.size) tokens[i + 1].timestampMs else token.timestampMs + 200L

            if (clean.isEmpty()) {
                // Standalone whitespace / BPE boundary token: marks boundary between words, never punctuation
                if (current != null) {
                    current.endMs = maxOf(current.endMs, token.timestampMs)
                    if (current.text.isNotBlank()) {
                        words.add(current)
                    }
                    current = null
                }
            } else if (isPurePunctuation) {
                // Attach punctuation to the preceding word
                if (current != null) {
                    current.text += clean
                    current.endMs = maxOf(current.endMs, token.timestampMs + 80L)
                }
            } else if (isSpacePrefixed || current == null) {
                // Starts a new word
                if (current != null && current.text.isNotBlank()) {
                    words.add(current)
                }
                current = PendingWord(
                    text = clean,
                    startMs = token.timestampMs,
                    endMs = maxOf(token.timestampMs + 100L, nextTimeMs)
                )
            } else {
                // Subword continuation: append to active word
                current.text += clean
                current.endMs = maxOf(current.endMs, nextTimeMs)
            }
        }

        if (current != null && current.text.isNotBlank()) {
            words.add(current)
        }

        // Monotonic normalization and offset addition
        val result = mutableListOf<AsrWord>()
        var lastEnd = 0L

        for (w in words) {
            val text = w.text.trim()
            if (text.isEmpty()) continue

            val safeStart = maxOf(lastEnd, w.startMs)
            val safeEnd = maxOf(safeStart + 50L, w.endMs).coerceAtMost(chunkDurationMs)
            if (safeStart < safeEnd) {
                result.add(
                    AsrWord(
                        text = text,
                        startMs = chunkStartOffsetMs + safeStart,
                        endMs = chunkStartOffsetMs + safeEnd
                    )
                )
                lastEnd = safeEnd
            }
        }

        return result
    }

    internal fun readPcmSamples(input: AudioInput): FloatArray {
        val file = File(input.pcmFilePath)
        if (!file.exists() || file.length() == 0L) {
            return FloatArray(0)
        }

        val totalBytes = file.length()
        val numSamples = if (config.isFloat32Pcm) {
            (totalBytes / 4L).toInt()
        } else {
            (totalBytes / 2L).toInt()
        }

        val samples = FloatArray(numSamples)
        var sampleIndex = 0

        val buffer = ByteArray(65536)
        val byteBuffer = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN)

        FileInputStream(file).buffered().use { stream ->
            var bytesRead: Int
            while (stream.read(buffer).also { bytesRead = it } != -1 && sampleIndex < numSamples) {
                byteBuffer.position(0)
                byteBuffer.limit(bytesRead)

                if (config.isFloat32Pcm) {
                    while (byteBuffer.remaining() >= 4 && sampleIndex < numSamples) {
                        samples[sampleIndex++] = byteBuffer.float
                    }
                } else {
                    while (byteBuffer.remaining() >= 2 && sampleIndex < numSamples) {
                        samples[sampleIndex++] = byteBuffer.short / 32768.0f
                    }
                }
            }
        }

        return if (sampleIndex == numSamples) samples else samples.copyOf(sampleIndex)
    }
}

