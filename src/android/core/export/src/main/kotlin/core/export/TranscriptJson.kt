package core.export

import core.contracts.TranscriptResult

/**
 * Builds the export JSON required by the case: {"transcript":[{speaker_id,start_ms,end_ms,text}]}.
 * Exactly these four fields, built by code (never by the LLM); text = displayText.
 * - Utterances with blank displayText are dropped: the format requires non-empty text.
 * - Rows are ordered by start_ms (stable).
 * - Any row breaking 0 <= start_ms < end_ms <= durationMs or the speaker_N format throws
 *   IllegalArgumentException: a broken file must not be written.
 */
object TranscriptJson {

    private val SPEAKER = Regex("speaker_[1-9][0-9]*")

    fun encode(result: TranscriptResult): String {
        val rows = result.utterances.filter { it.displayText.isNotBlank() }.sortedBy { it.startMs }
        return buildString {
            append("{\"transcript\":[")
            rows.forEachIndexed { i, u ->
                require(SPEAKER.matches(u.speakerId)) { "row $i: invalid speaker_id" }
                require(u.startMs in 0 until u.endMs && u.endMs <= result.durationMs) {
                    "row $i: interval [${u.startMs}, ${u.endMs}) outside [0, ${result.durationMs}]"
                }
                if (i > 0) append(',')
                append("{\"speaker_id\":\"").append(u.speakerId)
                append("\",\"start_ms\":").append(u.startMs)
                append(",\"end_ms\":").append(u.endMs)
                append(",\"text\":")
                appendJsonString(u.displayText.trim())
                append('}')
            }
            append("]}")
        }
    }

    // Non-ASCII stays as UTF-8; only quotes, backslash, control chars and lone surrogates are escaped.
    private fun StringBuilder.appendJsonString(s: String) {
        append('"')
        for ((i, c) in s.withIndex()) {
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                c < ' ' || isLoneSurrogate(s, i) -> append("\\u%04x".format(c.code))
                else -> append(c)
            }
        }
        append('"')
    }

    // A lone surrogate would turn into '?' when encoded to UTF-8, silently corrupting text.
    private fun isLoneSurrogate(s: String, i: Int): Boolean {
        val c = s[i]
        return when {
            c.isHighSurrogate() -> i + 1 >= s.length || !s[i + 1].isLowSurrogate()
            c.isLowSurrogate() -> i == 0 || !s[i - 1].isHighSurrogate()
            else -> false
        }
    }
}
