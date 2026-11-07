package engine.llm.spike

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ScrollView
import android.util.Log
import engine.llm.LlamaCppTextGenerator
import engine.llm.LlmException
import engine.llm.TranscriptValidator
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*

/** Dedicated runtime experiment. It never links whisper or depends on unmerged DTOs. */
class SpikeActivity : Activity() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
    private var runtime: LlamaCppTextGenerator?=null
    private val latestRaw=AtomicReference("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val status=TextView(this).apply { text="Включите авиарежим. Тест использует только вымышленную реплику." }
        val start=Button(this).apply { text="Запустить локальную LLM" }
        val cancel=Button(this).apply { text="Отменить" }
        val layout=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(32,64,32,32)
            addView(ScrollView(this@SpikeActivity).apply { addView(status) }, LinearLayout.LayoutParams(-1,0,1f))
            addView(start);addView(cancel)
        }
        setContentView(layout)
        var job: Job?=null
        start.setOnClickListener {
            if (job?.isActive==true) return@setOnClickListener
            start.isEnabled=false
            status.text="Подготовка локальных весов и inference…"
            job=scope.launch {
                try {
                    val file=withContext(Dispatchers.IO) {
                        val destination=File(filesDir,MODEL_NAME)
                        if (!destination.isFile) {
                            val partial=File(filesDir,"model.partial")
                            try {
                                assets.open(MODEL_NAME).use { input -> partial.outputStream().use { output -> input.copyTo(output) } }
                                check(partial.renameTo(destination))
                            } finally { partial.delete() }
                        }
                        destination
                    }
                    latestRaw.set("")
                    val engine=runtime ?: LlamaCppTextGenerator(file,MODEL_SHA,rawResponse={ raw ->
                        latestRaw.set(raw)
                        // This APK has no text input: the only source is SYNTHETIC_TEXT below.
                        raw.chunked(1000).forEachIndexed { i, part -> Log.i("LLM_SPIKE_RAW", "synthetic part=$i: $part") }
                    }).also { runtime=it }
                    val elapsed=android.os.SystemClock.elapsedRealtime()
                    val output=withTimeout(120_000) { engine.generate(SYNTHETIC_TEXT,256) }
                    val decision=TranscriptValidator().validateText(SYNTHETIC_TEXT,output)
                    val display=if(decision.accepted) output else SYNTHETIC_TEXT
                    Log.i("LLM_SPIKE_GUARD", "accepted=${decision.accepted} reason=${decision.reason} changed=${output!=SYNTHETIC_TEXT}")
                    status.text="Q4_K_M, русский промпт v2. Inference: ${android.os.SystemClock.elapsedRealtime()-elapsed} мс.\n\nВход: $SYNTHETIC_TEXT\n\nСЫРОЙ ответ до очистки/валидатора: ${latestRaw.get()}\n\nПосле удаления think: $output\n\nВалидатор: ${decision.accepted}, ${decision.reason}\n\nФинальный displayText: $display\n\nТолько синтетика; общий pipeline отдельно."
                } catch (cancelled: CancellationException) {
                    status.text="Обработка отменена"
                    throw cancelled
                } catch (error: LlmException) {
                    status.text="Ошибка LLM: ${error.code}"
                } catch (_: Exception) {
                    status.text="Тест не выполнен. Проверьте, что разрешённые веса включены в assets APK."
                } finally { start.isEnabled=true }
            }
        }
        cancel.setOnClickListener { job?.cancel() }
    }

    override fun onDestroy() {
        scope.cancel()
        // close waits for the cancelled native call before releasing its model.
        val old=runtime
        CoroutineScope(Dispatchers.IO).launch { old?.close() }
        super.onDestroy()
    }

    companion object {
        private const val MODEL_NAME="Qwen3-0.6B-Q4_K_M.gguf"
        private const val MODEL_SHA="2883b98c1396be553c252b27382cae54baad12b4087e6bec86af024bd412afa9"
        private const val SYNTHETIC_TEXT="мы не переносим встречу нужно 12 папок"
    }
}
