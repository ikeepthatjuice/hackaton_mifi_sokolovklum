package core.pipeline

import core.contracts.AsrEngine
import core.contracts.DiarizationEngine
import core.contracts.PipelineError
import core.contracts.TranscriptAligner
import core.contracts.TranscriptPostprocessor
import java.io.File

/**
 * How to get an engine for one stage. The pipeline resolves [models] through [ModelStore],
 * calls [load] right before the stage and closes the engine right after it
 * (if it implements AutoCloseable), so only one model is in memory at a time.
 */
class EngineSpec<T>(val models: List<String>, val load: suspend (modelFiles: List<File>) -> T)

/** Engines in call order: diarization (sherpa) → ASR (whisper) → alignment → postprocess (LLM). */
class PipelineEngines(
    val diarization: EngineSpec<DiarizationEngine>,
    val asr: EngineSpec<AsrEngine>,
    val aligner: TranscriptAligner,
    val postprocessor: EngineSpec<TranscriptPostprocessor>,
)

/** A failure with a known [PipelineError]; the pipeline turns it into PipelineState.Failed. */
class PipelineFailure(
    val error: PipelineError,
    val requiredBytes: Long? = null,
    cause: Throwable? = null,
    detail: String? = null, // for logs only, never shown in UI
) : Exception(if (detail == null) error.name else "${error.name}: $detail", cause)
