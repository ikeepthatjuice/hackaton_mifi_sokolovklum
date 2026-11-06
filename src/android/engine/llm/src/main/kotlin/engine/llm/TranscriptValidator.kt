package engine.llm

import core.contracts.Utterance
import java.text.Normalizer
import java.util.Locale

/** Conservative formatting guard, independent of inference. Never logs transcript content. */
class TranscriptValidator {
    enum class Rejection { STRUCTURE, WORDS, NUMBERS, SYMBOLS, LENGTH }
    data class Decision(val accepted: Boolean, val reason: Rejection? = null)

    fun validate(original: List<Utterance>, candidate: List<Utterance>): Decision {
        if (original.size != candidate.size) return reject(Rejection.STRUCTURE)
        for ((before, after) in original.zip(candidate)) {
            if (before.id != after.id || before.speakerId != after.speakerId ||
                before.startMs != after.startMs || before.endMs != after.endMs ||
                before.rawText != after.rawText) return reject(Rejection.STRUCTURE)
            val text = validateText(before.rawText, after.displayText)
            if (!text.accepted) return text
        }
        return Decision(true)
    }

    fun validateText(raw: String, formatted: String): Decision {
        if (formatted.length > raw.length * 2L + 64L) return reject(Rejection.LENGTH)
        val before = normalized(raw)
        val after = normalized(formatted)
        // Exact ordered words preserve numbers written as words, negations, ё and script.
        if (words.findAll(before).map { it.value }.toList() !=
            words.findAll(after).map { it.value }.toList()) return reject(Rejection.WORDS)
        // Do not treat a changed decimal separator, sign, date or unit symbol as punctuation.
        if (numbers.findAll(before).map { it.value }.toList() !=
            numbers.findAll(after).map { it.value }.toList()) return reject(Rejection.NUMBERS)
        if (protectedSymbols(before) != protectedSymbols(after)) return reject(Rejection.SYMBOLS)
        return Decision(true)
    }

    private fun protectedSymbols(text: String): String = text.filter {
        !it.isLetterOrDigit() && !it.isWhitespace() &&
            Character.getType(it) != Character.NON_SPACING_MARK.toInt() &&
            it !in ".,!?;:…\"«»"
    }

    private fun normalized(text: String) = Normalizer.normalize(text, Normalizer.Form.NFC).lowercase(Locale.ROOT)
    private fun reject(reason: Rejection) = Decision(false, reason)

    companion object {
        private val words = Regex("[\\p{L}\\p{M}\\p{N}]+(?:[-'’][\\p{L}\\p{M}\\p{N}]+)*")
        private val numbers = Regex("[+\\-−]?\\p{N}+(?:[.,:/\\-]\\p{N}+)*(?:\\s?[%‰₽$€])?")
    }
}
