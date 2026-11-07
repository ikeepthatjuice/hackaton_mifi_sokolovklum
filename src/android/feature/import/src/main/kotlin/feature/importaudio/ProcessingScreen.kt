package feature.importaudio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Stage text comes from the host; no timer or invented progress percentage. */
@Composable
fun ProcessingScreen(
    audioName: String,
    stageLabel: String,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    cancellationRequested: Boolean = false,
) {
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Обработка аудио", style = MaterialTheme.typography.headlineMedium)
        Text(audioName)
        CircularProgressIndicator()
        Text(
            text = if (cancellationRequested) "Отмена обработки…" else stageLabel,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        Text("Время обработки зависит от длительности записи и устройства.")
        OutlinedButton(
            onClick = onCancel,
            enabled = !cancellationRequested,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Отменить")
        }
    }
}
