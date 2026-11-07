package engine.llm.spike

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import engine.llm.LlamaCppTextGenerator
import engine.llm.LlmException
import java.io.File
import kotlinx.coroutines.*

/** Dedicated runtime experiment. It never links whisper or depends on unmerged DTOs. */
class SpikeActivity : Activity() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
    private var runtime: LlamaCppTextGenerator?=null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val status=TextView(this).apply { text="Включите авиарежим. Тест использует только вымышленную реплику." }
        val start=Button(this).apply { text="Запустить локальную LLM" }
        val cancel=Button(this).apply { text="Отменить" }
        val layout=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(32,64,32,32)
            addView(status);addView(start);addView(cancel)
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
                    val engine=runtime ?: LlamaCppTextGenerator(file,MODEL_SHA).also { runtime=it }
                    val elapsed=android.os.SystemClock.elapsedRealtime()
                    val output=withTimeout(120_000) { engine.generate(SYNTHETIC_TEXT,256) }
                    status.text="Настоящий inference завершён за ${android.os.SystemClock.elapsedRealtime()-elapsed} мс.\n\nИсходный текст: $SYNTHETIC_TEXT\n\nОтвет LLM: $output\n\nЭто тест runtime; полный pipeline и качество отдельно не проверены."
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
        private const val MODEL_NAME="Qwen3-0.6B-Q8_0.gguf"
        private const val MODEL_SHA="9465e63a22add5354d9bb4b99e90117043c7124007664907259bd16d043bb031"
        private const val SYNTHETIC_TEXT="мы не переносим встречу нужно 12 папок"
    }
}
