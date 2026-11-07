package app.frontdebug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import core.contracts.TranscriptExporter
import core.contracts.TranscriptResult
import core.contracts.Utterance
import feature.transcript.TranscriptExportRoute

/** Synthetic editor UI host. No model, storage or JSON-writing claim. */
class FrontEditsPreviewActivity : ComponentActivity() {
    val editor by lazy { FakeTranscriptEditor(sample) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
                    TranscriptExportRoute(sample, object : TranscriptExporter {
                        override suspend fun export(result: TranscriptResult, destinationUri: String) = Result.success(Unit)
                    }, {}, editorFactory = { editor })
                }
            }
        }
    }
    private companion object {
        val sample = TranscriptResult("synthetic-edits", 6000, listOf(
            Utterance("e0", "speaker_1", 0, 1000, "первая реплика", "Первая реплика."),
            Utterance("e1", "speaker_1", 1200, 2500, "вторая реплика", "Вторая реплика."),
            Utterance("e2", "speaker_2", 3000, 4500, "третья реплика", "Третья реплика."),
        ))
    }
}
