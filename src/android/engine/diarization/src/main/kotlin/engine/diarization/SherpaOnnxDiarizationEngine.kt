package engine.diarization

import com.k2fsa.sherpa.onnx.FastClusteringConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationSegment
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
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
    val segmentationModelPath: String = "models/sherpa-onnx-pyannote-segmentation-3-0.onnx",
    val embeddingModelPath: String = "models/3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx",
    val baseDir: File? = null,
    val clusteringThreshold: Float = 0.6f,
    val minSpeechDurationMs: Long = 250L,
    val minSilenceDurationMs: Long = 500L,
    val isFloat32Pcm: Boolean = true
)

/**
 * Offline Speaker Diarization Engine based on sherpa-onnx (OfflineSpeakerDiarization).
 */
class SherpaOnnxDiarizationEngine(
    private val config: SherpaOnnxDiarizationConfig = SherpaOnnxDiarizationConfig(),
    private val mockSegmentProvider: ((AudioInput) -> List<OfflineSpeakerDiarizationSegment>)? = null
) : DiarizationEngine {

    private var diarizer: OfflineSpeakerDiarization? = null

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
        if (diarizer == null) {
            if (mockSegmentProvider != null) return

            val segFile = resolveFile(config.segmentationModelPath)
            val embFile = resolveFile(config.embeddingModelPath)

            if (!segFile.exists()) {
                throw IllegalStateException("MODEL_LOAD_FAILED: Pyannote segmentation model not found: ${segFile.absolutePath}")
            }
            if (!embFile.exists()) {
                throw IllegalStateException("MODEL_LOAD_FAILED: Embedding model file not found: ${embFile.absolutePath}")
            }

            try {
                val diarConfig = OfflineSpeakerDiarizationConfig(
                    segmentation = OfflineSpeakerSegmentationModelConfig(
                        pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(model = segFile.absolutePath),
                        numThreads = 2
                    ),
                    embedding = SpeakerEmbeddingExtractorConfig(
                        model = embFile.absolutePath,
                        numThreads = 2,
                        provider = "cpu"
                    ),
                    clustering = FastClusteringConfig(
                        numClusters = -1,
                        threshold = config.clusteringThreshold
                    ),
                    minDurationOn = config.minSpeechDurationMs / 1000.0f,
                    minDurationOff = config.minSilenceDurationMs / 1000.0f
                )
                diarizer = OfflineSpeakerDiarization(diarConfig)
            } catch (t: Throwable) {
                throw IllegalStateException("MODEL_LOAD_FAILED: Failed to initialize sherpa-onnx diarization: ${t.message}", t)
            }
        }
    }

    @Synchronized
    fun release() {
        diarizer?.release()
        diarizer = null
    }

    override suspend fun diarize(input: AudioInput): List<SpeakerTurn> = withContext(Dispatchers.Default) {
        if (input.durationMs <= 0) {
            return@withContext emptyList()
        }

        val rawSegments = if (mockSegmentProvider != null) {
            mockSegmentProvider.invoke(input)
        } else {
            ensureInitialized()
            val samples = readPcmSamples(input)
            if (samples.isEmpty()) {
                return@withContext emptyList()
            }

            val d = diarizer ?: throw IllegalStateException("MODEL_LOAD_FAILED: Diarizer is null")
            val segments = try {
                d.process(samples)
            } catch (t: Throwable) {
                throw IllegalStateException("ENGINE_FAILED: Diarization process failed: ${t.message}", t)
            }
            segments.toList()
        }

        mapSegmentsToSpeakerTurns(rawSegments, input.durationMs)
    }

    @Synchronized
    private fun ensureInitialized() {
        if (diarizer == null) {
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
        segments: List<OfflineSpeakerDiarizationSegment>,
        maxDurationMs: Long
    ): List<SpeakerTurn> {
        if (segments.isEmpty()) return emptyList()

        val sortedSegments = segments.sortedBy { it.start }
        val clusterToSpeakerId = mutableMapOf<Int, String>()
        val turns = mutableListOf<SpeakerTurn>()

        for (seg in sortedSegments) {
            val safeStart = (seg.start * 1000f).toLong().coerceAtLeast(0L)
            val safeEnd = (seg.end * 1000f).toLong().coerceAtLeast(safeStart + 1L).coerceAtMost(maxDurationMs)
            if (safeStart >= safeEnd) continue

            val speakerId = clusterToSpeakerId.getOrPut(seg.speaker) {
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
