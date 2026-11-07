package feature.importaudio

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import core.contracts.PipelineState
import core.contracts.Stage
import core.contracts.TranscriptResult
import core.contracts.TranscriptionPipeline
import kotlinx.coroutines.launch

internal class PipelineViewModel(pipeline: TranscriptionPipeline) : ViewModel() {
    val session = PipelineSession(pipeline, viewModelScope)
    fun start(uri: String) { viewModelScope.launch { session.start(uri) } }
    override fun onCleared() { session.close() }
}

/** B4 injects its retained pipeline here. No model starts in composition or lifecycle callbacks. */
@Composable
fun PipelineRoute(
    pipeline: TranscriptionPipeline,
    modifier: Modifier = Modifier,
    onHistory: (() -> Unit)? = null,
    resultContent: @Composable (TranscriptResult, () -> Unit) -> Unit,
) {
    val factory = remember(pipeline) {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = PipelineViewModel(pipeline) as T
        }
    }
    val owner: PipelineViewModel = viewModel(key = "front-transcription", factory = factory)
    val ui by owner.session.state.collectAsStateWithLifecycle()
    Column(modifier) {
        ui.actionError?.let { Text(it, modifier = Modifier.padding(16.dp)) }
        when (val state = ui.state) {
            is PipelineState.Completed -> resultContent(state.result, owner.session::reset)
            is PipelineState.Failed -> PipelineFailureScreen(
                error = state.error,
                requiredBytes = state.requiredBytes,
                onRetry = { ui.audioUri?.let(owner::start) },
                onChooseAnotherAudio = owner.session::reset,
            )
            PipelineState.Cancelled -> LaunchedEffect(owner) { owner.session.reset() }
            else -> if (ui.starting || ui.jobId != null) {
                ProcessingScreen(
                    audioName = "Выбранное аудио",
                    stageLabel = if (state is PipelineState.Running) stageLabel(state.stage) else "Подготовка задачи…",
                    onCancel = owner.session::cancel,
                    cancellationRequested = ui.cancellationRequested,
                )
            } else {
                AudioImportRoute(
                    pipeline = pipeline,
                    onJobStarted = {},
                    onStartAudio = owner::start,
                    onHistory = onHistory,
                )
            }
        }
    }
}

internal fun stageLabel(stage: Stage): String = when (stage) {
    Stage.IMPORT -> "Подготовка моделей…"
    Stage.DECODE -> "Подготовка аудио"
    Stage.DIARIZATION -> "Определение говорящих"
    Stage.ASR -> "Распознавание речи"
    Stage.ALIGNMENT -> "Сопоставление реплик и времени"
    Stage.POSTPROCESS -> "Обработка текста"
    Stage.DONE -> "Завершение обработки"
}
