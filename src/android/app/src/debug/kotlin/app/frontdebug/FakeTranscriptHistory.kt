package app.frontdebug

import core.contracts.TranscriptResult
import core.contracts.Utterance
import feature.transcript.HistoryListItem
import feature.transcript.TranscriptHistoryStore

/** In-memory synthetic fixture only. Real file persistence belongs to core/history. */
class FakeTranscriptHistory(empty: Boolean = false) : TranscriptHistoryStore {
    private val entries: MutableMap<String, Pair<HistoryListItem, TranscriptResult>> = if (empty) mutableMapOf() else linkedMapOf(
        "synthetic-old" to (HistoryListItem("synthetic-old", "Синтетика — старая.wav", 1_700_000_000_000, 2000) to sample("synthetic-old")),
        "synthetic-new" to (HistoryListItem("synthetic-new", "Синтетика — новая.wav", 1_700_100_000_000, 2000) to sample("synthetic-new")),
    )
    override suspend fun list() = entries.values.map { it.first }
    override suspend fun load(id: String): TranscriptResult? = entries[id]?.second
    override suspend fun delete(id: String): Boolean = entries.remove(id) != null

    private companion object {
        fun sample(id: String) = TranscriptResult(id, 2000, listOf(
            Utterance("h0", "speaker_1", 0, 1000, "исправленный исходный текст", "Исправленная синтетическая реплика."),
        ))
    }
}
