package feature.transcript

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
    editorFactory: ((TranscriptResult) -> TranscriptEditor)? = null,
) {
    val factory = remember(exporter) {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = JsonExportViewModel(exporter) as T
        }
    }
    val owner: JsonExportViewModel = viewModel(key = "export:${result.jobId}", factory = factory)
    val ui by owner.session.state.collectAsStateWithLifecycle()
    val editOwner: TranscriptEditViewModel? = if (editorFactory == null) null else {
        val editFactory = remember(editorFactory, result.jobId) {
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    TranscriptEditViewModel(editorFactory(result)) as T
            }
        }
        viewModel(key = "edit:${result.jobId}", factory = editFactory)
    }
    val edited = editOwner?.editor?.state?.collectAsStateWithLifecycle()?.value
    val editing = editOwner?.busy?.collectAsStateWithLifecycle()?.value == true
    val editError = editOwner?.error?.collectAsStateWithLifecycle()?.value
    val currentResult = edited?.result ?: result
    var dialog by remember(result.jobId) { mutableStateOf<EditDialog?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) {
        owner.session.destinationSelected(it?.toString())
    }
    TranscriptScreen(
        result = currentResult,
        onSaveJson = {
            if (owner.session.beginPicking(currentResult)) {
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
        speakerNames = edited?.speakerNames.orEmpty(),
        onRenameSpeaker = if (editOwner == null || editing) null else { id -> dialog = EditDialog("rename", id) },
        onUtteranceActions = if (editOwner == null || editing) null else { id -> dialog = EditDialog("actions", id) },
        canUndo = edited?.canUndo == true && !editing,
        onUndo = if (editOwner == null) null else { { editOwner.perform { undo() } } },
        editMessage = if (editing) "Применение правки…" else editError,
    )
    if (edited != null) TranscriptEditDialog(
        dialog, edited, { dialog = null }, { dialog = it }, editOwner::perform,
    )
}
