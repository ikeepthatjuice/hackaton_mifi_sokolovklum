package core.pipeline

import core.audio.AudioPreparationException
import core.contracts.AudioInput
import core.contracts.PipelineError
import core.contracts.PipelineState
import core.contracts.Stage
import core.contracts.TranscriptResult
import core.contracts.TranscriptionPipeline
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Runs one transcription job at a time on [dispatcher]:
 * DECODE (audio → PCM) → DIARIZATION → ASR → ALIGNMENT → POSTPROCESS → Completed.
 *
 * - A new [start] cancels the running job and waits until it is Cancelled.
 * - Each model is loaded right before its stage and released right after it.
 * - Errors become Failed(PipelineError); error text never reaches UI. Cancellation is not an error.
 * - `jobsDir/<jobId>/` is deleted after Completed, Failed or Cancelled.
 *
 * @param prepareAudio usually `AudioPreparer::prepare`; writes PCM under `jobsDir/<jobId>/`.
 */
class DefaultTranscriptionPipeline(
    private val prepareAudio: suspend (audioUri: String, jobId: String) -> AudioInput,
    private val jobsDir: File,
    private val engines: PipelineEngines,
    private val modelStore: ModelStore,
    dispatcher: CoroutineDispatcher,
) : TranscriptionPipeline {

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    // shortcut: states of finished jobs stay in memory for the process lifetime; fine for a few jobs per session.
    private val states = ConcurrentHashMap<String, MutableStateFlow<PipelineState>>()
    private val jobs = ConcurrentHashMap<String, Job>()
    private val lock = Any()
    private var current: Job? = null

    override fun start(audioUri: String): String {
        val jobId = UUID.randomUUID().toString()
        val state = MutableStateFlow<PipelineState>(PipelineState.Running(Stage.IMPORT, null))
        states[jobId] = state
        synchronized(lock) {
            val previous = current
            val job = scope.launch {
                previous?.cancelAndJoin()
                run(jobId, audioUri, state)
            }
            // Also covers a job cancelled before its body started (while waiting for the previous one).
            job.invokeOnCompletion { cause ->
                jobs.remove(jobId)
                if (cause is CancellationException) {
                    deleteJobDir(jobId)
                    state.value = PipelineState.Cancelled
                }
            }
            jobs[jobId] = job
            current = job
        }
        return jobId
    }

    override fun observe(jobId: String): StateFlow<PipelineState> =
        states[jobId]?.asStateFlow() ?: MutableStateFlow<PipelineState>(PipelineState.Idle).asStateFlow()

    override fun cancel(jobId: String) {
        jobs[jobId]?.cancel()
    }

    private suspend fun run(jobId: String, audioUri: String, state: MutableStateFlow<PipelineState>) {
        try {
            state.value = PipelineState.Running(Stage.DECODE, null)
            val audio = prepareAudio(audioUri, jobId)

            state.value = PipelineState.Running(Stage.DIARIZATION, null)
            val turns = stage(engines.diarization) { it.diarize(audio) }

            state.value = PipelineState.Running(Stage.ASR, null)
            val words = stage(engines.asr) { it.transcribe(audio) }

            state.value = PipelineState.Running(Stage.ALIGNMENT, null)
            val utterances = engineCall { engines.aligner.align(words, turns) }

            state.value = PipelineState.Running(Stage.POSTPROCESS, null)
            val processed = stage(engines.postprocessor) { it.process(utterances) }

            deleteJobDir(jobId)
            state.value = PipelineState.Completed(TranscriptResult(jobId, audio.durationMs, processed))
        } catch (e: CancellationException) {
            throw e // invokeOnCompletion sets Cancelled and cleans up
        } catch (e: Throwable) {
            deleteJobDir(jobId)
            state.value = toFailed(e)
        }
    }

    /** Resolve models, load the engine, run [block], always release the engine. */
    private suspend fun <T, R> stage(spec: EngineSpec<T>, block: suspend (T) -> R): R {
        val files = spec.models.map { modelStore.ensureModel(it) }
        val engine = try {
            spec.load(files)
        } catch (e: CancellationException) {
            throw e
        } catch (e: OutOfMemoryError) {
            throw PipelineFailure(PipelineError.OUT_OF_MEMORY, cause = e)
        } catch (e: PipelineFailure) {
            throw e
        } catch (e: Exception) {
            throw PipelineFailure(PipelineError.MODEL_LOAD_FAILED, cause = e)
        }
        try {
            return engineCall { block(engine) }
        } finally {
            (engine as? AutoCloseable)?.runCatching { close() }
        }
    }

    /** Anything an engine throws, except cancellation and known failures, is ENGINE_FAILED. */
    private inline fun <R> engineCall(block: () -> R): R = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: OutOfMemoryError) {
        throw PipelineFailure(PipelineError.OUT_OF_MEMORY, cause = e)
    } catch (e: PipelineFailure) {
        throw e
    } catch (e: Exception) {
        throw PipelineFailure(PipelineError.ENGINE_FAILED, cause = e)
    }

    private fun toFailed(e: Throwable): PipelineState.Failed = when (e) {
        is PipelineFailure -> PipelineState.Failed(e.error, e.requiredBytes)
        is AudioPreparationException -> PipelineState.Failed(e.error, e.requiredBytes)
        is OutOfMemoryError -> PipelineState.Failed(PipelineError.OUT_OF_MEMORY)
        else -> PipelineState.Failed(PipelineError.UNKNOWN)
    }

    private fun deleteJobDir(jobId: String) {
        File(jobsDir, jobId).deleteRecursively()
    }
}
