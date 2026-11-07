package core.pipeline

import core.audio.AudioPreparationException
import core.contracts.AudioInput
import core.contracts.PipelineError
import core.contracts.PipelineState
import core.contracts.Stage
import core.contracts.TranscriptResult
import core.contracts.TranscriptionPipeline
import core.contracts.Utterance
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Runs one transcription job at a time on [dispatcher]:
 * DECODE (audio → PCM) → DIARIZATION → ASR → ALIGNMENT → POSTPROCESS → Completed.
 *
 * - A new [start] cancels the running job; the new job runs only after the old one fully unwound.
 * - Each model is loaded right before its stage and released right after it.
 * - Errors become Failed(PipelineError); error text never reaches UI. Cancellation is not an error.
 * - Completed/Failed/Cancelled are final: a late cancel never overwrites them.
 * - `jobsDir/<jobId>/` is deleted after Completed, Failed or Cancelled.
 *
 * @param prepareAudio usually `AudioPreparer::prepare`; writes PCM under `jobsDir/<jobId>/`.
 * @param awaitModels waits for the app-start model copy (assets → filesDir); the job stays in IMPORT meanwhile.
 */
class DefaultTranscriptionPipeline(
    private val prepareAudio: suspend (audioUri: String, jobId: String) -> AudioInput,
    private val jobsDir: File,
    private val engines: PipelineEngines,
    private val modelStore: ModelStore,
    dispatcher: CoroutineDispatcher,
    private val awaitModels: suspend () -> Unit = {},
) : TranscriptionPipeline {

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    // shortcut: states of finished jobs stay in memory for the process lifetime; fine for a few jobs per session.
    private val states = ConcurrentHashMap<String, MutableStateFlow<PipelineState>>()
    private val jobs = ConcurrentHashMap<String, Job>()
    private val lock = Any()
    private var current: Job? = null
    // Held for a whole run, so overlapping starts can never run two bodies (and two models) at once.
    private val runMutex = Mutex()

    override fun start(audioUri: String): String {
        val jobId = UUID.randomUUID().toString()
        val state = MutableStateFlow<PipelineState>(PipelineState.Running(Stage.IMPORT, null))
        states[jobId] = state
        // LAZY: register bookkeeping before the job can run or finish.
        val job = scope.launch(start = CoroutineStart.LAZY) {
            runMutex.withLock { run(jobId, audioUri, state) }
        }
        // Also covers a job cancelled before its body started (while waiting for the previous one).
        job.invokeOnCompletion { cause ->
            jobs.remove(jobId)
            if (cause is CancellationException) {
                deleteJobDir(jobId)
                state.update { if (it.isFinal()) it else PipelineState.Cancelled }
            }
        }
        jobs[jobId] = job
        val previous = synchronized(lock) { current.also { current = job } }
        previous?.cancel()
        job.start()
        return jobId
    }

    override fun observe(jobId: String): StateFlow<PipelineState> =
        states[jobId]?.asStateFlow() ?: MutableStateFlow<PipelineState>(PipelineState.Idle).asStateFlow()

    override fun cancel(jobId: String) {
        jobs[jobId]?.cancel()
    }

    private suspend fun run(jobId: String, audioUri: String, state: MutableStateFlow<PipelineState>) {
        try {
            awaitModels() // state is still Running(IMPORT)
            state.value = PipelineState.Running(Stage.DECODE, null)
            val audio = prepareAudio(audioUri, jobId)

            state.value = PipelineState.Running(Stage.DIARIZATION, null)
            val turns = stage(engines.diarization) { it.diarize(audio) }

            state.value = PipelineState.Running(Stage.ASR, null)
            val words = stage(engines.asr) { it.transcribe(audio) }

            state.value = PipelineState.Running(Stage.ALIGNMENT, null)
            val utterances = engineCall { engines.aligner.align(words, turns) }

            state.value = PipelineState.Running(Stage.POSTPROCESS, null)
            // Resolve/copy and EngineSpec.load finish BEFORE the generation budget starts.
            val budgetMs = (audio.durationMs.coerceAtLeast(0).toDouble() * 0.30).toLong().coerceAtLeast(1L)
            var warnings = emptyList<String>()
            val raw = { utterances.map { it.copy(displayText = it.rawText) } }
            val processed = try {
                // Budget = max(30% of audio, one portion): the first portion always finishes; later portions
                // run only while the budget lasts. Finished portions are kept, the rest stay raw.
                val done = stage(engines.postprocessor, suppressCloseErrors = false) { processor ->
                    val done = ArrayList<Utterance>(utterances.size)
                    val portions = utterances.chunked(POSTPROCESS_PORTION)
                    coroutineScope {
                        val budget = launch { delay(budgetMs) } // generation clock starts with the first portion
                        portions.firstOrNull()?.let { done += engineCall { processor.process(it) } }
                        val rest = launch { for (portion in portions.drop(1)) done += engineCall { processor.process(portion) } }
                        select<Unit> {
                            rest.onJoin {}
                            budget.onJoin { rest.cancelAndJoin() }
                        }
                        budget.cancel()
                    }
                    done
                }
                val total = utterances.size
                warnings = when (done.size) {
                    total -> emptyList()
                    0 -> listOf("Пунктуация и регистр не обработаны: исчерпан лимит времени LLM. Показан исходный текст.")
                    else -> listOf("Пунктуация и регистр обработаны в ${done.size} из $total реплик: исчерпан лимит времени LLM. Остальные — исходный текст.")
                }
                done + raw().drop(done.size)
            } catch (failure: Throwable) {
                // User/job cancellation always wins; an engine-internal cancellation is an LLM error.
                currentCoroutineContext().ensureActive()
                warnings = listOf("Пунктуация не обработана: ошибка локальной LLM")
                raw()
            }

            // A cancel requested during a non-suspending step still wins over the result.
            currentCoroutineContext().ensureActive()
            deleteJobDir(jobId)
            state.value = PipelineState.Completed(TranscriptResult(jobId, audio.durationMs, processed, warnings))
        } catch (e: CancellationException) {
            throw e // invokeOnCompletion sets Cancelled and cleans up
        } catch (e: Throwable) {
            // An engine that throws because it was cancelled is still a cancel, not a failure.
            currentCoroutineContext().ensureActive()
            deleteJobDir(jobId)
            state.value = toFailed(e)
        }
    }

    /** Resolve models, load the engine, run [block], always release the engine. */
    private suspend fun <T, R> stage(spec: EngineSpec<T>, suppressCloseErrors: Boolean = true, block: suspend (T) -> R): R {
        val files = spec.models.map { modelStore.ensureModel(it) }
        val engine = guarded(PipelineError.MODEL_LOAD_FAILED) { spec.load(files) }
        try {
            return engineCall { block(engine) }
        } finally {
            (engine as? AutoCloseable)?.let { owned ->
                if (suppressCloseErrors) runCatching { owned.close() } else owned.close()
            }
        }
    }

    /** Anything an engine throws, except our cancellation and known failures, is ENGINE_FAILED. */
    private suspend inline fun <R> engineCall(block: () -> R): R = guarded(PipelineError.ENGINE_FAILED, block)

    private suspend inline fun <R> guarded(error: PipelineError, block: () -> R): R = try {
        block()
    } catch (e: CancellationException) {
        // Ours → propagate. Engine-internal (e.g. its own withTimeout) while we are active → engine error.
        if (!currentCoroutineContext().isActive) throw e
        throw PipelineFailure(error, cause = e)
    } catch (e: OutOfMemoryError) {
        throw PipelineFailure(PipelineError.OUT_OF_MEMORY, cause = e)
    } catch (e: PipelineFailure) {
        throw e
    } catch (e: Exception) {
        throw PipelineFailure(error, cause = e)
    }

    private fun toFailed(e: Throwable): PipelineState.Failed = when (e) {
        is PipelineFailure -> PipelineState.Failed(e.error, e.requiredBytes)
        is AudioPreparationException -> PipelineState.Failed(e.error, e.requiredBytes)
        is OutOfMemoryError -> PipelineState.Failed(PipelineError.OUT_OF_MEMORY)
        else -> PipelineState.Failed(PipelineError.UNKNOWN)
    }

    private fun PipelineState.isFinal() =
        this is PipelineState.Completed || this is PipelineState.Failed || this is PipelineState.Cancelled

    private companion object {
        /** Utterances per LLM call: small enough to keep progress on timeout, large enough for batching. */
        const val POSTPROCESS_PORTION = 4
    }

    private fun deleteJobDir(jobId: String) {
        File(jobsDir, jobId).deleteRecursively()
    }
}
