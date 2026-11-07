package core.history

import core.contracts.TranscriptResult
import core.contracts.Utterance
import core.export.TranscriptJson
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class HistoryRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()

    // Synthetic text only, never case data.
    private fun result(id: String, text: String = "Привет. Как дела?") = TranscriptResult(
        jobId = id,
        durationMs = 5000,
        utterances = listOf(
            Utterance("a", "speaker_1", 0, 2000, text.lowercase(), text),
            Utterance("b", "speaker_2", 2000, 4000, "ответ", "Ответ \"в кавычках\"\nи перенос."),
        ),
        warnings = listOf("Пунктуация обработана в 1 из 2 реплик"),
    )

    private fun meta(id: String, createdAt: Long, name: String = "запись №1.m4a") =
        HistoryMeta(id, name, createdAt, 5000, listOf("Пунктуация обработана в 1 из 2 реплик"))

    private fun TestScope.repo(dir: File = tmp.root) = HistoryRepository(dir, StandardTestDispatcher(testScheduler))

    @Test
    fun saveLoadRoundtripKeepsMetaAndTranscriptWithCyrillic() = runTest {
        val r = repo()
        r.save(result("job-1"), meta("job-1", 100))

        val entry = r.load("job-1")!!
        assertEquals(meta("job-1", 100), entry.meta)
        assertEquals("job-1", entry.result.jobId)
        assertEquals(5000L, entry.result.durationMs)
        assertEquals(listOf("Привет. Как дела?", "Ответ \"в кавычках\"\nи перенос."), entry.result.utterances.map { it.displayText })
        assertEquals(entry.result.utterances.map { it.displayText }, entry.result.utterances.map { it.rawText })
        assertEquals(listOf("speaker_1", "speaker_2"), entry.result.utterances.map { it.speakerId })
        assertEquals(listOf(0L to 2000L, 2000L to 4000L), entry.result.utterances.map { it.startMs to it.endMs })
        assertEquals(meta("job-1", 100).warnings, entry.result.warnings)
    }

    @Test
    fun transcriptPartIsExactlyTheExportFormat() = runTest {
        repo().save(result("job-1"), meta("job-1", 100))
        val file = File(tmp.root, "job-1.json").readText()
        assertTrue(file.endsWith(TranscriptJson.encode(result("job-1")).substring(1)))
        assertFalse(File(tmp.root, "job-1.json.tmp").exists())
    }

    @Test
    fun listIsNewestFirst() = runTest {
        val r = repo()
        r.save(result("old"), meta("old", 100))
        r.save(result("new"), meta("new", 300))
        r.save(result("mid"), meta("mid", 200))
        assertEquals(listOf("new", "mid", "old"), r.list().map { it.id })
    }

    @Test
    fun deleteRemovesEntry() = runTest {
        val r = repo()
        r.save(result("job-1"), meta("job-1", 100))
        assertTrue(r.delete("job-1"))
        assertNull(r.load("job-1"))
        assertEquals(emptyList<HistoryMeta>(), r.list())
        assertFalse(r.delete("job-1"))
    }

    @Test
    fun brokenAndForeignFilesAreSkipped() = runTest {
        val r = repo()
        r.save(result("good"), meta("good", 100))
        File(tmp.root, "broken.json").writeText("{\"meta\":{\"id\":\"broken\"") // truncated write
        File(tmp.root, "foreign.json").writeText("[1,2,3]")
        File(tmp.root, "notes.txt").writeText("not json")
        assertEquals(listOf("good"), r.list().map { it.id })
        assertNull(r.load("broken"))
    }

    @Test
    fun saveOverwritesSameIdAtomically() = runTest {
        val r = repo()
        r.save(result("job-1", "Первый."), meta("job-1", 100))
        r.save(result("job-1", "Второй."), meta("job-1", 200))
        assertEquals("Второй.", r.load("job-1")!!.result.utterances.first().displayText)
        assertEquals(listOf(200L), r.list().map { it.createdAt })
    }

    @Test
    fun idThatIsNotAFileNameIsRejected() = runTest {
        val r = repo()
        val bad = runCatching { r.save(result("../x"), meta("../x", 1)) }
        assertTrue(bad.exceptionOrNull() is IllegalArgumentException)
        assertNull(r.load("../x"))
        assertFalse(r.delete("../x"))
    }

    @Test
    fun fileWhoseMetaIdIsNotItsNameIsSkipped() = runTest {
        val r = repo()
        r.save(result("two"), meta("two", 100))
        File(tmp.root, "two.json").renameTo(File(tmp.root, "one.json")) // one.json says it is "two"
        assertEquals(emptyList<HistoryMeta>(), r.list())
        assertNull(r.load("one"))
        assertNull(r.load("two"))
    }

    @Test
    fun rowThatExportWouldRejectMakesTheEntryBroken() = runTest {
        val r = repo()
        r.save(result("job-1"), meta("job-1", 100))
        val f = File(tmp.root, "job-1.json")
        val text = f.readText()
        check("\"start_ms\":0,\"end_ms\":2000" in text)
        f.writeText(text.replace("\"start_ms\":0,\"end_ms\":2000", "\"start_ms\":3000,\"end_ms\":2000")) // start > end
        assertEquals(emptyList<HistoryMeta>(), r.list())
        assertNull(r.load("job-1"))
    }

    @Test
    fun saveRejectsJobIdThatIsNotMetaId() = runTest {
        val bad = runCatching { repo().save(result("job-1"), meta("job-2", 1)) }
        assertTrue(bad.exceptionOrNull() is IllegalArgumentException)
        assertFalse(File(tmp.root, "job-2.json").exists())
    }

    @Test
    fun missingDirectoryIsEmptyList() = runTest {
        assertEquals(emptyList<HistoryMeta>(), repo(File(tmp.root, "absent")).list())
    }
}
