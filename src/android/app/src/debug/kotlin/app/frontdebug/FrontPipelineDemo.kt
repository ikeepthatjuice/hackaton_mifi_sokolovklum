package app.frontdebug

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import core.contracts.PipelineError
import core.contracts.PipelineState
import core.contracts.Stage
import core.contracts.TranscriptResult
import core.contracts.TranscriptionPipeline
import core.contracts.Utterance
import feature.importaudio.PipelineRoute
import feature.transcript.TranscriptScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

private class DebugPipelineOwner : ViewModel() {
    val pipeline = DebugPipeline(viewModelScope)
}

@Composable
fun FrontPipelineDemo() {
    val factory = remember {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = DebugPipelineOwner() as T
        }
    }
    val owner: DebugPipelineOwner = viewModel(factory = factory)
    Column {
        Text(
            "DEBUG · Выбор файла настоящий. Pipeline и результат синтетические; аудио не читается.",
            modifier = Modifier.padding(16.dp),
        )
        PipelineRoute(owner.pipeline, modifier = Modifier.weight(1f)) { result, onNew ->
            TranscriptScreen(result, onSaveJson = {}, onChooseAnotherAudio = onNew)
        }
    }
}

/** Only src/debug: typed real contract, synthetic states, no audio or model access. */
private class DebugPipeline(private val scope: CoroutineScope) : TranscriptionPipeline {
    private val sequence = AtomicInteger()
    private val states = ConcurrentHashMap<String, MutableStateFlow<PipelineState>>()
    private val jobs = ConcurrentHashMap<String, Job>()

    override fun start(audioUri: String): String {
        require(audioUri.startsWith("content://"))
        val id = "synthetic-${sequence.incrementAndGet()}"
        val state = MutableStateFlow<PipelineState>(PipelineState.Idle)
        states[id] = state
        jobs[id] = scope.launch {
            for (stage in Stage.entries) {
                state.value = PipelineState.Running(stage, null)
                delay(450)
            }
            state.value = PipelineState.Completed(
                TranscriptResult(
                    jobId = id, durationMs = 4_000,
                    utterances = listOf(
                        Utterance("synthetic-1", "speaker_1", 100, 1500, "это вымышленный пример", "Это вымышленный пример."),
                        Utterance("synthetic-2", "speaker_2", 2000, 3500, "проверим отмену и повтор", "Проверим отмену и повтор."),
                    ),
                    warnings = listOf("Синтетический результат. Модели не запускались."),
                ),
            )
        }
        return id
    }

    override fun observe(jobId: String): StateFlow<PipelineState> = states[jobId]
        ?: MutableStateFlow(PipelineState.Failed(PipelineError.UNKNOWN))

    override fun cancel(jobId: String) {
        val state = states[jobId] ?: return
        if (state.value is PipelineState.Completed || state.value is PipelineState.Failed) return
        jobs.remove(jobId)?.cancel()
        state.value = PipelineState.Cancelled
    }
}
