package com.neurostenographer.app.di

import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.neurostenographer.app.BuildConfig
import core.audio.AudioPreparer
import core.contracts.PipelineState
import core.pipeline.AssetModelStore
import core.pipeline.DefaultTranscriptionPipeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Full cycle on real engines: audio → pipeline (wired as in AppGraph) → AppGraph.exporter →
 * files/fullcycle.json, then checked on the host with quality/check_export.py.
 * Runs only with -PrealEngines. Instrumentation args:
 *  - `audio`: file name in the app's external files dir (adb push there); default jfk.wav from test assets;
 *  - `language`: whisper language, default "en" (jfk.wav is English); "ru" for a Russian recording.
 * Logs timings and counts only, never transcript text.
 */
@RunWith(AndroidJUnit4::class)
class FullCycleTest {

    @Test
    fun audioToExportedJson(): Unit = runBlocking {
        assumeTrue("needs -PrealEngines", BuildConfig.REAL_ENGINES)
        val args = InstrumentationRegistry.getArguments()
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val audio = args.getString("audio")?.let { File(target.getExternalFilesDir(null), it) }
            ?: File(target.cacheDir, "jfk.wav").also { wav ->
                InstrumentationRegistry.getInstrumentation().context.assets.open("jfk.wav").use { input ->
                    wav.outputStream().use { input.copyTo(it) }
                }
            }
        assertTrue("no audio file ${audio.name}", audio.isFile)

        val pipeline = DefaultTranscriptionPipeline(
            prepareAudio = AudioPreparer(target)::prepare,
            jobsDir = File(target.filesDir, "jobs"),
            engines = pipelineEngines(asrLanguage = args.getString("language") ?: "en"),
            modelStore = AssetModelStore(target),
            dispatcher = Dispatchers.Default,
        )

        val started = SystemClock.elapsedRealtime()
        val id = pipeline.start(Uri.fromFile(audio).toString())
        var stageStart = started
        var lastState: PipelineState? = null
        val final = withTimeout(30 * 60_000L) {
            pipeline.observe(id)
                .onEach { s ->
                    if (s == lastState) return@onEach
                    val now = SystemClock.elapsedRealtime()
                    if (lastState != null) Log.i(TAG, "stage $lastState took ${now - stageStart} ms")
                    stageStart = now
                    lastState = s
                }
                .first { it is PipelineState.Completed || it is PipelineState.Failed || it is PipelineState.Cancelled }
        }
        val totalMs = SystemClock.elapsedRealtime() - started
        assertTrue("pipeline ended with $final", final is PipelineState.Completed)
        val result = (final as PipelineState.Completed).result
        Log.i(TAG, "total_ms=$totalMs audio_ms=${result.durationMs} rtf=${"%.3f".format(totalMs.toDouble() / result.durationMs)} " +
            "utterances=${result.utterances.size} speakers=${result.utterances.map { it.speakerId }.distinct().size} warnings=${result.warnings}")
        assertTrue("no utterances", result.utterances.isNotEmpty())

        val out = File(target.getExternalFilesDir(null), "fullcycle.json")
        val exported = AppGraph.get(target).exporter.export(result, Uri.fromFile(out).toString())
        assertTrue("export failed: ${exported.exceptionOrNull()}", exported.isSuccess)
        Log.i(TAG, "exported bytes=${out.length()} duration_ms=${result.durationMs}")
    }

    private companion object {
        const val TAG = "FULL_CYCLE"
    }
}
