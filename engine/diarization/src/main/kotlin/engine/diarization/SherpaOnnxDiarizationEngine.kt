package engine.diarization

import core.contracts.AudioInput
import core.contracts.DiarizationEngine
import core.contracts.SpeakerTurn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Configuration for Sherpa-onnx Diarization engine.
 *
 * @param vadModelPath Absolute path to Silero VAD ONNX model
 * @param embeddingModelPath Absolute path to 3D-Speaker / Cam++ embedding ONNX model
 * @param clusteringThreshold Cosine similarity threshold for clustering
 * @param minSpeechDurationMs Minimum speech segment duration in milliseconds
 * @param minSilenceDurationMs Minimum silence interval to split turns
 * @param isFloat32Pcm True if input PCM is 32-bit float LE, false if 16-bit signed integer LE
 */
data class SherpaOnnxDiarizationConfig(
    val vadModelPath: String = "",
    val embeddingModelPath: String = "",
    val clusteringThreshold: Float = 0.6f,
    val minSpeechDurationMs: Long = 250L,
    val minSilenceDurationMs: Long = 500L,
    val isFloat32Pcm: Boolean = true
)

/**
 * Offline Speaker Diarization Engine based on sherpa-onnx (Silero VAD + 3D-Speaker embedding + clustering).
 * Emits SpeakerTurn intervals with stable sequential speaker identifiers (speaker_1, speaker_2, ...).
 */
class SherpaOnnxDiarizationEngine(
    private val config: SherpaOnnxDiarizationConfig = SherpaOnnxDiarizationConfig(),
    private val mockSegmentProvider: ((AudioInput) -> List<SherpaOnnxSegment>)? = null
) : DiarizationEngine {

    private var contextHandle: Long = 0L

    /**
     * Initializes the native diarization context.
     */
    @Synchronized
    fun initialize() {
        if (contextHandle == 0L && SherpaOnnxNative.isNativeLoaded()) {
            if (config.vadModelPath.isNotBlank() && config.embeddingModelPath.isNotBlank()) {
                val vadFile = File(config.vadModelPath)
                val embFile = File(config.embeddingModelPath)
                if (!vadFile.exists()) {
                    throw IllegalArgumentException("VAD model file not found: ${config.vadModelPath}")
                }
                if (!embFile.exists()) {
                    throw IllegalArgumentException("Embedding model file not found: ${config.embeddingModelPath}")
                }

                contextHandle = SherpaOnnxNative.initDiarization(
                    vadModelPath = config.vadModelPath,
                    embeddingModelPath = config.embeddingModelPath,
                    clusteringThreshold = config.clusteringThreshold,
                    minSpeechDurationMs = config.minSpeechDurationMs,
                    minSilenceDurationMs = config.minSilenceDurationMs
                )
                if (contextHandle == 0L) {
                    throw IllegalStateException("Failed to initialize sherpa-onnx diarization context")
                }
            }
        }
    }

    /**
     * Releases native diarization context.
     */
    @Synchronized
    fun release() {
        if (contextHandle != 0L) {
            SherpaOnnxNative.freeDiarization(contextHandle)
            contextHandle = 0L
        }
    }

    override suspend fun diarize(input: AudioInput): List<SpeakerTurn> = withContext(Dispatchers.Default) {
        if (input.durationMs <= 0) {
            return@withContext emptyList()
        }

        val rawSegments = if (mockSegmentProvider != null) {
            mockSegmentProvider.invoke(input)
        } else if (SherpaOnnxNative.isNativeLoaded()) {
            ensureInitialized()
            val samples = readPcmSamples(input)
            if (samples.isEmpty()) {
                return@withContext emptyList()
            }
            SherpaOnnxNative.diarizePcm(
                contextHandle = contextHandle,
                samples = samples,
                sampleRate = input.sampleRateHz
            ).toList()
        } else {
            throw IllegalStateException(
                "Native sherpa-onnx library (libsherpa_onnx_jni.so) is not loaded on this platform: " +
                        "${SherpaOnnxNative.getLoadError()?.message}. Use mockSegmentProvider for host JVM tests."
            )
        }

        mapSegmentsToSpeakerTurns(rawSegments, input.durationMs)
    }

    @Synchronized
    private fun ensureInitialized() {
        if (contextHandle == 0L) {
            initialize()
        }
    }

    /**
     * Reads raw PCM audio file into normalized float32 samples [-1.0, 1.0].
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
     * Maps raw cluster IDs to canonical sequential speaker identifiers ("speaker_1", "speaker_2", ...)
     * according to first appearance time, ensuring returning speakers retain their original ID.
     */
    internal fun mapSegmentsToSpeakerTurns(
        segments: List<SherpaOnnxSegment>,
        maxDurationMs: Long
    ): List<SpeakerTurn> {
        if (segments.isEmpty()) return emptyList()

        // Sort by startMs to ensure stable appearance order
        val sortedSegments = segments.sortedBy { it.startMs }

        val clusterToSpeakerId = mutableMapOf<Int, String>()
        val turns = mutableListOf<SpeakerTurn>()

        for (seg in sortedSegments) {
            val safeStart = seg.startMs.coerceAtLeast(0L)
            val safeEnd = seg.endMs.coerceAtLeast(safeStart + 1L).coerceAtMost(maxDurationMs)
            if (safeStart >= safeEnd) continue

            val speakerId = clusterToSpeakerId.getOrPut(seg.clusterId) {
                "speaker_${clusterToSpeakerId.size + 1}"
            }

            turns.add(
                SpeakerTurn(
                    speakerId = speakerId,
                    startMs = safeStart,
                    endMs = safeEnd
                )
            )
        }

        return mergeAdjacentSameSpeakerTurns(turns)
    }

    /**
     * Merges adjacent turns from the same speaker if the gap between them is smaller than minSilenceDurationMs.
     */
    private fun mergeAdjacentSameSpeakerTurns(turns: List<SpeakerTurn>): List<SpeakerTurn> {
        if (turns.size <= 1) return turns

        val merged = mutableListOf<SpeakerTurn>()
        var current = turns.first()

        for (i in 1 until turns.size) {
            val next = turns[i]
            if (next.speakerId == current.speakerId && (next.startMs - current.endMs) < config.minSilenceDurationMs) {
                current = current.copy(endMs = maxOf(current.endMs, next.endMs))
            } else {
                merged.add(current)
                current = next
            }
        }
        merged.add(current)

        return merged
    }
}

