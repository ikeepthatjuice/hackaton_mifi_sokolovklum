// Copyright (c)  2023  Xiaomi Corporation
// Apache-2.0 License
// Source: https://github.com/k2-fsa/sherpa-onnx/tree/v1.10.30/sherpa-onnx/kotlin-api

package com.k2fsa.sherpa.onnx

data class SpeakerEmbeddingExtractorConfig(
    var model: String = "",
    var numThreads: Int = 1,
    var debug: Boolean = false,
    var provider: String = "cpu"
)

