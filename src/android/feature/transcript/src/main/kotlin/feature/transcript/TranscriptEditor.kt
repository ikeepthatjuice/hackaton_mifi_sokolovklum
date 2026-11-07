package feature.transcript

import core.contracts.TranscriptResult
import kotlinx.coroutines.flow.StateFlow

/** UI adapter boundary; core/edit owns all mutations and persistence. */
data class TranscriptEditUiState(
    val result: TranscriptResult,
    val speakerNames: Map<String, String> = emptyMap(),
    val canUndo: Boolean = false,
)

interface TranscriptEditor : AutoCloseable {
    val state: StateFlow<TranscriptEditUiState>
    suspend fun renameSpeaker(speakerId: String, name: String)
    suspend fun editText(utteranceId: String, text: String)
    suspend fun changeSpeaker(utteranceId: String, speakerId: String)
    suspend fun mergePrevious(utteranceId: String)
    suspend fun undo()
    override fun close() {}
}
