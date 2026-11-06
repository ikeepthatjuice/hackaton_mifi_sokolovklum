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
import core.contracts.TranscriptionPipeline
import core.contracts.PipelineState
import core.contracts.Stage
import core.contracts.Utterance
import feature.importaudio.AudioImportRoute
import feature.importaudio.ProcessingScreen
import feature.transcript.TranscriptScreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Real SAF selection, debug-only synthetic processing. Never launches a model. */
@Composable
fun FrontScreensDemo() {
    var screen by rememberSaveable { mutableStateOf("import") }
    var jobId by rememberSaveable { mutableStateOf<String?>(null) }
    val pipeline = remember { SyntheticPipelineFixture() }
    val result = remember { syntheticTranscript() }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column {
                Text(
                    "DEBUG · Выбор файла настоящий. Результаты синтетические; модели и экспорт не подключены.",
                    modifier = Modifier.padding(16.dp),
                )
                when (screen) {
                    "import" -> AudioImportRoute(
                        pipeline = pipeline,
                        onJobStarted = {
                            jobId = it
                            screen = "processing"
                        },
                        modifier = Modifier.weight(1f),
                    )
                    "processing" -> {
                        ProcessingScreen(
                            audioName = "Выбранное аудио (обработка синтетическая)",
                            stageLabel = "Демонстрация этапа: распознавание речи",
                            onCancel = {
                                jobId?.let(pipeline::cancel)
                                jobId = null
                                screen = "import"
                            },
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
                            jobId = null
                            screen = "import"
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/** Fixture for F2: receives the real content URI, but never reads its audio or produces ASR. */
private class SyntheticPipelineFixture : TranscriptionPipeline {
    private val state = MutableStateFlow<PipelineState>(PipelineState.Idle)
    private var counter = 0

    override fun start(audioUri: String): String {
        require(audioUri.startsWith("content://"))
        counter += 1
        state.value = PipelineState.Running(Stage.IMPORT, null)
        return "synthetic-job-$counter"
    }

    override fun observe(jobId: String): StateFlow<PipelineState> = state

    override fun cancel(jobId: String) {
        state.value = PipelineState.Cancelled
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
