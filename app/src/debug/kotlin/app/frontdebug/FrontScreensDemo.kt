package app.frontdebug

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import core.contracts.TranscriptResult
import core.contracts.Utterance
import feature.importaudio.ImportScreen
import feature.importaudio.ProcessingScreen
import feature.transcript.TranscriptScreen

/** Debug-only manual walkthrough. Does not read audio or launch a model. */
@Composable
fun FrontScreensDemo() {
    var screen by rememberSaveable { mutableStateOf("import") }
    var sampleSelected by rememberSaveable { mutableStateOf(false) }
    val result = remember { syntheticTranscript() }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column {
                Text(
                    "DEBUG · Синтетические данные. Модели и экспорт не подключены.",
                    modifier = Modifier.padding(16.dp),
                )
                when (screen) {
                    "import" -> ImportScreen(
                        selectedAudioName = if (sampleSelected) "Синтетический пример (не аудиофайл)" else null,
                        onChooseAudio = { sampleSelected = true },
                        onStart = { screen = "processing" },
                        modifier = Modifier.weight(1f),
                    )
                    "processing" -> {
                        ProcessingScreen(
                            audioName = "Синтетический пример",
                            stageLabel = "Демонстрация этапа: распознавание речи",
                            onCancel = { screen = "import" },
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedButton(
                            onClick = { screen = "result" },
                            modifier = Modifier.padding(16.dp),
                        ) { Text("DEBUG: показать синтетический результат") }
                    }
                    "result" -> TranscriptScreen(
                        result = result,
                        onSaveJson = {},
                        onChooseAnotherAudio = {
                            sampleSelected = false
                            screen = "import"
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

private fun syntheticTranscript() = TranscriptResult(
    jobId = "synthetic-debug-only",
    durationMs = 12_000,
    utterances = listOf(
        Utterance(
            id = "synthetic-1", speakerId = "speaker_1", startMs = 1_000, endMs = 3_500,
            rawText = "это вымышленный пример", displayText = "Это вымышленный пример.",
        ),
        Utterance(
            id = "synthetic-2", speakerId = "speaker_2", startMs = 4_000, endMs = 7_000,
            rawText = "проверим отображение реплик", displayText = "Проверим отображение реплик.",
        ),
        Utterance(
            id = "synthetic-3", speakerId = "speaker_1", startMs = 8_000, endMs = 11_000,
            rawText = "затем выберем другой файл", displayText = "Затем выберем другой файл.",
        ),
    ),
    warnings = listOf("Синтетический результат для проверки интерфейса, не вывод модели."),
)
