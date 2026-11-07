package core.export

import core.contracts.TranscriptResult
import core.contracts.Utterance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

// Synthetic text only.
class TranscriptJsonTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun u(speaker: String, start: Long, end: Long, text: String) =
        Utterance("u$start", speaker, start, end, rawText = "raw", displayText = text)

    private fun result(vararg us: Utterance, durationMs: Long = 5000) = TranscriptResult("job", durationMs, us.toList())

    @Test
    fun exactCaseFormatWithDisplayTextAndOrder() {
        val json = TranscriptJson.encode(result(u("speaker_2", 2000, 3000, "Второй."), u("speaker_1", 0, 1500, " Первый. ")))
        assertEquals(
            """{"transcript":[{"speaker_id":"speaker_1","start_ms":0,"end_ms":1500,"text":"Первый."},""" +
                """{"speaker_id":"speaker_2","start_ms":2000,"end_ms":3000,"text":"Второй."}]}""",
            json,
        )
    }

    @Test
    fun emptyResultAndBlankTextGiveEmptyTranscript() {
        assertEquals("""{"transcript":[]}""", TranscriptJson.encode(result(durationMs = 0)))
        assertEquals("""{"transcript":[]}""", TranscriptJson.encode(result(u("speaker_1", 0, 10, "  "))))
    }

    @Test
    fun specialCharsAreEscaped() {
        val json = TranscriptJson.encode(result(u("speaker_1", 0, 10, "a\"b\\c\nd\u0001e\uD800f")))
        assertEquals("""{"transcript":[{"speaker_id":"speaker_1","start_ms":0,"end_ms":10,"text":"a\"b\\c\nd\u0001e\ud800f"}]}""", json)
    }

    @Test
    fun invalidRowsAreRejected() {
        val bad = listOf(
            u("speaker_1", -1, 10, "x"),
            u("speaker_1", 10, 10, "x"),
            u("speaker_1", 20, 10, "x"),
            u("speaker_1", 0, 5001, "x"), // past durationMs
            u("speaker_0", 0, 10, "x"),
            u("SPEAKER_00", 0, 10, "x"),
        )
        for (row in bad) assertThrows(row.toString(), IllegalArgumentException::class.java) { TranscriptJson.encode(result(row)) }
    }

    /** Runs the team's offline checker (quality/check_export.py) on our output; skipped without python3. */
    @Test
    fun outputPassesQualityChecker() {
        val checker = File("../../../../quality/check_export.py").canonicalFile
        assumeTrue("checker not found", checker.isFile)
        val cases = listOf(
            result(u("speaker_1", 100, 1100, "Проверим \"кавычки\"\nи перенос."), u("speaker_2", 1400, 2300, "Да 😀.")),
            result(durationMs = 0),
        )
        for (r in cases) {
            val file = tmp.newFile().apply { writeText(TranscriptJson.encode(r)) }
            val proc = runCatching {
                ProcessBuilder("python3", "-I", checker.path, file.path, "--duration-ms", r.durationMs.toString())
                    .redirectErrorStream(true).start()
            }.getOrNull()
            assumeTrue("python3 not available", proc != null)
            assumeTrue(proc!!.waitFor(30, TimeUnit.SECONDS))
            val out = proc.inputStream.bufferedReader().readText()
            assertEquals(out, 0, proc.exitValue())
        }
    }
}
