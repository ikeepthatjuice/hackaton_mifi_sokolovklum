package feature.transcript

import core.contracts.TranscriptExporter
import core.contracts.TranscriptResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class ExportPhase { IDLE, PICKING, SAVING, SAVED, FAILED }
internal data class ExportUiState(val phase: ExportPhase = ExportPhase.IDLE, val message: String? = null)

/** Keeps the exact result chosen before opening SAF; a new screen must not replace it. */
internal class JsonExportSession(
    private val exporter: TranscriptExporter,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutableState = MutableStateFlow(ExportUiState())
    val state = mutableState.asStateFlow()
    private var pending: TranscriptResult? = null
    private var exportJob: Job? = null

    fun beginPicking(result: TranscriptResult): Boolean {
        if (state.value.phase == ExportPhase.PICKING || state.value.phase == ExportPhase.SAVING) return false
        pending = result
        mutableState.value = ExportUiState(ExportPhase.PICKING)
        return true
    }

    fun destinationSelected(uri: String?) {
        val result = pending
        pending = null
        if (uri == null) {
            if (state.value.phase == ExportPhase.PICKING) mutableState.value = ExportUiState()
            return
        }
        if (state.value.phase == ExportPhase.SAVING || state.value.phase == ExportPhase.SAVED) return
        if (result == null) {
            mutableState.value = ExportUiState(ExportPhase.FAILED, "Сохранение прервано. Выберите место заново.")
            return
        }
        mutableState.value = ExportUiState(ExportPhase.SAVING)
        exportJob = scope.launch {
            try {
                val outcome = withContext(dispatcher) { exporter.export(result, uri) }
                outcome.exceptionOrNull()?.let { if (it is CancellationException) throw it }
                mutableState.value = if (outcome.isSuccess) {
                    ExportUiState(ExportPhase.SAVED, "JSON сохранён")
                } else {
                    ExportUiState(ExportPhase.FAILED, "Не удалось сохранить JSON. Выберите место и повторите.")
                }
            } catch (cancelled: CancellationException) {
                mutableState.value = ExportUiState()
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = ExportUiState(ExportPhase.FAILED, "Не удалось сохранить JSON. Выберите место и повторите.")
            }
        }
    }

    fun pickerFailed() {
        pending = null
        mutableState.value = ExportUiState(ExportPhase.FAILED, "Не удалось открыть выбор места сохранения.")
    }

    fun close() {
        pending = null
        exportJob?.cancel()
    }
}
