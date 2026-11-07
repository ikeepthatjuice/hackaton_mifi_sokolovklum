package com.neurostenographer.app.di

import android.util.Log
import com.neurostenographer.app.BuildConfig
import core.contracts.AsrEngine
import core.contracts.DiarizationEngine
import core.contracts.TranscriptPostprocessor
import core.pipeline.EngineSpec
import core.pipeline.PipelineEngines
import engine.alignment.TranscriptAlignerImpl
import engine.asr.WhisperAsrConfig
import engine.asr.WhisperAsrEngine
import engine.diarization.SherpaOnnxDiarizationConfig
import engine.diarization.SherpaOnnxDiarizationEngine
import engine.llm.LlamaCppTextGenerator
import engine.llm.LocalTranscriptPostprocessor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking

/**
 * Real offline engines (release, and debug with -PrealEngines). Model names are `target_file`
 * entries of models/manifest.json; AssetModelStore copies them from APK assets to filesDir/models,
 * and `load` gets those absolute paths. The pipeline loads each engine right before its stage and
 * closes it right after: sherpa → whisper → LLM, one model in memory at a time.
 */
// asrLanguage: the app always uses "ru"; FullCycleTest passes "en" for the English jfk.wav sample.
internal fun pipelineEngines(asrLanguage: String = "ru") = PipelineEngines(
    diarization = EngineSpec(listOf(SEGMENTATION, EMBEDDING)) { (segmentation, embedding) ->
        Diarization(
            SherpaOnnxDiarizationEngine(
                SherpaOnnxDiarizationConfig(
                    segmentationModelPath = segmentation.path,
                    embeddingModelPath = embedding.path,
                ),
            ),
        )
    },
    asr = EngineSpec(listOf(WHISPER)) { (model) ->
        Asr(WhisperAsrEngine(WhisperAsrConfig(modelPath = model.path, language = asrLanguage)))
    },
    aligner = TranscriptAlignerImpl(),
    postprocessor = EngineSpec(listOf(LLM)) { (model) ->
        val generator = LlamaCppTextGenerator(model, BuildConfig.LLM_SHA256)
        // Warm the fixed prompt KV here: load runs before the LLM budget starts. A failed warm-up is not a
        // job failure: generate() retries it inside the budget and the stage falls back to rawText as before.
        try {
            generator.prepare()
        } catch (e: CancellationException) {
            // load() has not returned ownership to stage(), so release here on cancellation.
            generator.close()
            throw e
        } catch (e: Exception) {
            Log.w("PipelineEngines", "LLM warm-up failed: ${e.javaClass.simpleName}")
        }
        Postprocessor(generator)
    },
)

private const val SEGMENTATION = "sherpa-onnx-pyannote-segmentation-3-0.onnx"
private const val EMBEDDING = "3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx"
private const val WHISPER = "ggml-base.bin"
private const val LLM = "Qwen3-0.6B-Q4_K_M.gguf"

// Engines free native memory with release()/close(); the pipeline closes only AutoCloseable.
private class Diarization(private val e: SherpaOnnxDiarizationEngine) : DiarizationEngine by e, AutoCloseable {
    override fun close() = e.release()
}

private class Asr(private val e: WhisperAsrEngine) : AsrEngine by e, AutoCloseable {
    override fun close() = e.release()
}

private class Postprocessor(private val generator: LlamaCppTextGenerator) :
    TranscriptPostprocessor by LocalTranscriptPostprocessor(generator), AutoCloseable {
    // close() waits for a cancelled native call before freeing the model, so it must block here.
    override fun close() = runBlocking { generator.close() }
}
