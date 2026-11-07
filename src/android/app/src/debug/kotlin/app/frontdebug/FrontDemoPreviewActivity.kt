package app.frontdebug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import core.contracts.TranscriptResult
import core.contracts.Utterance
import feature.importaudio.LlmProgress
import feature.importaudio.ProcessingScreen
import feature.transcript.TranscriptScreen
import feature.transcript.TranscriptTextActions

class FrontDemoPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
                    if (intent.getBooleanExtra("progress", false)) {
                        ProcessingScreen("Синтетическое аудио", "Обработка текста", {}, llmProgress = LlmProgress(2, 5))
                    } else {
                        val player = remember { FakeTranscriptPlayback(sample) }
                        val playing by player.playingId.collectAsState()
                        DisposableEffect(player) { onDispose { player.close() } }
                        TranscriptScreen(sample, {}, {},
                            headerActions = { TranscriptTextActions(sample, mapOf("speaker_1" to "Анна")) },
                            onPlayUtterance = player::play,
                            playingUtteranceId = playing,
                        )
                    }
                }
            }
        }
    }
    companion object {
        val sample = TranscriptResult("synthetic-demo", 40_000, listOf(
            Utterance("d0", "speaker_1", 1000, 4000, "вымышленная запись", "Вымышленная запись."),
            Utterance("d1", "speaker_2", 33_000, 36_000, "только синтетика", "Только синтетика."),
        ))
    }
}
