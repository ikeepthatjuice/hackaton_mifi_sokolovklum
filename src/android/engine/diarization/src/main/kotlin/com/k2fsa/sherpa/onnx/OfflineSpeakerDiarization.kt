// Copyright (c)  2023  Xiaomi Corporation
// Apache-2.0 License
// Source: https://github.com/k2-fsa/sherpa-onnx/tree/v1.10.30/sherpa-onnx/kotlin-api

package com.k2fsa.sherpa.onnx

data class OfflineSpeakerDiarizationSegment(
    val start: Float,
    val end: Float,
    val speaker: Int
)

data class OfflineSpeakerSegmentationPyannoteModelConfig(
    var model: String = ""
)

data class OfflineSpeakerSegmentationModelConfig(
    var pyannote: OfflineSpeakerSegmentationPyannoteModelConfig = OfflineSpeakerSegmentationPyannoteModelConfig(),
    var numThreads: Int = 1,
    var debug: Boolean = false,
    var provider: String = "cpu"
)

data class FastClusteringConfig(
    var numClusters: Int = -1,
    var threshold: Float = 0.5f
)

data class OfflineSpeakerDiarizationConfig(
    var segmentation: OfflineSpeakerSegmentationModelConfig = OfflineSpeakerSegmentationModelConfig(),
    var embedding: SpeakerEmbeddingExtractorConfig = SpeakerEmbeddingExtractorConfig(),
    var clustering: FastClusteringConfig = FastClusteringConfig(),
    var minDurationOn: Float = 0.2f,
    var minDurationOff: Float = 0.5f
)

class OfflineSpeakerDiarization(
    var config: OfflineSpeakerDiarizationConfig
) {
    private var ptr: Long = 0

    init {
        ptr = new(config)
    }

    protected fun finalize() {
        if (ptr != 0L) {
            delete(ptr)
            ptr = 0
        }
    }

    fun release() {
        if (ptr != 0L) {
            delete(ptr)
            ptr = 0
        }
    }

    fun process(samples: FloatArray): Array<OfflineSpeakerDiarizationSegment> {
        return process(ptr, samples)
    }

    private external fun new(config: OfflineSpeakerDiarizationConfig): Long
    private external fun delete(ptr: Long)
    private external fun process(ptr: Long, samples: FloatArray): Array<OfflineSpeakerDiarizationSegment>

    companion object {
        init {
            try {
                System.loadLibrary("sherpa-onnx-jni")
            } catch (t: Throwable) {
                // If library not available on host JVM, let callers handle or mock
            }
        }
    }
}

