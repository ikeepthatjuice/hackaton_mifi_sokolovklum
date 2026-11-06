package feature.importaudio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import core.contracts.PipelineError

/** requiredBytes is the missing capacity from B4/#11, never a guessed total file size. */
@Composable
fun PipelineFailureScreen(
    error: PipelineError,
    onRetry: () -> Unit,
    onChooseAnotherAudio: () -> Unit,
    modifier: Modifier = Modifier,
    requiredBytes: Long? = null,
) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            requiredBytes?.takeIf { error == PipelineError.INSUFFICIENT_STORAGE && it > 0 }
                ?.let { "Освободите ${missingMegabytes(it)} МБ" }
                ?: "Обработка не завершена",
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(errorMessage(error))
        Button(onClick = onRetry) { Text("Повторить") }
        OutlinedButton(onClick = onChooseAnotherAudio) { Text("Выбрать другое аудио") }
    }
}

internal fun missingMegabytes(bytes: Long): Long =
    bytes.coerceAtLeast(0) / 1_048_576 + if (bytes > 0 && bytes % 1_048_576 != 0L) 1 else 0

internal fun errorMessage(error: PipelineError): String = when (error) {
    PipelineError.UNSUPPORTED_FORMAT -> "Формат файла не поддерживается или доступ к нему потерян."
    PipelineError.DECODE_FAILED -> "Не удалось прочитать аудио. Попробуйте другой файл."
    PipelineError.MODEL_MISSING -> "На устройстве отсутствует необходимая модель."
    PipelineError.MODEL_LOAD_FAILED -> "Не удалось загрузить модель."
    PipelineError.OUT_OF_MEMORY -> "Не хватает оперативной памяти. Закройте другие приложения."
    PipelineError.ENGINE_FAILED -> "Во время обработки произошла ошибка модели."
    PipelineError.EXPORT_FAILED -> "Не удалось сохранить результат."
    PipelineError.INSUFFICIENT_STORAGE -> "Не хватает места на устройстве. Освободите место и повторите обработку."
    PipelineError.UNKNOWN -> "Не удалось завершить обработку. Повторите попытку."
}
