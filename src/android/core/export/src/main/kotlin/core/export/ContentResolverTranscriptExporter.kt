package core.export

import android.content.ContentResolver
import android.net.Uri
import core.contracts.PipelineError
import core.contracts.TranscriptExporter
import core.contracts.TranscriptResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/** Export failure for the UI; [error] is always EXPORT_FAILED, details go to [cause]/message (logs only). */
class TranscriptExportException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    val error: PipelineError get() = PipelineError.EXPORT_FAILED
}

/**
 * Writes [TranscriptJson] to a SAF destination (e.g. from ACTION_CREATE_DOCUMENT).
 * Returns Result.failure(TranscriptExportException) on invalid data or IO errors; cancellation is rethrown.
 */
class ContentResolverTranscriptExporter(
    private val resolver: ContentResolver,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : TranscriptExporter {

    override suspend fun export(result: TranscriptResult, destinationUri: String): Result<Unit> = try {
        val json = TranscriptJson.encode(result).toByteArray(Charsets.UTF_8)
        withContext(dispatcher) {
            // "wt": some providers keep the old tail on plain "w" when the new file is shorter.
            val out = resolver.openOutputStream(Uri.parse(destinationUri), "wt")
                ?: throw TranscriptExportException("provider returned no stream")
            out.use { it.write(json) }
        }
        Result.success(Unit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: TranscriptExportException) {
        Result.failure(e)
    } catch (e: Exception) {
        Result.failure(TranscriptExportException("export failed: ${e.javaClass.simpleName}", e))
    }
}
