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
 * @param modelPath Absolute path to ggml whisper model file (e.g. ggml-base.bin or ggml-base-q5_1.bin)
 * @param language Language code ("ru" by default, or "auto")
 * @param threads Number of CPU threads to use on Android (recommend 4 on big/MID cores)
 * @param enableTokenTimestamps Extract precise token timestamps instead of segment averages
 * @param isFloat32Pcm True if raw PCM is 32-bit float LE, false if 16-bit signed integer LE
 */
data class WhisperAsrConfig(
    val modelPath: String = "",
    val language: String = "ru",
    val threads: Int = 4,
    val enableTokenTimestamps: Boolean = true,
    val isFloat32Pcm: Boolean = true
)

/**
 * Offline ASR Engine based on whisper.cpp native inference.
 * Extracts word-level timestamps directly from Whisper's token alignment.
 */
class WhisperAsrEngine(
    private val config: WhisperAsrConfig = WhisperAsrConfig(),
    private val mockTokenProvider: ((AudioInput) -> List<WhisperToken>)? = null
) : AsrEngine {

    private var contextHandle: Long = 0L

    /**
     * Initializes the native Whisper context.
     * Can be invoked eagerly or lazily on first transcribe.
     */
    @Synchronized
    fun initialize() {
        if (contextHandle == 0L && WhisperNative.isNativeLoaded() && config.modelPath.isNotBlank()) {
            val modelFile = File(config.modelPath)
            if (!modelFile.exists()) {
                throw IllegalArgumentException("Whisper model file does not exist at: ${config.modelPath}")
            }
            contextHandle = WhisperNative.initContext(config.modelPath)
            if (contextHandle == 0L) {
                throw IllegalStateException("Failed to initialize whisper_context from: ${config.modelPath}")
            }
        }
    }

    /**
     * Releases the native whisper_context resources.
     */
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
        } else if (WhisperNative.isNativeLoaded()) {
            ensureInitialized()
            val samples = readPcmSamples(input)
            if (samples.isEmpty()) {
                return@withContext emptyList()
            }
            WhisperNative.transcribePcm(
                contextHandle = contextHandle,
                samples = samples,
                language = config.language,
                threads = config.threads,
                enableTokenTimestamps = config.enableTokenTimestamps
            ).toList()
        } else {
            // If native library is not available and no mock provider was specified
            throw IllegalStateException(
                "Native whisper library (libwhisper_jni.so) is not loaded on this platform: " +
                        "${WhisperNative.getLoadError()?.message}. Use mockTokenProvider for host JVM tests."
            )
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
     * Reads raw PCM audio file into normalized float32 samples [-1.0, 1.0].
     * Supports both float32 little-endian (baseline standard) and 16-bit PCM.
     */
    internal fun readPcmSamples(input: AudioInput): FloatArray {
        val file = File(input.pcmFilePath)
        if (!file.exists() || file.length() == 0L) {
            return FloatArray(0)
        }

        val fileBytes = file.readBytes()
        val byteBuffer = ByteBuffer.wrap(fileBytes).order(ByteOrder.LITTLE_ENDIAN)

        return if (config.isFloat32Pcm) {
            val numFloats = fileBytes.size / 4
            val samples = FloatArray(numFloats)
            for (i in 0 until numFloats) {
                samples[i] = byteBuffer.float
            }
            samples
        } else {
            val numShorts = fileBytes.size / 2
            val samples = FloatArray(numShorts)
            for (i in 0 until numShorts) {
                samples[i] = byteBuffer.short / 32768.0f
            }
            samples
        }
    }

    /**
     * Converts token stream with millisecond timestamps into full words.
     * Whisper tokens that belong to the same word are merged, and their
     * start and end timestamps define the exact word boundaries.
     */
    internal fun aggregateTokensToWords(tokens: List<WhisperToken>, maxDurationMs: Long): List<AsrWord> {
        if (tokens.isEmpty()) return emptyList()

        val words = mutableListOf<AsrWord>()
        val currentWordChars = StringBuilder()
        var currentWordStartMs = -1L
        var currentWordEndMs = -1L

        fun flushWord() {
            if (currentWordChars.isNotBlank() && currentWordStartMs >= 0) {
                val cleanedText = currentWordChars.toString().trim()
                if (cleanedText.isNotEmpty()) {
                    val safeStart = currentWordStartMs.coerceAtLeast(0L)
                    val safeEnd = currentWordEndMs.coerceAtLeast(safeStart + 1L).coerceAtMost(maxDurationMs)
                    words.add(AsrWord(cleanedText, safeStart, safeEnd))
                }
            }
            currentWordChars.clear()
            currentWordStartMs = -1L
            currentWordEndMs = -1L
        }

        for (token in tokens) {
            val rawTokenText = token.text
            if (rawTokenText.isEmpty()) continue

            // Check if token represents boundary (starts with space ' ' or byte BPE space ' ')
            val startsWithSpace = rawTokenText.startsWith(" ") ||
                    rawTokenText.startsWith("\u0120") ||
                    rawTokenText.startsWith(" ")

            val tokenContent = rawTokenText
                .replace(" ", "")
                .replace("\u0120", "")
                .replace(" ", "")

            if (startsWithSpace && currentWordChars.isNotEmpty()) {
                flushWord()
            }

            if (tokenContent.isNotEmpty()) {
                if (currentWordStartMs < 0) {
                    currentWordStartMs = token.startMs
                }
                currentWordChars.append(tokenContent)
                currentWordEndMs = token.endMs
            }
        }

        flushWord()

        // Sanity checks: strictly monotonic startMs < endMs, non-negative
        return words.filter { it.startMs < it.endMs && it.text.isNotBlank() }
    }
}

