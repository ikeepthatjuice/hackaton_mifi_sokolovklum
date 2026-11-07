package app.frontdebug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import core.contracts.PipelineError
import core.contracts.TranscriptResult
import core.contracts.Utterance
import feature.importaudio.ImportScreen
import feature.importaudio.ProcessingScreen
import feature.importaudio.PipelineFailureScreen
import feature.transcript.TranscriptScreen

/** Synthetic layout harness for font/rotation/scroll tests; does not run models or export files. */
class FrontUxPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val density = LocalDensity.current
            val fontScale = intent.getFloatExtra("fontScale", density.fontScale)
            var screen by rememberSaveable { mutableStateOf(intent.getStringExtra("screen") ?: "import") }
            var saved by rememberSaveable { mutableStateOf(false) }
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                MaterialTheme {
                    Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
                        when (screen) {
                            "processing" -> ProcessingScreen("Синтетическое аудио", "Распознавание речи", { screen = "import" })
                            "storage" -> PipelineFailureScreen(
                                PipelineError.INSUFFICIENT_STORAGE, {}, { screen = "import" }, requiredBytes = 1_048_577,
                            )
                            "transcript", "saving", "llm-timeout", "llm-failure" -> TranscriptScreen(
                                result = when (screen) {
                                    "llm-timeout" -> longSyntheticResult.copy(warnings = listOf(
                                        "Пунктуация и регистр не обработаны: исчерпан лимит времени LLM. Показан исходный текст.",
                                    ))
                                    "llm-failure" -> longSyntheticResult.copy(warnings = listOf(
                                        "Пунктуация не обработана: ошибка локальной LLM",
                                    ))
                                    else -> longSyntheticResult
                                },
                                onSaveJson = { saved = true },
                                onChooseAnotherAudio = { screen = "import" },
                                exportAvailable = true,
                                exportInProgress = screen == "saving",
                                exportMessage = if (saved) "Проверен callback кнопки (файл не записывался)" else null,
                            )
                            else -> ImportScreen("Синтетическое аудио", {}, { screen = "processing" })
                        }
                    }
                }
            }
        }
    }
}

private val longSyntheticResult = TranscriptResult(
    jobId = "synthetic-layout-only", durationMs = 1_000_000,
    utterances = List(500) { index ->
        Utterance(
            id = "synthetic-$index", speakerId = "speaker_${index % 2 + 1}",
            startMs = index * 2_000L, endMs = index * 2_000L + 1_500,
            rawText = "вымышленная реплика номер $index для проверки прокрутки",
            displayText = "Вымышленная реплика № $index для проверки прокрутки длинного списка.",
        )
    },
    warnings = listOf("Синтетический layout-тест: модели и экспорт не запускались."),
)
