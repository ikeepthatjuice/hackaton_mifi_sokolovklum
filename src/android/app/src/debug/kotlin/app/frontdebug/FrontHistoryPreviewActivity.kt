package app.frontdebug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import core.contracts.TranscriptExporter
import core.contracts.TranscriptResult
import feature.importaudio.ImportScreen
import feature.transcript.TranscriptHistoryHost

class FrontHistoryPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val history = remember { FakeTranscriptHistory(intent.getBooleanExtra("empty", false)) }
            val exporter = remember { object : TranscriptExporter {
                override suspend fun export(result: TranscriptResult, destinationUri: String) = Result.success(Unit)
            } }
            MaterialTheme {
                Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
                    TranscriptHistoryHost(history, exporter) { openHistory ->
                        ImportScreen(null, {}, {}, onHistory = openHistory)
                    }
                }
            }
        }
    }
}
