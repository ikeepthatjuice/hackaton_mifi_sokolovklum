package app.frontdebug

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import core.contracts.TranscriptExporter
import core.contracts.TranscriptResult
import core.contracts.Utterance
import feature.transcript.TranscriptExportRoute
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.net.Uri
import java.io.IOException

@Composable
fun FrontExportDemo(onReturn: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val exporter = remember(context) { SyntheticJsonExporter(context) }
    val result = remember {
        TranscriptResult(
            "synthetic-export-demo", 2000,
            listOf(Utterance("synthetic-1", "speaker_1", 100, 1500, "это синтетический экспорт", "Это синтетический экспорт.")),
        )
    }
    Column {
        Text("DEBUG · Экспорт только фиксированного синтетического JSON.", modifier = Modifier.padding(16.dp))
        TranscriptExportRoute(result, exporter, onReturn)
    }
}

/** The production exporter belongs to back. This fixed fixture cannot serialize real results. */
private class SyntheticJsonExporter(private val context: Context) : TranscriptExporter {
    override suspend fun export(result: TranscriptResult, destinationUri: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                require(result.jobId == "synthetic-export-demo")
                val json = """{"transcript":[{"speaker_id":"speaker_1","start_ms":100,"end_ms":1500,"text":"Это синтетический экспорт."}]}"""
                val stream = context.contentResolver.openOutputStream(Uri.parse(destinationUri), "wt")
                    ?: throw IOException("No destination stream")
                stream.bufferedWriter(Charsets.UTF_8).use { it.write(json) }
                Result.success(Unit)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Result.failure(failure)
            }
        }
}
