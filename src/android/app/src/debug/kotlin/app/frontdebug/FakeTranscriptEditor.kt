package app.frontdebug

import core.contracts.TranscriptResult
import feature.transcript.TranscriptEditor
import feature.transcript.TranscriptEditUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Debug-only UI fixture until back provides core/edit. Never linked into release. */
class FakeTranscriptEditor(result: TranscriptResult) : TranscriptEditor {
    private val mutable = MutableStateFlow(TranscriptEditUiState(result))
    override val state = mutable.asStateFlow()
    private var previous: TranscriptEditUiState? = null

    private fun update(block: (TranscriptEditUiState) -> TranscriptEditUiState) {
        val before = mutable.value
        val after = block(before)
        previous = before
        mutable.value = after.copy(canUndo = true)
    }
    override suspend fun renameSpeaker(speakerId: String, name: String) = update {
        require(name.isNotBlank())
        it.copy(speakerNames = it.speakerNames + (speakerId to name))
    }
    override suspend fun editText(utteranceId: String, text: String) = update { state ->
        require(text.isNotBlank())
        state.copy(result = state.result.copy(utterances = state.result.utterances.map {
            if (it.id == utteranceId) it.copy(displayText = text) else it
        }))
    }
    override suspend fun changeSpeaker(utteranceId: String, speakerId: String) = update { state ->
        state.copy(result = state.result.copy(utterances = state.result.utterances.map {
            if (it.id == utteranceId) it.copy(speakerId = speakerId) else it
        }))
    }
    override suspend fun mergePrevious(utteranceId: String) = update { state ->
        val rows = state.result.utterances.toMutableList()
        val i = rows.indexOfFirst { it.id == utteranceId }
        require(i > 0)
        val before = rows[i - 1]
        val current = rows.removeAt(i)
        rows[i - 1] = before.copy(
            endMs = maxOf(before.endMs, current.endMs),
            rawText = "${before.rawText} ${current.rawText}",
            displayText = "${before.displayText} ${current.displayText}",
        )
        state.copy(result = state.result.copy(utterances = rows))
    }
    override suspend fun undo() {
        previous?.let { mutable.value = it.copy(canUndo = false) }
        previous = null
    }
}
