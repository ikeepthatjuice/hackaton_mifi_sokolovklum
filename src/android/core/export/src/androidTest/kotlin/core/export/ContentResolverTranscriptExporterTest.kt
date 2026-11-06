package core.export

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import core.contracts.PipelineError
import core.contracts.TranscriptResult
import core.contracts.Utterance
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

// Real ContentResolver on a file:// destination; synthetic text only.
@RunWith(AndroidJUnit4::class)
class ContentResolverTranscriptExporterTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val exporter = ContentResolverTranscriptExporter(context.contentResolver)

    private fun result(text: String, endMs: Long = 1000) =
        TranscriptResult("job", 2000, listOf(Utterance("u0", "speaker_1", 0, endMs, "raw", text)))

    @Test
    fun writesJsonAndTruncatesOnOverwrite() = runBlocking {
        val file = File(context.cacheDir, "export-test.json").apply { delete() }
        val uri = Uri.fromFile(file).toString()

        assertTrue(exporter.export(result("Длинный вымышленный текст для проверки."), uri).isSuccess)
        assertTrue(exporter.export(result("Коротко."), uri).isSuccess)

        assertEquals(
            """{"transcript":[{"speaker_id":"speaker_1","start_ms":0,"end_ms":1000,"text":"Коротко."}]}""",
            file.readText(),
        )
        file.delete()
    }

    @Test
    fun invalidDataOrDestinationIsExportFailed() = runBlocking {
        val invalid = exporter.export(result("x", endMs = 5000), Uri.fromFile(File(context.cacheDir, "x.json")).toString())
        val badUri = exporter.export(result("x"), "content://no.such.provider/doc")
        for (r in listOf(invalid, badUri)) {
            val e = r.exceptionOrNull() as TranscriptExportException
            assertEquals(PipelineError.EXPORT_FAILED, e.error)
        }
    }
}
