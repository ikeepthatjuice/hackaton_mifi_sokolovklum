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
    val numThreads: Int = 4,
    val maxChunkDurationMs: Long = 20_000L,
    val overlapDurationMs: Long = 1000L,
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

        // 4. Chunk audio into slices <= 20 seconds with ~1s overlap
        val sampleRate = input.sampleRateHz
        val maxSamplesPerChunk = (config.maxChunkDurationMs * sampleRate / 1000L).toInt()
        val minSearchSamples = ((config.maxChunkDurationMs - 5000L) * sampleRate / 1000L).toInt().coerceAtLeast(maxSamplesPerChunk / 2)
        val overlapSamples = (config.overlapDurationMs * sampleRate / 1000L).toInt().coerceAtMost(maxSamplesPerChunk / 4)

        var words = listOf<AsrWord>()
        var chunkStartSample = 0
        var prevChunkEndSample = 0

        while (chunkStartSample < allSamples.size) {
            val remainingSamples = allSamples.size - chunkStartSample
            val chunkLength = if (remainingSamples <= maxSamplesPerChunk) {
                remainingSamples
            } else {
                findOptimalSplitPoint(allSamples, chunkStartSample + minSearchSamples, chunkStartSample + maxSamplesPerChunk) - chunkStartSample
            }

            val chunkEndSample = chunkStartSample + chunkLength
            val chunkSamples = allSamples.copyOfRange(chunkStartSample, chunkEndSample)
            val chunkStartOffsetMs = (chunkStartSample.toLong() * 1000L) / sampleRate
            val chunkDurationMs = (chunkLength.toLong() * 1000L) / sampleRate
            val overlapEndMs = (prevChunkEndSample.toLong() * 1000L) / sampleRate

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

                words = mergeChunkWords(
                    existingWords = words,
                    newChunkWords = chunkWords,
                    overlapStartMs = chunkStartOffsetMs,
                    overlapEndMs = overlapEndMs,
                    totalDurationMs = input.durationMs
                )
            }

            prevChunkEndSample = chunkEndSample
            if (chunkEndSample >= allSamples.size) {
                break
            }
            val nextStart = chunkEndSample - overlapSamples
            chunkStartSample = if (nextStart > chunkStartSample) nextStart else chunkEndSample
        }

        words
    }

    /**
     * Merges words from a newly transcribed chunk with already recognized words,
     * deduplicating overlapping words and repairing words cut off at chunk boundaries.
     * Enforces strictly monotonic timestamps bounded by [totalDurationMs].
     */
    internal fun mergeChunkWords(
        existingWords: List<AsrWord>,
        newChunkWords: List<AsrWord>,
        overlapStartMs: Long,
        overlapEndMs: Long,
        totalDurationMs: Long
    ): List<AsrWord> {
        if (existingWords.isEmpty()) return newChunkWords
        if (newChunkWords.isEmpty()) return existingWords

        val result = existingWords.toMutableList()
        var prevWordEndMs = result.lastOrNull()?.endMs ?: 0L

        fun normalize(s: String): String =
            s.filter { it.isLetterOrDigit() }.lowercase()

        for (curr in newChunkWords) {
            val currNorm = normalize(curr.text)

            // Overlap check: words before or near the previous chunk end boundary
            if (curr.startMs < overlapEndMs + 200L) {
                // 1. Exact or near duplicate check (same normalized text + overlapping / adjacent times)
                val duplicateIndex = result.indexOfLast { prev ->
                    val prevNorm = normalize(prev.text)
                    if (prevNorm.isEmpty() || currNorm.isEmpty()) false
                    else if (prevNorm == currNorm) {
                        val timeDiff = kotlin.math.abs(curr.startMs - prev.startMs)
                        val overlap = maxOf(0L, minOf(curr.endMs, prev.endMs) - maxOf(curr.startMs, prev.startMs))
                        timeDiff <= 400L || overlap > 0L
                    } else false
                }

                if (duplicateIndex != -1) {
                    // Word already recognized in previous chunk; skip duplicate from overlap
                    continue
                }

                // 2. Repair cut-off word at seam: if prev was a prefix of curr cut at the boundary
                val truncatedIndex = result.indexOfLast { prev ->
                    val prevNorm = normalize(prev.text)
                    if (prevNorm.isNotEmpty() && currNorm.startsWith(prevNorm) && currNorm.length > prevNorm.length) {
                        val timeDiff = kotlin.math.abs(curr.startMs - prev.startMs)
                        timeDiff <= 300L
                    } else false
                }

                if (truncatedIndex != -1) {
                    val prev = result[truncatedIndex]
                    val safeStart = if (truncatedIndex > 0) maxOf(result[truncatedIndex - 1].endMs, curr.startMs) else curr.startMs
                    val safeEnd = maxOf(safeStart + 50L, curr.endMs).coerceAtMost(totalDurationMs)
                    if (safeStart < safeEnd) {
                        result[truncatedIndex] = curr.copy(startMs = safeStart, endMs = safeEnd)
                        if (truncatedIndex == result.size - 1) {
                            prevWordEndMs = safeEnd
                        }
                    }
                    continue
                }
            }

            // 3. New word: append with strictly monotonic timestamp enforcement
            val safeStart = maxOf(prevWordEndMs, curr.startMs)
            val safeEnd = maxOf(safeStart + 50L, curr.endMs).coerceAtMost(totalDurationMs)
            if (safeStart < safeEnd) {
                result.add(curr.copy(startMs = safeStart, endMs = safeEnd))
                prevWordEndMs = safeEnd
            }
        }

        return result
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

