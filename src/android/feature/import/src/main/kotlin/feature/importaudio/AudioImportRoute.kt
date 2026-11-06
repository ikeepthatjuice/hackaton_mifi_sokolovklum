package feature.importaudio

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import core.contracts.TranscriptionPipeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.CancellationException

/** SAF handles access; no storage permission, file path conversion or model work in UI. */
@Composable
fun AudioImportRoute(
    pipeline: TranscriptionPipeline,
    onJobStarted: (String) -> Unit,
    modifier: Modifier = Modifier,
    onStartAudio: ((String) -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val saver = remember(pipeline) {
        listSaver<AudioImportController, String>(
            save = { listOf(it.audioUri.orEmpty(), it.audioName.orEmpty(), it.jobId.orEmpty()) },
            restore = {
                AudioImportController(
                    pipeline,
                    initialUri = it[0].ifEmpty { null },
                    initialName = it[1].ifEmpty { null },
                    initialJobId = it[2].ifEmpty { null },
                )
            },
        )
    }
    val controller = rememberSaveable(pipeline, saver = saver) { AudioImportController(pipeline) }
    // F3 owns the job; clear an old F2 guard when returning after a terminal state.
    LaunchedEffect(controller, onStartAudio != null) {
        if (onStartAudio != null) controller.reset()
    }
    var picking by rememberSaveable { mutableStateOf(false) }
    var pickerError by remember { mutableStateOf<String?>(null) }
    var readingSelection by remember { mutableStateOf(false) }
    var starting by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(LocalAudioDocument()) { uri ->
        picking = false
        pickerError = null
        if (uri != null) {
            readingSelection = true
            scope.launch {
                try {
                    val name = withContext(Dispatchers.IO) {
                        try {
                            context.contentResolver.takePersistableUriPermission(
                                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION,
                            )
                        } catch (_: SecurityException) {
                            // Some SAF providers only grant temporary access; immediate import can still read it.
                        }
                        context.contentResolver.openInputStream(uri)?.use { }
                            ?: throw java.io.IOException("Audio URI cannot be opened")
                        displayName(context, uri)
                    }
                    controller.acceptSelection(uri.toString(), name)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    controller.selectionFailed()
                } finally {
                    readingSelection = false
                }
            }
        }
    }
    ImportScreen(
        selectedAudioName = controller.audioName,
        onChooseAudio = {
            if (!picking && !readingSelection && !starting && controller.jobId == null) {
                picking = true
                pickerError = null
                try {
                    picker.launch(arrayOf("audio/*"))
                } catch (_: Exception) {
                    picking = false
                    pickerError = "Не удалось открыть выбор файла. Попробуйте ещё раз."
                }
            }
        },
        onStart = {
            val uri = controller.audioUri
            if (uri != null && !picking && !readingSelection && !starting && controller.jobId == null) {
                if (onStartAudio != null) {
                    onStartAudio(uri) // F3 owns the job in its ViewModel, not this screen's scope.
                } else {
                    starting = true
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { controller.start() }?.let(onJobStarted)
                        } finally {
                            starting = false
                        }
                    }
                }
            }
        },
        modifier = modifier,
        errorMessage = pickerError ?: controller.errorMessage,
        busy = picking || readingSelection || starting || controller.jobId != null,
    )
}

private class LocalAudioDocument : ActivityResultContracts.OpenDocument() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        super.createIntent(context, input).putExtra(Intent.EXTRA_LOCAL_ONLY, true)
}

private fun displayName(context: Context, uri: Uri): String? =
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
        val column = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (column >= 0 && it.moveToFirst()) it.getString(column) else null
    }
