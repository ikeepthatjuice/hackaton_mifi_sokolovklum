package com.neurostenographer.app.di

import com.neurostenographer.app.BuildConfig
import core.contracts.AsrEngine
import core.contracts.DiarizationEngine
import core.contracts.PipelineError
import core.contracts.TranscriptPostprocessor
import core.pipeline.EngineSpec
import core.pipeline.PipelineFailure
import core.pipeline.PipelineEngines
import engine.alignment.TranscriptAlignerImpl
import engine.asr.GigaAmAsrEngine
import engine.asr.GigaAmConfig
import engine.diarization.SherpaOnnxDiarizationConfig
import engine.diarization.SherpaOnnxDiarizationEngine
import engine.llm.LlamaCppTextGenerator
import engine.llm.LocalTranscriptPostprocessor
import kotlinx.coroutines.runBlocking

/**
 * Real offline engines (release, and debug with -PrealEngines). Model names are `target_file`
 * entries of models/manifest.json; AssetModelStore copies them from APK assets to filesDir/models,
 * and `load` gets those absolute paths. The pipeline loads each engine right before its stage and
 * closes it right after: sherpa → whisper → LLM, one model in memory at a time.
 */
// asrLanguage: the app always uses "ru". GigaAM is Russian-only, so another language fails the job with ENGINE_FAILED.
internal fun pipelineEngines(asrLanguage: String = "ru") = PipelineEngines(
    diarization = diarizationSpec(),
    asr = EngineSpec(GIGAAM) { (encoder, decoder, joiner, tokens) ->
        // A clear job error instead of a Russian model transcribing another language.
        if (asrLanguage != "ru") throw PipelineFailure(PipelineError.ENGINE_FAILED, detail = "ASR supports only ru, got $asrLanguage")
        GigaAm(GigaAmAsrEngine(GigaAmConfig(encoder.path, decoder.path, joiner.path, tokens.path, language = asrLanguage)))
    },
    aligner = TranscriptAlignerImpl(),
    postprocessor = EngineSpec(listOf(LLM)) { (model) ->
        Postprocessor(LlamaCppTextGenerator(model, BuildConfig.LLM_SHA256))
    },
)

/** Diarization with a given embedding model ([EMBEDDINGS] value) and clustering threshold (null = engine default). */
internal fun diarizationSpec(embedding: String = EMBEDDING, threshold: Float? = null): EngineSpec<DiarizationEngine> =
    EngineSpec(listOf(SEGMENTATION, embedding)) { (segmentation, emb) ->
        val config = SherpaOnnxDiarizationConfig(segmentationModelPath = segmentation.path, embeddingModelPath = emb.path)
        Diarization(SherpaOnnxDiarizationEngine(if (threshold == null) config else config.copy(clusteringThreshold = threshold)))
    }

private const val SEGMENTATION = "sherpa-onnx-pyannote-segmentation-3-0.onnx"
private const val EMBEDDING = "3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx"

/** Embedding models in models/manifest.json, by short name (diarization sweep in FullCycleTest). */
internal val EMBEDDINGS = mapOf(
    "eres2net" to EMBEDDING,
    "campplus" to "3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx",
)
private const val LLM = "Qwen3-0.6B-Q4_K_M.gguf"
private val GIGAAM = listOf("encoder.int8.onnx", "decoder.onnx", "joiner.onnx", "tokens.txt").map { "gigaam-v3-punct-$it" }

// Engines free native memory with release()/close(); the pipeline closes only AutoCloseable.
private class Diarization(private val e: SherpaOnnxDiarizationEngine) : DiarizationEngine by e, AutoCloseable {
    override fun close() = e.release()
}

private class GigaAm(private val e: GigaAmAsrEngine) : AsrEngine by e, AutoCloseable {
    override fun close() = e.release()
}

private class Postprocessor(private val generator: LlamaCppTextGenerator) :
    TranscriptPostprocessor by LocalTranscriptPostprocessor(generator), AutoCloseable {
    // close() waits for a cancelled native call before freeing the model, so it must block here.
    override fun close() = runBlocking { generator.close() }
}
