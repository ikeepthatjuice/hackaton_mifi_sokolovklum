package feature.transcript

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import core.contracts.TranscriptResult

/** Displays the shared result without changing text, speaker IDs or times. */
@Composable
@OptIn(ExperimentalFoundationApi::class)
fun TranscriptScreen(
    result: TranscriptResult,
    onSaveJson: () -> Unit,
    onChooseAnotherAudio: () -> Unit,
    modifier: Modifier = Modifier,
    exportAvailable: Boolean = false,
    exportInProgress: Boolean = false,
    exportMessage: String? = null,
    speakerNames: Map<String, String> = emptyMap(),
    onRenameSpeaker: ((String) -> Unit)? = null,
    onUtteranceActions: ((String) -> Unit)? = null,
    canUndo: Boolean = false,
    onUndo: (() -> Unit)? = null,
    editMessage: String? = null,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text(
                "Расшифровка",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(top = 24.dp),
            )
        }
        item(key = "transcript:count") { Text("Реплик: ${result.utterances.size}") }
        if (onUndo != null) item(key = "transcript:undo") {
            OutlinedButton(onClick = onUndo, enabled = canUndo) { Text("Отменить последнюю правку") }
        }
        if (editMessage != null) item { Text(editMessage) }
        if (result.warnings.isNotEmpty()) {
            item { Text(result.warnings.joinToString("\n")) }
        }
        // Separate lazy items keep each action reachable at large font sizes / in landscape.
        item(key = "transcript:export") {
            Button(
                onClick = onSaveJson,
                enabled = exportAvailable && !exportInProgress,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (exportInProgress) "Сохранение…" else "Сохранить JSON")
            }
        }
        if (!exportAvailable) item { Text("Сохранение пока недоступно") }
        if (exportMessage != null) item { Text(exportMessage) }
        item {
            OutlinedButton(onClick = onChooseAnotherAudio, modifier = Modifier.fillMaxWidth()) {
                Text("Выбрать другое аудио")
            }
        }
        if (result.utterances.isEmpty()) {
            item { Text("Речь в записи не обнаружена") }
        }
        items(result.utterances, key = { it.id }) { utterance ->
            Card(modifier = Modifier.fillMaxWidth().testTag("utterance:${utterance.id}").then(
                if (onUtteranceActions == null) Modifier else Modifier.combinedClickable(
                    onClick = {}, onLongClickLabel = "Действия с репликой",
                    onLongClick = { onUtteranceActions(utterance.id) },
                ),
            )) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val name = speakerNames[utterance.speakerId] ?: utterance.speakerId
                    if (onRenameSpeaker == null) Text(name, style = MaterialTheme.typography.titleMedium)
                    else TextButton(onClick = { onRenameSpeaker(utterance.speakerId) }) { Text(name) }
                    Text("${formatTimestamp(utterance.startMs)} — ${formatTimestamp(utterance.endMs)}")
                    Text(utterance.displayText)
                }
            }
        }
        item(key = "transcript:end") { Text("Конец расшифровки", modifier = Modifier.padding(bottom = 24.dp)) }
    }
}

private fun formatTimestamp(milliseconds: Long): String {
    val hours = milliseconds / 3_600_000
    val minutes = milliseconds / 60_000 % 60
    val seconds = milliseconds / 1_000 % 60
    val millis = milliseconds % 1_000
    return "${hours.toString().padStart(2, '0')}:" +
        "${minutes.toString().padStart(2, '0')}:" +
        "${seconds.toString().padStart(2, '0')}." +
        millis.toString().padStart(3, '0')
}
