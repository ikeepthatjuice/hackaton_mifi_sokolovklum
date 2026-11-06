package feature.importaudio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Stateless UI: the host owns the URI, picker and processing job. */
@Composable
fun ImportScreen(
    selectedAudioName: String?,
    onChooseAudio: () -> Unit,
    onStart: () -> Unit,
    modifier: Modifier = Modifier,
    errorMessage: String? = null,
    busy: Boolean = false,
) {
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Нейро-стенографист", style = MaterialTheme.typography.headlineMedium)
        Text("Выберите аудио. Приложение покажет реплики, говорящих и время.")
        Text("Обработка выполняется на устройстве без интернета.")
        OutlinedButton(onClick = onChooseAudio, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(if (selectedAudioName == null) "Выбрать аудио" else "Выбрать другой файл")
        }
        Text(selectedAudioName ?: "Файл ещё не выбран")
        if (errorMessage != null) {
            Text(errorMessage, color = MaterialTheme.colorScheme.error)
        }
        Button(
            onClick = onStart,
            enabled = selectedAudioName != null && !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Начать обработку")
        }
    }
}
