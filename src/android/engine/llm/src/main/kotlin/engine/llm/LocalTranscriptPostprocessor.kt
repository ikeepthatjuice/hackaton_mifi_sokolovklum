package engine.llm

import core.contracts.TranscriptPostprocessor
import core.contracts.Utterance
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

enum class PostprocessEvent { INFERENCE_COMPLETED, FORMAT_REJECTED, INPUT_SKIPPED }

class LocalTranscriptPostprocessor(
    private val generator: TextGenerator,
    private val validator: TranscriptValidator = TranscriptValidator(),
    private val maxChunkChars: Int = 400,
    private val maxOutputTokens: Int = 512,
    // Diagnostics carry only enum values; the caller may aggregate these into job warnings.
    private val diagnostic: (PostprocessEvent) -> Unit = {}
) : TranscriptPostprocessor {
    init {
        require(maxChunkChars in 32..1000)
        require(maxOutputTokens in 32..1024)
    }

    override suspend fun process(utterances: List<Utterance>): List<Utterance> {
        // Snapshot prevents caller mutation during suspension; never hand metadata to the model.
        val input = utterances.toList()
        require(input.map { it.id }.distinct().size == input.size) { "Duplicate utterance ID" }
        require(input.all { it.startMs >= 0 && it.endMs > it.startMs }) { "Invalid utterance interval" }
        val result = input.map { utterance ->
            currentCoroutineContext().ensureActive()
            if (utterance.rawText.isBlank()) {
                diagnostic(PostprocessEvent.INPUT_SKIPPED)
                utterance.copy(displayText = utterance.rawText)
            } else {
                val formatted = chunks(utterance.rawText).joinGenerated()
                val candidate = utterance.copy(displayText = formatted)
                if (validator.validate(listOf(utterance), listOf(candidate)).accepted) candidate
                else {
                    diagnostic(PostprocessEvent.FORMAT_REJECTED)
                    utterance.copy(displayText = utterance.rawText)
                }
            }
        }
        currentCoroutineContext().ensureActive()
        check(validator.validate(input, result).accepted) { "Postprocessor invariant failed" }
        return result
    }

    private suspend fun List<String>.joinGenerated(): String {
        val outputs = ArrayList<String>(size)
        for (chunk in this) {
            currentCoroutineContext().ensureActive()
            // Runtime/load/decode failures propagate. Missing LLM must not look like success.
            val generated = try {
                generator.generate(chunk, maxOutputTokens)
            } catch (cancelled: CancellationException) {
                throw cancelled
            }
            currentCoroutineContext().ensureActive()
            diagnostic(PostprocessEvent.INFERENCE_COMPLETED)
            if (validator.validateText(chunk, generated).accepted) {
                // Models often trim whitespace: retain separators between chunks verbatim.
                outputs.add(generated.trimEnd() + chunk.takeLastWhile { it.isWhitespace() })
            }
            else {
                diagnostic(PostprocessEvent.FORMAT_REJECTED)
                outputs.add(chunk)
            }
        }
        return outputs.joinToString("")
    }

    /** Split only on whitespace, retaining it verbatim; never split a word/number. */
    private fun chunks(text: String): List<String> {
        val result = mutableListOf<String>()
        var start = 0
        while (text.length - start > maxChunkChars) {
            var end = start + maxChunkChars
            while (end > start && !text[end - 1].isWhitespace()) end--
            // An overlong unbroken token is handled as a typed failure, not sent unbounded.
            require(end > start) { "Unbroken input exceeds LLM chunk limit" }
            result.add(text.substring(start, end))
            start = end
        }
        if (start < text.length) result.add(text.substring(start))
        return result
    }
}
