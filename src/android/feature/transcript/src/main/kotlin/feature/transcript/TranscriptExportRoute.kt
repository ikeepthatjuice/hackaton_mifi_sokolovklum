package feature.transcript

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import core.contracts.TranscriptExporter
import core.contracts.TranscriptResult
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

internal class JsonExportViewModel(exporter: TranscriptExporter) : ViewModel() {
    val session = JsonExportSession(exporter, viewModelScope)
    override fun onCleared() { session.close() }
}

/** Chooses a destination only; the injected exporter owns JSON formatting and writing. */
@Composable
fun TranscriptExportRoute(
    result: TranscriptResult,
    exporter: TranscriptExporter,
    onChooseAnotherAudio: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val factory = remember(exporter) {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = JsonExportViewModel(exporter) as T
        }
    }
    val owner: JsonExportViewModel = viewModel(key = "export:${result.jobId}", factory = factory)
    val ui by owner.session.state.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) {
        owner.session.destinationSelected(it?.toString())
    }
    TranscriptScreen(
        result = result,
        onSaveJson = {
            if (owner.session.beginPicking(result)) {
                try {
                    picker.launch("transcript-${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss", Locale.ROOT))}.json")
                } catch (_: Exception) {
                    owner.session.pickerFailed()
                }
            }
        },
        onChooseAnotherAudio = onChooseAnotherAudio,
        modifier = modifier,
        exportAvailable = true,
        exportInProgress = ui.phase == ExportPhase.PICKING || ui.phase == ExportPhase.SAVING,
        exportMessage = ui.message,
    )
}
