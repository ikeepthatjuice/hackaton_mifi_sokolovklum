package app.frontdebug

import core.contracts.TranscriptResult
import core.contracts.Utterance
import feature.transcript.TranscriptPlayback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** UI-only fixture: does not open audio or play sound. Never packaged in release. */
class FakeTranscriptPlayback(@Suppress("UNUSED_PARAMETER") result: TranscriptResult) : TranscriptPlayback {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutable = MutableStateFlow<String?>(null)
    override val playingId = mutable.asStateFlow()
    private var job: Job? = null
    override fun play(utterance: Utterance) {
        job?.cancel()
        mutable.value = utterance.id
        job = scope.launch {
            delay((utterance.endMs - utterance.startMs).coerceIn(1_000, 10_000))
            mutable.value = null
        }
    }
    override fun stop() { job?.cancel(); mutable.value = null }
    override fun close() { stop(); scope.cancel() }
}
