package feature.transcript

import core.contracts.TranscriptResult

/** List presentation only. Back maps core.history metadata here and owns all file operations. */
data class HistoryListItem(val id: String, val title: String, val createdAtMs: Long, val durationMs: Long)

interface TranscriptHistoryStore {
    suspend fun list(): List<HistoryListItem>
    suspend fun load(id: String): TranscriptResult?
    suspend fun delete(id: String): Boolean
}
