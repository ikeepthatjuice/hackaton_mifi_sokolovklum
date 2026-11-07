package feature.transcript

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import core.contracts.TranscriptResult
import core.contracts.Utterance
import kotlinx.coroutines.flow.StateFlow

/** UI boundary for back's AudioSegmentPlayer; source URI and media lifetime belong to its adapter. */
interface TranscriptPlayback : AutoCloseable {
    val playingId: StateFlow<String?>
    fun play(utterance: Utterance)
    fun stop()
    override fun close() { stop() }
}

fun transcriptPlainText(result: TranscriptResult, speakerNames: Map<String, String> = emptyMap()): String =
    result.utterances.joinToString("\n") {
        val minutes = it.startMs.coerceAtLeast(0) / 60_000
        val seconds = it.startMs.coerceAtLeast(0) / 1_000 % 60
        val time = "${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
        "${speakerNames[it.speakerId] ?: it.speakerId} [$time]: ${it.displayText}"
    }

/** Android clipboard and system chooser only; this app never sends text over a network. */
@Composable
fun TranscriptTextActions(result: TranscriptResult, speakerNames: Map<String, String> = emptyMap()) {
    val context = LocalContext.current
    val text = remember(result, speakerNames) { transcriptPlainText(result, speakerNames) }
    var message by remember(result.jobId) { mutableStateOf<String?>(null) }
    Column {
        OutlinedButton(enabled = text.isNotBlank(), onClick = {
            message = try {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Расшифровка", text))
                "Текст скопирован"
            } catch (_: Exception) { "Не удалось скопировать текст. Повторите попытку." }
        }) { Text("Копировать") }
        OutlinedButton(enabled = text.isNotBlank(), onClick = {
            message = try {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                context.startActivity(Intent.createChooser(send, "Поделиться расшифровкой").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                null
            } catch (_: Exception) { "Не удалось открыть меню отправки текста." }
        }) { Text("Поделиться") }
        message?.let { Text(it) }
    }
}
