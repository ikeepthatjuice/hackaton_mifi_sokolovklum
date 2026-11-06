package feature.importaudio

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import core.contracts.TranscriptionPipeline
import java.util.concurrent.CancellationException

/** UI-only selection state. Content URIs are passed unchanged to the shared pipeline. */
internal class AudioImportController(
    private val pipeline: TranscriptionPipeline,
    initialUri: String? = null,
    initialName: String? = null,
    initialJobId: String? = null,
) {
    var audioUri by mutableStateOf(initialUri)
        private set
    var audioName by mutableStateOf(initialName)
        private set
    var jobId by mutableStateOf(initialJobId)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set

    fun acceptSelection(uri: String?, name: String?) {
        // Cancelling the picker keeps the previous selection, error and job unchanged.
        if (uri == null || jobId != null) return
        audioUri = uri
        audioName = name?.takeIf { it.isNotBlank() } ?: "Выбранное аудио"
        errorMessage = null
    }

    fun selectionFailed() {
        errorMessage = "Не удалось получить доступ к файлу. Выберите аудио ещё раз."
    }

    /** Called after Completed/Failed/Cancelled before returning to the import screen. */
    fun reset() {
        jobId = null
        errorMessage = null
    }

    fun start(): String? {
        jobId?.let { return it }
        val uri = audioUri ?: return null
        return try {
            pipeline.start(uri).also {
                jobId = it
                errorMessage = null
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            errorMessage = "Не удалось начать обработку. Повторите попытку."
            null
        }
    }
}
