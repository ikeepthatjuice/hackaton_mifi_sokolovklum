package engine.llm

import core.contracts.TranscriptPostprocessor
import core.contracts.Utterance
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking

enum class PostprocessEvent { INFERENCE_COMPLETED, FORMAT_REJECTED, INPUT_SKIPPED, BATCH_FORMAT_REJECTED }

/** Owns its runtime. Pipeline's AutoCloseable hook releases it after success, timeout or cancel. */
class LocalTranscriptPostprocessor(
    private val generator: TextGenerator,
    private val validator: TranscriptValidator = TranscriptValidator(),
    private val maxChunkChars: Int = 150,
    private val maxOutputTokens: Int = 512,
    private val diagnostic: (PostprocessEvent) -> Unit = {},
    private val maxBatchSize: Int = 12,
    private val maxBatchChars: Int = 600,
) : TranscriptPostprocessor, AutoCloseable {
    companion object {
        /** Use from EngineSpec.load: preparation is outside the pipeline generation budget. */
        suspend fun load(modelFile: java.io.File, expectedSha256: String, threads: Int = 4): LocalTranscriptPostprocessor {
            val runtime = LlamaCppTextGenerator(modelFile, expectedSha256, threads)
            try {
                runtime.prepare()
                return LocalTranscriptPostprocessor(runtime)
            } catch (failure: Throwable) {
                runtime.close()
                throw failure
            }
        }
    }

    private val closed = AtomicBoolean(false)

    init {
        require(maxChunkChars in 32..1000)
        require(maxOutputTokens in 32..1024)
        require(maxBatchSize in 1..12)
        require(maxBatchChars in 64..1000)
    }

    override suspend fun process(utterances: List<Utterance>): List<Utterance> {
        check(!closed.get()) { "Postprocessor closed" }
        val input = utterances.toList()
        require(input.map { it.id }.distinct().size == input.size) { "Duplicate utterance ID" }
        require(input.all { it.startMs >= 0 && it.endMs > it.startMs }) { "Invalid utterance interval" }
        val parts = input.map { u ->
            if (u.rawText.isBlank()) {
                diagnostic(PostprocessEvent.INPUT_SKIPPED)
                listOf(u.rawText)
            } else chunks(u.rawText)
        }
        val output = parts.map { it.toMutableList() }
        val rejected = BooleanArray(input.size)
        data class Work(val row: Int, val part: Int, val raw: String)
        val work = parts.flatMapIndexed { row, pieces ->
            if (input[row].rawText.isBlank()) emptyList()
            else pieces.mapIndexed { part, raw -> Work(row, part, raw) }
        }
        var cursor = 0
        while (cursor < work.size) {
            currentCoroutineContext().ensureActive()
            val batch = mutableListOf(work[cursor++])
            while (cursor < work.size && batch.size < maxBatchSize) {
                // Do not undo the 150-char split by rebatching pieces of the same utterance.
                if (work[cursor].row == batch.last().row) break
                val candidate = batch + work[cursor]
                if (BatchFormat.encode(candidate.map { it.raw }).length > maxBatchChars) break
                batch.add(work[cursor++])
            }
            val packet = if (batch.size == 1) batch.single().raw else BatchFormat.encode(batch.map { it.raw })
            // Failures propagate to the pipeline, which returns all rawText with an error warning.
            val generated = generator.generate(packet, maxOutputTokens)
            currentCoroutineContext().ensureActive()
            diagnostic(PostprocessEvent.INFERENCE_COMPLETED)
            val answers = if (batch.size == 1) mapOf(0 to generated) else {
                val decoded = BatchFormat.decode(generated, batch.size)
                if (decoded.malformed) diagnostic(PostprocessEvent.BATCH_FORMAT_REJECTED)
                decoded.rows
            }
            batch.forEachIndexed { index, item ->
                val answer = answers[index]
                if (answer != null && validator.validateText(item.raw, answer).accepted) {
                    output[item.row][item.part] = answer.trimEnd() + item.raw.takeLastWhile { it.isWhitespace() }
                } else rejected[item.row] = true
            }
        }
        val result = input.mapIndexed { i, u ->
            val candidate = u.copy(displayText = output[i].joinToString(""))
            if (rejected[i] || !validator.validate(listOf(u), listOf(candidate)).accepted) {
                diagnostic(PostprocessEvent.FORMAT_REJECTED)
                u.copy(displayText = u.rawText)
            } else candidate
        }
        currentCoroutineContext().ensureActive()
        check(validator.validate(input, result).accepted) { "Postprocessor invariant failed" }
        return result
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        when (val owned = generator) {
            is LlamaCppTextGenerator -> runBlocking { owned.close() }
            is AutoCloseable -> owned.close()
        }
    }

    private fun chunks(text: String): List<String> {
        val result = mutableListOf<String>()
        var start = 0
        while (text.length - start > maxChunkChars) {
            var end = start + maxChunkChars
            while (end > start && !text[end - 1].isWhitespace()) end--
            require(end > start) { "Unbroken input exceeds LLM chunk limit" }
            result.add(text.substring(start, end))
            start = end
        }
        if (start < text.length) result.add(text.substring(start))
        return result
    }
}
