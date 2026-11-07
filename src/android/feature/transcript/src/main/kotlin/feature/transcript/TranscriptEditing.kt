package feature.transcript

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

internal class TranscriptEditViewModel(val editor: TranscriptEditor) : ViewModel() {
    val error = MutableStateFlow<String?>(null)
    val busy = MutableStateFlow(false)
    fun perform(action: suspend TranscriptEditor.() -> Unit) {
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            try {
                error.value = null
                editor.action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                error.value = "Не удалось применить правку. Повторите попытку."
            } finally {
                busy.value = false
            }
        }
    }
    override fun onCleared() { editor.close() }
}

internal data class EditDialog(val kind: String, val id: String)

/** Dialogs dispatch commands only; no editing rules or DTO mutation in UI. */
@Composable
internal fun TranscriptEditDialog(
    dialog: EditDialog?,
    state: TranscriptEditUiState,
    onDismiss: () -> Unit,
    onDialog: (EditDialog) -> Unit,
    onCommand: (suspend TranscriptEditor.() -> Unit) -> Unit,
) {
    if (dialog == null) return
    val utterance = state.result.utterances.find { it.id == dialog.id }
    val title = when (dialog.kind) {
        "rename" -> "Переименовать"
        "text" -> "Исправить текст"
        "speaker" -> "Сменить спикера"
        else -> "Действия с репликой"
    }
    val initial = if (dialog.kind == "rename") state.speakerNames[dialog.id] ?: dialog.id
        else utterance?.displayText.orEmpty()
    var value by remember(dialog) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            when (dialog.kind) {
                "rename", "text" -> OutlinedTextField(
                    value = value, onValueChange = { value = it },
                    label = { Text(if (dialog.kind == "rename") "Имя спикера" else "Текст реплики") },
                    singleLine = dialog.kind == "rename",
                )
                "speaker" -> Column {
                    state.result.utterances.map { it.speakerId }.distinct().forEach { id ->
                        TextButton(onClick = {
                            onCommand { changeSpeaker(dialog.id, id) }; onDismiss()
                        }) { Text(state.speakerNames[id] ?: id) }
                    }
                }
                else -> Column {
                    TextButton(onClick = { onDialog(EditDialog("text", dialog.id)) }) { Text("Исправить текст") }
                    TextButton(onClick = { onDialog(EditDialog("speaker", dialog.id)) }) { Text("Сменить спикера") }
                    TextButton(
                        enabled = state.result.utterances.indexOfFirst { it.id == dialog.id } > 0,
                        onClick = { onCommand { mergePrevious(dialog.id) }; onDismiss() },
                    ) { Text("Склеить с предыдущей") }
                }
            }
        },
        confirmButton = {
            if (dialog.kind == "rename" || dialog.kind == "text") TextButton(
                enabled = value.isNotBlank(),
                onClick = {
                    if (dialog.kind == "rename") onCommand { renameSpeaker(dialog.id, value.trim()) }
                    else onCommand { editText(dialog.id, value) }
                    onDismiss()
                },
            ) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
