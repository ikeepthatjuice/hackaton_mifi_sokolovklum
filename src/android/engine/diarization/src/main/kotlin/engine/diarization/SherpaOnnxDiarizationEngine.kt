package engine.diarization

import core.contracts.AudioInput
import core.contracts.DiarizationEngine
import core.contracts.SpeakerTurn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Configuration for Sherpa-onnx Diarization engine.
 */
data class SherpaOnnxDiarizationConfig(
    val vadModelPath: String = "models/silero_vad.onnx",
    val embeddingModelPath: String = "models/3dspeaker_speech_campplus_sv_ru_voxceleb_16k.onnx",
    val baseDir: File? = null,
    val clusteringThreshold: Float = 0.6f,
    val minSpeechDurationMs: Long = 250L,
    val minSilenceDurationMs: Long = 500L,
    val isFloat32Pcm: Boolean = true
)

/**
 * Offline Speaker Diarization Engine based on sherpa-onnx.
 */
class SherpaOnnxDiarizationEngine(
    private val config: SherpaOnnxDiarizationConfig = SherpaOnnxDiarizationConfig(),
    private val mockSegmentProvider: ((AudioInput) -> List<SherpaOnnxSegment>)? = null
) : DiarizationEngine {

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
            if (!SherpaOnnxNative.isNativeLoaded()) {
                if (mockSegmentProvider != null) return
                throw IllegalStateException("MODEL_LOAD_FAILED: Native sherpa-onnx library is not loaded: ${SherpaOnnxNative.getLoadError()?.message}")
            }

            val vadFile = resolveFile(config.vadModelPath)
            val embFile = resolveFile(config.embeddingModelPath)

            if (!vadFile.exists()) {
                throw IllegalStateException("MODEL_LOAD_FAILED: VAD model file not found: ${vadFile.absolutePath}")
            }
            if (!embFile.exists()) {
                throw IllegalStateException("MODEL_LOAD_FAILED: Embedding model file not found: ${embFile.absolutePath}")
            }

            contextHandle = SherpaOnnxNative.initDiarization(
                vadModelPath = vadFile.absolutePath,
                embeddingModelPath = embFile.absolutePath,
                clusteringThreshold = config.clusteringThreshold,
                minSpeechDurationMs = config.minSpeechDurationMs,
                minSilenceDurationMs = config.minSilenceDurationMs
            )

            if (contextHandle == 0L) {
                throw IllegalStateException("MODEL_LOAD_FAILED: Failed to initialize sherpa-onnx diarization context")
            }
        }
    }

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
        } else {
            if (!SherpaOnnxNative.isNativeLoaded()) {
                throw IllegalStateException("MODEL_LOAD_FAILED: Native sherpa-onnx library not loaded: ${SherpaOnnxNative.getLoadError()?.message}")
            }
            ensureInitialized()

            val samples = readPcmSamples(input)
            if (samples.isEmpty()) {
                return@withContext emptyList()
            }

            val segments = SherpaOnnxNative.diarizePcm(
                contextHandle = contextHandle,
                samples = samples,
                sampleRate = input.sampleRateHz
            ) ?: throw IllegalStateException("ENGINE_FAILED: Diarization processing failed")

            segments.toList()
        }

        mapSegmentsToSpeakerTurns(rawSegments, input.durationMs)
    }

    @Synchronized
    private fun ensureInitialized() {
        if (contextHandle == 0L) {
            initialize()
        }
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

    internal fun mapSegmentsToSpeakerTurns(
        segments: List<SherpaOnnxSegment>,
        maxDurationMs: Long
    ): List<SpeakerTurn> {
        if (segments.isEmpty()) return emptyList()

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

