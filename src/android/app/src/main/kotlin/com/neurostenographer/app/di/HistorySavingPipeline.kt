package com.neurostenographer.app.di

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import core.contracts.PipelineState
import core.contracts.TranscriptionPipeline
import core.history.HistoryMeta
import core.history.HistoryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Same pipeline for the UI, plus: every job that ends Completed is saved to [history].
 * Failed/Cancelled jobs are not saved. A failed save never affects the job's result.
 */
internal class HistorySavingPipeline(
    private val delegate: TranscriptionPipeline,
    private val history: HistoryRepository,
    private val sourceName: (audioUri: String) -> String,
    private val scope: CoroutineScope,
    // Class name only: transcript content never goes to logs.
    private val onSaveError: (Throwable) -> Unit = { Log.w(TAG, "history save failed: ${it.javaClass.simpleName}") },
) : TranscriptionPipeline by delegate {

    override fun start(audioUri: String): String {
        val jobId = delegate.start(audioUri)
        scope.launch {
            val name = sourceName(audioUri) // now, while the picked URI is still readable
            val final = delegate.observe(jobId).first {
                it is PipelineState.Completed || it is PipelineState.Failed || it is PipelineState.Cancelled
            }
            if (final !is PipelineState.Completed) return@launch
            val r = final.result
            runCatching { history.save(r, HistoryMeta(r.jobId, name, System.currentTimeMillis(), r.durationMs, r.warnings)) }
                .onFailure(onSaveError)
        }
        return jobId
    }

    private companion object {
        const val TAG = "History"
    }
}

/** File name for the history list: DISPLAY_NAME of a content:// URI, else the last path segment. */
internal fun displayName(context: Context, audioUri: String): String {
    val uri = Uri.parse(audioUri)
    val fromProvider = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()
    return fromProvider ?: uri.lastPathSegment ?: "audio"
}
