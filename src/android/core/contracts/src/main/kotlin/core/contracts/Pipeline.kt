package core.contracts

import kotlinx.coroutines.flow.StateFlow

/** Pipeline stages in execution order (PROJECT_PLAN §5). */
enum class Stage { IMPORT, DECODE, DIARIZATION, ASR, ALIGNMENT, POSTPROCESS, DONE }

/**
 * Typed failure reasons. UI maps them to messages; never parse error text.
 * Cancellation is not an error and is reported as [PipelineState.Cancelled].
 */
enum class PipelineError {
    UNSUPPORTED_FORMAT,
    DECODE_FAILED,
    MODEL_MISSING,
    MODEL_LOAD_FAILED,
    OUT_OF_MEMORY,
    ENGINE_FAILED,
    EXPORT_FAILED,
    UNKNOWN,
}

sealed interface PipelineState {
    data object Idle : PipelineState

    /** [fraction] is progress inside [stage] in [0, 1], or null when the stage cannot report it. */
    data class Running(val stage: Stage, val fraction: Float?) : PipelineState {
        init {
            require(fraction == null || fraction in 0f..1f) { "fraction must be in [0, 1], was $fraction" }
        }
    }

    data class Completed(val result: TranscriptResult) : PipelineState
    data class Failed(val error: PipelineError) : PipelineState
    data object Cancelled : PipelineState
}

/**
 * Entry point for UI. Audio and destinations are passed as URI strings so this module
 * stays pure Kotlin/JVM without android.*; core/audio resolves them to local PCM.
 *
 * One job runs at a time; the pipeline owns model load/release and runs off the UI thread.
 */
interface TranscriptionPipeline {
    /** Starts processing and returns the jobId. */
    fun start(audioUri: String): String

    /** State of [jobId]; ends in Completed, Failed or Cancelled. */
    fun observe(jobId: String): StateFlow<PipelineState>

    /** Cancels [jobId]; the job ends in [PipelineState.Cancelled]. No-op for finished jobs. */
    fun cancel(jobId: String)
}

/**
 * Writes the mandatory export JSON ({"transcript":[{speaker_id,start_ms,end_ms,text}]}),
 * built by code, never by the LLM. Failure is returned as Result; cancellation is rethrown.
 */
interface TranscriptExporter {
    suspend fun export(result: TranscriptResult, destinationUri: String): Result<Unit>
}
