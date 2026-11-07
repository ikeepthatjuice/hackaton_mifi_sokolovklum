package feature.transcript

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import core.contracts.TranscriptExporter
import core.contracts.TranscriptResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal data class HistoryUiState(
    val items: List<HistoryListItem> = emptyList(),
    val busy: Boolean = false,
    val error: String? = null,
    val opened: TranscriptResult? = null,
    val deleteCandidate: HistoryListItem? = null,
)

internal class HistoryViewModel(private val store: TranscriptHistoryStore) : ViewModel() {
    val state = MutableStateFlow(HistoryUiState())
    private var operation: Job? = null
    private var generation = 0L
    private fun run(action: suspend (Long) -> Unit) {
        operation?.cancel()
        val version = ++generation
        state.value = state.value.copy(busy = true, error = null)
        operation = viewModelScope.launch {
            try { action(version) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (version == generation) state.value = state.value.copy(error = "Не удалось выполнить действие с историей. Повторите попытку.")
            } finally {
                if (version == generation) state.value = state.value.copy(busy = false)
            }
        }
    }
    fun refresh() = run { version ->
        val rows = store.list().sortedByDescending { it.createdAtMs }
        if (version == generation) state.value = state.value.copy(items = rows)
    }
    fun open(id: String) = run { version ->
        val result = store.load(id)
        if (version == generation) state.value = state.value.copy(
            opened = result, error = if (result == null) "Запись недоступна. Обновите историю." else null,
        )
    }
    fun closeResult() { state.value = state.value.copy(opened = null) }
    fun leave() {
        ++generation
        operation?.cancel()
        state.value = state.value.copy(opened = null, busy = false, deleteCandidate = null)
    }
    fun askDelete(item: HistoryListItem?) { state.value = state.value.copy(deleteCandidate = item) }
    fun confirmDelete() {
        val item = state.value.deleteCandidate ?: return
        state.value = state.value.copy(deleteCandidate = null)
        run { version ->
            val deleted = store.delete(item.id)
            val rows = store.list().sortedByDescending { it.createdAtMs }
            if (version == generation) state.value = state.value.copy(
                items = rows,
                error = if (deleted) null else "Не удалось удалить запись. Обновите историю и повторите попытку.",
            )
        }
    }
}

/** Retains only navigation in UI. A null repository leaves the release import flow unchanged. */
@Composable
fun TranscriptHistoryHost(
    history: TranscriptHistoryStore?,
    exporter: TranscriptExporter,
    modifier: Modifier = Modifier,
    importContent: @Composable ((() -> Unit)?) -> Unit,
) {
    var showHistory by rememberSaveable { mutableStateOf(false) }
    if (showHistory && history != null) TranscriptHistoryRoute(history, exporter, { showHistory = false }, modifier)
    else importContent(if (history == null) null else { { showHistory = true } })
}

@Composable
fun TranscriptHistoryRoute(
    store: TranscriptHistoryStore,
    exporter: TranscriptExporter,
    onImport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val factory = remember(store) {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = HistoryViewModel(store) as T
        }
    }
    val owner: HistoryViewModel = viewModel(key = "front-history", factory = factory)
    val ui by owner.state.collectAsStateWithLifecycle()
    val leaveHistory = { owner.leave(); onImport() }
    LaunchedEffect(owner) { owner.refresh() }
    BackHandler { if (ui.opened != null) owner.closeResult() else leaveHistory() }
    val opened = ui.opened
    if (opened != null) {
        Column(modifier.fillMaxSize()) {
            TextButton(onClick = owner::closeResult) { Text("К истории") }
            TranscriptExportRoute(opened, exporter, leaveHistory, Modifier.weight(1f))
        }
    } else {
        LazyColumn(modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { Text("История", style = MaterialTheme.typography.headlineMedium) }
            item { OutlinedButton(onClick = leaveHistory) { Text("К выбору аудио") } }
            if (ui.busy) item { Text("Загрузка истории…") }
            ui.error?.let { message -> item {
                Text(message, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = owner::refresh, enabled = !ui.busy) { Text("Повторить") }
            } }
            if (ui.items.isEmpty() && !ui.busy && ui.error == null) item { Text("Пока нет расшифровок") }
            items(ui.items, key = { it.id }) { item ->
                Card(Modifier.fillMaxWidth().clickable(enabled = !ui.busy) { owner.open(item.id) }) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(item.title, style = MaterialTheme.typography.titleMedium)
                        Text(formatHistoryDate(item.createdAtMs))
                        Text("Длительность: ${formatHistoryDuration(item.durationMs)}")
                        TextButton(onClick = { owner.askDelete(item) }, enabled = !ui.busy,
                            modifier = Modifier.semantics { contentDescription = "Удалить ${item.title}" },
                        ) { Text("Удалить") }
                    }
                }
            }
        }
    }
    ui.deleteCandidate?.let { item ->
        AlertDialog(
            onDismissRequest = { owner.askDelete(null) },
            title = { Text("Удалить расшифровку?") },
            text = { Text("«${item.title}» будет удалена из истории. Это действие нельзя отменить.") },
            confirmButton = { TextButton(onClick = owner::confirmDelete) { Text("Удалить запись") } },
            dismissButton = { TextButton(onClick = { owner.askDelete(null) }) { Text("Отмена") } },
        )
    }
}

private fun formatHistoryDate(epochMs: Long): String = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm", Locale("ru", "RU"))
    .withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(epochMs))

private fun formatHistoryDuration(durationMs: Long): String {
    val seconds = durationMs.coerceAtLeast(0) / 1000
    return "${(seconds / 60).toString().padStart(2, '0')}:${(seconds % 60).toString().padStart(2, '0')}"
}
