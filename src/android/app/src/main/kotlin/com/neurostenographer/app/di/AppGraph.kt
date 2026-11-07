package com.neurostenographer.app.di

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import core.audio.AudioPreparer
import core.contracts.TranscriptExporter
import core.contracts.TranscriptionPipeline
import core.export.ContentResolverTranscriptExporter
import core.pipeline.AssetModelStore
import core.pipeline.DefaultTranscriptionPipeline
import kotlinx.coroutines.Dispatchers
import java.io.File

/**
 * App-wide dependencies, one instance per process (the pipeline keeps job state and must survive
 * recomposition and rotation). Engines come from the build type: debug — synthetic engines
 * (src/debug), release — real engines (B6, after the engine modules are merged).
 */
class AppGraph private constructor(context: Context) {

    val pipeline: TranscriptionPipeline = DefaultTranscriptionPipeline(
        prepareAudio = AudioPreparer(context)::prepare,
        jobsDir = File(context.filesDir, "jobs"), // AudioPreparer writes filesDir/jobs/<jobId>/
        engines = pipelineEngines(),
        modelStore = AssetModelStore(context),
        dispatcher = Dispatchers.Default,
    )

    val exporter: TranscriptExporter = ContentResolverTranscriptExporter(context.contentResolver)

    companion object {
        @Volatile private var instance: AppGraph? = null

        fun get(context: Context): AppGraph = instance ?: synchronized(this) {
            instance ?: AppGraph(context.applicationContext).also { instance = it }
        }
    }
}

/** In Compose: `val graph = rememberAppGraph()`, then `graph.pipeline` / `graph.exporter`. */
@Composable
fun rememberAppGraph(): AppGraph {
    val context = LocalContext.current
    return remember(context) { AppGraph.get(context) }
}
