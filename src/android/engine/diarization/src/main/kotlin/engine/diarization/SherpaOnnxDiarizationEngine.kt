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
 * Supported embedding model architectures for offline speaker diarization.
 */
enum class SpeakerEmbeddingModel(
    val defaultRelativePath: String,
    val description: String
) {
    /**
     * 3D-Speaker ERes2Net base model (~39.6 MB).
     * High accuracy, default choice for offline speaker diarization.
     */
    ERES2NET(
        defaultRelativePath = "models/3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx",
        description = "3D-Speaker ERes2Net Base"
    ),

    /**
     * 3D-Speaker CAM++ model (~20.5 MB).
     * Lightweight and fast alternative for low-power mobile devices.
     */
    CAMPPLUS(
        defaultRelativePath = "models/3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx",
        description = "3D-Speaker CAM++"
    );
}

/**
 * Configuration for Sherpa-onnx Diarization engine.
 *
 * @param segmentationModelPath Path to pyannote segmentation ONNX model
 * @param embeddingModelType High-level embedding model choice (ERES2NET by default, CAMPPLUS as fast option)
 * @param embeddingModelPath Explicit path to embedding ONNX model (defaults to embeddingModelType.defaultRelativePath)
 * @param baseDir Optional base directory to resolve relative paths
 * @param clusteringThreshold Clustering threshold (e.g. 0.70f..0.85f; higher threshold -> fewer clusters). Default 0.75f.
 * @param minSpeechDurationMs Minimum speech segment duration in ms (default 500ms)
 * @param minSilenceDurationMs Minimum silence duration between segments in ms (default 500ms)
 * @param minSpeakerTotalDurationMs Minimum total speech required for a speaker cluster to not be merged (default 2000ms)
 * @param isFloat32Pcm True if PCM samples are 32-bit float LE
 */
data class SherpaOnnxDiarizationConfig(
    val segmentationModelPath: String = "models/sherpa-onnx-pyannote-segmentation-3-0.onnx",
    val embeddingModelType: SpeakerEmbeddingModel = SpeakerEmbeddingModel.ERES2NET,
    val embeddingModelPath: String = embeddingModelType.defaultRelativePath,
    val baseDir: File? = null,
    val clusteringThreshold: Float = 0.75f,
    val minSpeechDurationMs: Long = 500L,
    val minSilenceDurationMs: Long = 500L,
    val minSpeakerTotalDurationMs: Long = 2000L,
    val isFloat32Pcm: Boolean = true
) {
    companion object {
        const val MODEL_ERES2NET = "models/3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx"
        const val MODEL_CAMPPLUS = "models/3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx"

        fun withERes2Net(threshold: Float = 0.75f, baseDir: File? = null) = SherpaOnnxDiarizationConfig(
            embeddingModelType = SpeakerEmbeddingModel.ERES2NET,
            embeddingModelPath = MODEL_ERES2NET,
            clusteringThreshold = threshold,
            baseDir = baseDir
        )

        fun withCamPlus(threshold: Float = 0.75f, baseDir: File? = null) = SherpaOnnxDiarizationConfig(
            embeddingModelType = SpeakerEmbeddingModel.CAMPPLUS,
            embeddingModelPath = MODEL_CAMPPLUS,
            clusteringThreshold = threshold,
            baseDir = baseDir
        )
    }
}

/**
 * Offline Speaker Diarization Engine based on sherpa-onnx C-API.
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

            val segFile = resolveFile(config.segmentationModelPath)
            val embFile = resolveFile(config.embeddingModelPath)

            if (!segFile.exists()) {
                throw IllegalStateException("MODEL_LOAD_FAILED: Pyannote segmentation model not found: ${segFile.absolutePath}")
            }
            if (!embFile.exists()) {
                throw IllegalStateException("MODEL_LOAD_FAILED: Embedding model file not found: ${embFile.absolutePath}")
            }

            contextHandle = SherpaOnnxNative.initDiarization(
                segmentationModelPath = segFile.absolutePath,
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

        // 1. Sanitize boundaries and sort chronologically
        val validSegments = segments.mapNotNull { seg ->
            val safeStart = seg.startMs.coerceAtLeast(0L)
            val safeEnd = seg.endMs.coerceAtLeast(safeStart + 1L).coerceAtMost(maxDurationMs)
            if (safeStart < safeEnd) {
                seg.copy(startMs = safeStart, endMs = safeEnd)
            } else null
        }.sortedBy { it.startMs }

        if (validSegments.isEmpty()) return emptyList()

        // 2. Calculate total speaking duration per cluster
        val clusterDurations = mutableMapOf<Int, Long>()
        for (seg in validSegments) {
            val dur = seg.endMs - seg.startMs
            clusterDurations[seg.clusterId] = clusterDurations.getOrDefault(seg.clusterId, 0L) + dur
        }

        // 3. Identify significant clusters (total speech >= minSpeakerTotalDurationMs, e.g. 2000 ms)
        val significantClusters = clusterDurations.filter { it.value >= config.minSpeakerTotalDurationMs }.keys
        val targetClusters = if (significantClusters.isEmpty()) {
            clusterDurations.keys
        } else {
            significantClusters
        }

        // 4. Reassign micro-clusters (< 2s speech) to the temporally nearest significant cluster
        val remappedSegments = validSegments.map { seg ->
            if (seg.clusterId in targetClusters) {
                seg
            } else {
                val nearest = validSegments
                    .filter { it.clusterId in targetClusters }
                    .minByOrNull { other ->
                        if (seg.startMs >= other.endMs) {
                            seg.startMs - other.endMs
                        } else if (other.startMs >= seg.endMs) {
                            other.startMs - seg.endMs
                        } else {
                            0L
                        }
                    }
                if (nearest != null) {
                    seg.copy(clusterId = nearest.clusterId)
                } else {
                    seg
                }
            }
        }

        // 5. Stable assignment: speaker_1, speaker_2... by order of first appearance
        val clusterToSpeakerId = mutableMapOf<Int, String>()
        val turns = mutableListOf<SpeakerTurn>()

        for (seg in remappedSegments) {
            val speakerId = clusterToSpeakerId.getOrPut(seg.clusterId) {
                "speaker_${clusterToSpeakerId.size + 1}"
            }

            turns.add(
                SpeakerTurn(
                    speakerId = speakerId,
                    startMs = seg.startMs,
                    endMs = seg.endMs
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
