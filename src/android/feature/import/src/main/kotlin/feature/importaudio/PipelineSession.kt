package feature.importaudio

import core.contracts.PipelineError
import core.contracts.PipelineState
import core.contracts.TranscriptionPipeline
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class PipelineUiState(
    val jobId: String? = null,
    val audioUri: String? = null,
    val state: PipelineState = PipelineState.Idle,
    val starting: Boolean = false,
    val cancellationRequested: Boolean = false,
    val actionError: String? = null,
)

/** Owned by a ViewModel. Public actions and state updates run on its UI dispatcher. */
internal class PipelineSession(
    private val pipeline: TranscriptionPipeline,
    private val scope: CoroutineScope,
    private val startDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutableState = MutableStateFlow(PipelineUiState())
    val state = mutableState.asStateFlow()
    private var generation = 0L
    private var observer: Job? = null
    private var closed = false

    suspend fun start(audioUri: String): String? {
        if (closed) return null
        val current = state.value
        if (current.starting) return null
        if (current.jobId != null && !current.state.isTerminal()) {
            return current.jobId.takeIf { current.audioUri == audioUri }
        }
        observer?.cancel()
        val token = ++generation
        mutableState.value = PipelineUiState(audioUri = audioUri, starting = true)
        var createdJob: String? = null
        try {
            val id = withContext(startDispatcher) {
                pipeline.start(audioUri).also {
                    createdJob = it
                    // A blocking implementation may finish after its owner was destroyed.
                    if (!scope.isActive) runCatching { pipeline.cancel(it) }
                }
            }
            if (token != generation || closed) {
                runCatching { pipeline.cancel(id) }
                return null
            }
            val cancelRequested = state.value.cancellationRequested
            attach(id, audioUri, token, cancelRequested)
            if (cancelRequested) sendCancel(id)
            return id
        } catch (cancelled: CancellationException) {
            createdJob?.let { runCatching { pipeline.cancel(it) } }
            throw cancelled
        } catch (_: Exception) {
            if (token == generation && !closed) {
                mutableState.value = PipelineUiState(
                    audioUri = audioUri,
                    state = PipelineState.Failed(PipelineError.UNKNOWN),
                    actionError = "Не удалось начать обработку. Повторите попытку.",
                )
            }
            return null
        }
    }

    /** Reconnects to an existing job; never invokes start. Also used after a saved job ID is restored. */
    fun attach(jobId: String, audioUri: String? = null) {
        if (closed || state.value.jobId == jobId) return
        attach(jobId, audioUri, ++generation, false)
    }

    private fun attach(id: String, uri: String?, token: Long, cancelRequested: Boolean) {
        observer?.cancel()
        mutableState.value = PipelineUiState(jobId = id, audioUri = uri, cancellationRequested = cancelRequested)
        observer = scope.launch {
            try {
                pipeline.observe(id).collect { value ->
                    if (token == generation && state.value.jobId == id && !state.value.state.isTerminal()) {
                        mutableState.value = state.value.copy(
                            jobId = if (value.isTerminal()) null else id,
                            state = value,
                            cancellationRequested = state.value.cancellationRequested && !value.isTerminal(),
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (token == generation && state.value.jobId == id && !state.value.state.isTerminal()) {
                    mutableState.value = state.value.copy(
                        jobId = null,
                        state = PipelineState.Failed(PipelineError.UNKNOWN),
                        cancellationRequested = false,
                    )
                }
            }
        }
    }

    fun cancel() {
        val current = state.value
        if (closed || current.cancellationRequested || current.state.isTerminal()) return
        if (current.jobId == null && !current.starting) return
        mutableState.value = current.copy(cancellationRequested = true, actionError = null)
        current.jobId?.let(::sendCancel)
    }

    private fun sendCancel(id: String) {
        try {
            pipeline.cancel(id)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            mutableState.value = state.value.copy(
                cancellationRequested = false,
                actionError = "Не удалось отменить обработку. Попробуйте ещё раз.",
            )
        }
    }

    fun reset() {
        if (state.value.starting || (state.value.jobId != null && !state.value.state.isTerminal())) return
        ++generation
        observer?.cancel()
        mutableState.value = PipelineUiState()
    }

    fun close() {
        closed = true
        ++generation
        observer?.cancel()
        state.value.jobId?.takeIf { !state.value.state.isTerminal() }?.let {
            runCatching { pipeline.cancel(it) }
        }
    }
}

internal fun PipelineState.isTerminal() =
    this is PipelineState.Completed || this is PipelineState.Failed || this == PipelineState.Cancelled
