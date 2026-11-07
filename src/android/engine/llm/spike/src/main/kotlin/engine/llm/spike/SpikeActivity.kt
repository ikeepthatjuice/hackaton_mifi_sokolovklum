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
import engine.llm.TextGenerator
import engine.llm.LocalTranscriptPostprocessor
import core.contracts.Utterance
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
        val batchStart=Button(this).apply { text="Батч 12 реплик / бюджет 18 с" }
        var batchMode=false
        val cancel=Button(this).apply { text="Отменить" }
        val layout=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(32,64,32,32)
            addView(ScrollView(this@SpikeActivity).apply { addView(status) }, LinearLayout.LayoutParams(-1,0,1f))
            addView(start);addView(batchStart);addView(cancel)
        }
        setContentView(layout)
        var job: Job?=null
        start.setOnClickListener {
            if (job?.isActive==true) return@setOnClickListener
            start.isEnabled=false
            batchStart.isEnabled=false
            val isBatch=batchMode
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
                    val prepareStart=android.os.SystemClock.elapsedRealtime()
                    engine.prepare() // model checksum/load explicitly outside generation budget
                    val prepareMs=android.os.SystemClock.elapsedRealtime()-prepareStart
                    val elapsed=android.os.SystemClock.elapsedRealtime()
                    if (isBatch) {
                        val input=SYNTHETIC_ROWS.mapIndexed { i, text ->
                            Utterance("synthetic_$i","speaker_1",i*5000L,i*5000L+4000,text,text)
                        }
                        // The activity owns runtime; this wrapper does not close it between warm runs.
                        val processor=LocalTranscriptPostprocessor(TextGenerator { text, cap -> engine.generate(text,cap) })
                        val result=withTimeoutOrNull(18_000) { processor.process(input) }
                        val changed=result?.count { it.displayText != it.rawText } ?: 0
                        val valid=result?.let { TranscriptValidator().validate(input,it).accepted } ?: false
                        val generationMs=android.os.SystemClock.elapsedRealtime()-elapsed
                        Log.i("LLM_SPIKE_GUARD","synthetic batch=12 prepareMs=$prepareMs generationMs=$generationMs changed=$changed valid=$valid timeout=${result==null}")
                        status.text="KV cache, 4 threads, prefill512. Подготовка: $prepareMs мс. Генерация: $generationMs мс.\n\nСинтетический текст 12 реплик, бюджет 18 с (30% от условной минуты). Оформлено $changed/12. Guard=$valid. Timeout=${result==null}.\n\n${result?.joinToString("\n") { it.displayText } ?: "Показан rawText: лимит времени LLM"}\n\nЭто не прогон реального аудио. Для warm нажмите батч повторно."
                    } else {
                        val output=withTimeout(120_000) { engine.generate(SYNTHETIC_TEXT,256) }
                        val decision=TranscriptValidator().validateText(SYNTHETIC_TEXT,output)
                        val display=if(decision.accepted) output else SYNTHETIC_TEXT
                        Log.i("LLM_SPIKE_GUARD", "accepted=${decision.accepted} reason=${decision.reason} changed=${output!=SYNTHETIC_TEXT} prepareMs=$prepareMs generationMs=${android.os.SystemClock.elapsedRealtime()-elapsed}")
                        status.text="Q4 KV cache, 4 threads, prefill512. Подготовка: $prepareMs мс. Генерация: ${android.os.SystemClock.elapsedRealtime()-elapsed} мс.\n\nВход: $SYNTHETIC_TEXT\n\nRAW: ${latestRaw.get()}\n\nОчищено: $output\n\nGuard: ${decision.accepted}, ${decision.reason}\n\nFinal: $display\n\nДля warm нажмите повторно."
                    }
                } catch (cancelled: CancellationException) {
                    status.text="Обработка отменена"
                    throw cancelled
                } catch (error: LlmException) {
                    status.text="Ошибка LLM: ${error.code}"
                } catch (_: Exception) {
                    status.text="Тест не выполнен. Проверьте, что разрешённые веса включены в assets APK."
                } finally { start.isEnabled=true;batchStart.isEnabled=true;batchMode=false }
            }
        }
        batchStart.setOnClickListener { batchMode=true;start.performClick() }
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
        private val SYNTHETIC_ROWS=listOf(
            "мы не переносим встречу нужно 12 папок", "олег завтра позвонит марине", "да", "нет",
            "анна принесла 15 листов", "встреча в 12 часов", "нет не 6 папок", "мы будем завтра",
            "иван пришёл утром", "мария позвонила олегу", "нужно 05 папок", "сегодня всё готово",
        )
        private const val SYNTHETIC_TEXT="мы не переносим встречу нужно 12 папок"
    }
}
