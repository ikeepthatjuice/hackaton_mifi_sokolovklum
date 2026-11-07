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

/**
 * Configuration for Whisper ASR engine.
 *
 * @param modelPath Path to model file (absolute or relative to baseDir/filesDir)
 * @param baseDir Optional base directory to resolve relative model paths (e.g. context.filesDir)
 * @param language Language code ("ru" by default, or "auto")
 * @param threads Number of CPU threads (recommend 4 on big/MID cores)
 * @param enableTokenTimestamps Extract precise token timestamps
 * @param isFloat32Pcm True if raw PCM is 32-bit float LE, false if 16-bit signed integer LE
 */
data class WhisperAsrConfig(
    val modelPath: String = "models/ggml-base.bin",
    val baseDir: File? = null,
    val language: String = "ru",
    val threads: Int = 4,
    val enableTokenTimestamps: Boolean = true,
    val isFloat32Pcm: Boolean = true
)

/**
 * Offline ASR Engine based on whisper.cpp native inference.
 */
class WhisperAsrEngine(
    private val config: WhisperAsrConfig = WhisperAsrConfig(),
    private val mockTokenProvider: ((AudioInput) -> List<WhisperToken>)? = null
) : AsrEngine {

    private var contextHandle: Long = 0L

    fun resolveModelFile(): File {
        val path = config.modelPath
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
            if (!WhisperNative.isNativeLoaded()) {
                if (mockTokenProvider != null) return // Allowed only in unit-test mock mode
                throw IllegalStateException("MODEL_LOAD_FAILED: Native whisper library is not loaded: ${WhisperNative.getLoadError()?.message}")
            }

            val modelFile = resolveModelFile()
            if (!modelFile.exists()) {
                throw IllegalStateException("MODEL_LOAD_FAILED: Model file not found at: ${modelFile.absolutePath}")
            }

            contextHandle = WhisperNative.initContext(modelFile.absolutePath)
            if (contextHandle == 0L) {
                throw IllegalStateException("MODEL_LOAD_FAILED: whisper_init_from_file failed for: ${modelFile.absolutePath}")
            }
        }
    }

    @Synchronized
    fun release() {
        if (contextHandle != 0L) {
            WhisperNative.freeContext(contextHandle)
            contextHandle = 0L
        }
    }

    override suspend fun transcribe(input: AudioInput): List<AsrWord> = withContext(Dispatchers.Default) {
        if (input.durationMs <= 0) {
            return@withContext emptyList()
        }

        val tokens = if (mockTokenProvider != null) {
            mockTokenProvider.invoke(input)
        } else {
            if (!WhisperNative.isNativeLoaded()) {
                throw IllegalStateException("MODEL_LOAD_FAILED: Native whisper library not loaded: ${WhisperNative.getLoadError()?.message}")
            }
            ensureInitialized()

            val samples = readPcmSamples(input)
            if (samples.isEmpty()) {
                return@withContext emptyList()
            }

            val rawTokens = WhisperNative.transcribePcm(
                contextHandle = contextHandle,
                samples = samples,
                language = config.language,
                threads = config.threads,
                enableTokenTimestamps = config.enableTokenTimestamps
            ) ?: throw IllegalStateException("ENGINE_FAILED: Whisper transcription returned null")

            rawTokens.toList()
        }

        aggregateTokensToWords(tokens, input.durationMs)
    }

    @Synchronized
    private fun ensureInitialized() {
        if (contextHandle == 0L) {
            initialize()
        }
    }

    /**
     * Reads PCM streamingly using a small 64KB chunk buffer to prevent OOM on long recordings.
     */
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

        val buffer = ByteArray(65536) // 64 KB buffer
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

    internal fun aggregateTokensToWords(tokens: List<WhisperToken>, maxDurationMs: Long): List<AsrWord> {
        if (tokens.isEmpty()) return emptyList()

        val rawWords = mutableListOf<AsrWord>()
        val currentWordChars = StringBuilder()
        var currentWordStartMs = -1L
        var currentWordEndMs = -1L

        fun flushWord() {
            if (currentWordChars.isNotBlank() && currentWordStartMs >= 0) {
                val cleanedText = currentWordChars.toString().trim()
                if (cleanedText.isNotEmpty()) {
                    rawWords.add(AsrWord(cleanedText, currentWordStartMs, currentWordEndMs))
                }
            }
            currentWordChars.clear()
            currentWordStartMs = -1L
            currentWordEndMs = -1L
        }

        for (token in tokens) {
            val rawTokenText = token.text
            if (rawTokenText.isEmpty()) continue

            // 1. Пропускать служебные токены вида [_...] / <|...|>
            val trimmed = rawTokenText.trim()
            if (trimmed.startsWith("<|") || trimmed.startsWith("[_")) {
                continue
            }

            // 2. Склеивать подслова в слова: новый AsrWord начинается с токена, у которого текст начинается с пробела
            val startsWithSpace = rawTokenText.startsWith(" ") ||
                    rawTokenText.startsWith("\u0120") ||
                    rawTokenText.startsWith("\u2581")

            val tokenContent = rawTokenText
                .replace(" ", "")
                .replace("\u0120", "")
                .replace("\u2581", "")

            if (startsWithSpace && currentWordChars.isNotEmpty()) {
                flushWord()
            }

            if (tokenContent.isNotEmpty()) {
                if (currentWordStartMs < 0) {
                    currentWordStartMs = token.startMs // t0 слова = t0 первого токена
                }
                currentWordChars.append(tokenContent)
                currentWordEndMs = token.endMs // t1 = t1 последнего
            }
        }

        flushWord()

        // 3. После склейки — нормализация по порядку:
        //    start = max(start, prevEnd); end = max(end, start + 1); end = min(end, durationMs);
        //    если start >= durationMs — слово отбросить.
        val normalizedWords = mutableListOf<AsrWord>()
        var prevEnd = 0L

        for (word in rawWords) {
            val start = maxOf(word.startMs, prevEnd)
            var end = maxOf(word.endMs, start + 1L)
            end = minOf(end, maxDurationMs)

            if (start >= maxDurationMs) {
                continue
            }
            if (end <= start) {
                continue
            }

            normalizedWords.add(AsrWord(word.text, start, end))
            prevEnd = end
        }

        return normalizedWords
    }
}

