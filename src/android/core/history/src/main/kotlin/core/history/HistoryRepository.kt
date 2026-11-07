package core.history

import core.contracts.TranscriptResult
import core.contracts.Utterance
import core.export.TranscriptJson
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

data class HistoryMeta(
    val id: String, // = TranscriptResult.jobId
    val sourceName: String, // source file name shown in the list
    val createdAt: Long, // epoch ms
    val durationMs: Long,
    val warnings: List<String>,
)

/** After [HistoryRepository.load] rawText == displayText == stored text: rawText is not kept, as in export. */
data class HistoryEntry(val meta: HistoryMeta, val result: TranscriptResult)

/**
 * Finished transcripts as `dir/<id>.json` = {"meta":{…},"transcript":[…]}; the transcript part is
 * the export format (TranscriptJson). Writes are atomic (tmp → rename); broken files are skipped.
 */
class HistoryRepository(
    private val dir: File,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /** Throws IllegalArgumentException for an id unfit as a file name, jobId != meta.id, or a result export would reject. */
    suspend fun save(result: TranscriptResult, meta: HistoryMeta): Unit = withContext(dispatcher) {
        require(result.jobId == meta.id) { "result.jobId != meta.id" }
        val file = fileOf(meta.id)
        val transcript = TranscriptJson.encode(result) // {"transcript":[...]}, validates rows
        check(transcript.startsWith("{\"transcript\":")) { "unexpected export shape" }
        val json = "{\"meta\":" + encodeMeta(meta) + "," + transcript.substring(1)
        dir.mkdirs()
        val tmp = File(dir, "${meta.id}.json.tmp")
        tmp.writeText(json, Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw IOException("cannot move ${tmp.name} to ${file.name}")
        }
    }

    /** Newest first. Broken, foreign or renamed files (meta.id != file name) are skipped, never thrown. */
    suspend fun list(): List<HistoryMeta> = withContext(dispatcher) {
        dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }.orEmpty()
            .mapNotNull { f -> runCatching { decode(f.readText(Charsets.UTF_8), f.name.removeSuffix(".json")).meta }.getOrNull() }
            .sortedByDescending { it.createdAt }
    }

    /** Null if there is no such entry or its file is broken. */
    suspend fun load(id: String): HistoryEntry? = withContext(dispatcher) {
        if (!ID.matches(id)) return@withContext null
        val file = fileOf(id)
        if (!file.isFile) return@withContext null
        runCatching { decode(file.readText(Charsets.UTF_8), id) }.getOrNull()
    }

    suspend fun delete(id: String): Boolean = withContext(dispatcher) {
        ID.matches(id) && fileOf(id).delete()
    }

    private fun fileOf(id: String): File {
        require(ID.matches(id)) { "invalid history id" }
        return File(dir, "$id.json")
    }

    private fun encodeMeta(m: HistoryMeta) = buildString {
        append("{\"id\":").append(Json.quote(m.id))
        append(",\"sourceName\":").append(Json.quote(m.sourceName))
        append(",\"createdAt\":").append(m.createdAt)
        append(",\"durationMs\":").append(m.durationMs)
        append(",\"warnings\":[").append(m.warnings.joinToString(",") { Json.quote(it) }).append("]}")
    }

    @Suppress("UNCHECKED_CAST")
    /** Throws unless meta.id == [fileId] and every row passes the export rules (speaker_N, 0 <= start < end <= duration). */
    private fun decode(text: String, fileId: String): HistoryEntry {
        val root = Json.parse(text) as Map<String, Any?>
        val m = root["meta"] as Map<String, Any?>
        val meta = HistoryMeta(
            id = (m["id"] as String).also { require(it == fileId) { "meta.id != file name" } },
            sourceName = m["sourceName"] as String,
            createdAt = m["createdAt"] as Long,
            durationMs = m["durationMs"] as Long,
            warnings = (m["warnings"] as List<Any?>).map { it as String },
        )
        val rows = root["transcript"] as List<Map<String, Any?>>
        val utterances = rows.mapIndexed { i, r ->
            val text = r["text"] as String
            Utterance("u$i", r["speaker_id"] as String, r["start_ms"] as Long, r["end_ms"] as Long, text, text)
        }
        val result = TranscriptResult(meta.id, meta.durationMs, utterances, meta.warnings)
        TranscriptJson.encode(result) // same validation as export; throws on a bad row
        return HistoryEntry(meta, result)
    }

    private companion object {
        // id becomes a file name: same rule as job ids, no path escape.
        val ID = Regex("[A-Za-z0-9_-]{1,64}")
    }
}

/** Minimal JSON for this module's own files (org.json is a stub in JVM unit tests; no new dependencies). */
internal object Json {
    fun quote(s: String) = buildString {
        append('"')
        for (c in s) when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c == '\n' -> append("\\n")
            c == '\r' -> append("\\r")
            c == '\t' -> append("\\t")
            c < ' ' -> append("\\u%04x".format(c.code))
            else -> append(c)
        }
        append('"')
    }

    /** Objects → Map, arrays → List, integers → Long, other numbers → Double. Throws on malformed input. */
    fun parse(text: String): Any? {
        val p = Parser(text)
        val v = p.value()
        p.skipWs()
        require(p.pos == text.length) { "trailing data" }
        return v
    }

    private class Parser(val s: String) {
        var pos = 0

        fun skipWs() { while (pos < s.length && s[pos].isWhitespace()) pos++ }

        fun value(): Any? {
            skipWs()
            require(pos < s.length) { "unexpected end" }
            return when (val c = s[pos]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> word("true", true)
                'f' -> word("false", false)
                'n' -> word("null", null)
                else -> if (c == '-' || c.isDigit()) num() else throw IllegalArgumentException("unexpected '$c' at $pos")
            }
        }

        private fun obj(): Map<String, Any?> {
            pos++
            val map = LinkedHashMap<String, Any?>()
            skipWs()
            if (peek() == '}') { pos++; return map }
            while (true) {
                skipWs()
                val key = str()
                skipWs(); expect(':')
                map[key] = value()
                skipWs()
                if (peek() == ',') { pos++; continue }
                expect('}'); return map
            }
        }

        private fun arr(): List<Any?> {
            pos++
            val list = ArrayList<Any?>()
            skipWs()
            if (peek() == ']') { pos++; return list }
            while (true) {
                list.add(value())
                skipWs()
                if (peek() == ',') { pos++; continue }
                expect(']'); return list
            }
        }

        private fun str(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                require(pos < s.length) { "unterminated string" }
                when (val c = s[pos++]) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        require(pos < s.length) { "bad escape" }
                        when (val e = s[pos++]) {
                            '"', '\\', '/' -> sb.append(e)
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                require(pos + 4 <= s.length) { "bad \\u escape" }
                                sb.append(s.substring(pos, pos + 4).toInt(16).toChar()); pos += 4
                            }
                            else -> throw IllegalArgumentException("bad escape \\$e")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun num(): Any {
            val start = pos
            if (peek() == '-') pos++
            while (pos < s.length && (s[pos].isDigit() || s[pos] in ".eE+-")) pos++
            val t = s.substring(start, pos)
            return t.toLongOrNull() ?: t.toDouble()
        }

        private fun word(w: String, v: Any?): Any? {
            require(s.startsWith(w, pos)) { "unexpected token at $pos" }
            pos += w.length
            return v
        }

        private fun peek() = if (pos < s.length) s[pos] else '\u0000'
        private fun expect(c: Char) { require(peek() == c) { "expected '$c' at $pos" }; pos++ }
    }
}
