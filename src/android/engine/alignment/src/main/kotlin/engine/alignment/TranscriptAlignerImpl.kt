package engine.alignment

import core.contracts.AsrWord
import core.contracts.SpeakerTurn
import core.contracts.TranscriptAligner
import core.contracts.Utterance
import kotlin.math.max
import kotlin.math.min

/**
 * Robust implementation of TranscriptAligner.
 */
class TranscriptAlignerImpl(
    private val maxIntraUtterancePauseMs: Long = 1500L,
    private val turnProximityToleranceMs: Long = 1000L
) : TranscriptAligner {

    override fun align(words: List<AsrWord>, turns: List<SpeakerTurn>): List<Utterance> {
        if (words.isEmpty()) return emptyList()

        val deduplicatedWords = deduplicateSeamWords(words)
        if (deduplicatedWords.isEmpty()) return emptyList()

        var lastAssignedSpeaker = turns.firstOrNull()?.speakerId ?: "speaker_1"
        val wordAssignments = mutableListOf<WordWithSpeaker>()

        for (word in deduplicatedWords) {
            val assignedSpeaker = findBestSpeakerForWord(word, turns, lastAssignedSpeaker)
            lastAssignedSpeaker = assignedSpeaker
            wordAssignments.add(WordWithSpeaker(word, assignedSpeaker))
        }

        val utterances = mutableListOf<Utterance>()
        val currentGroup = mutableListOf<AsrWord>()
        var currentSpeaker = ""

        fun flushGroup() {
            if (currentGroup.isNotEmpty() && currentSpeaker.isNotBlank()) {
                val uttId = "utt_${String.format("%04d", utterances.size + 1)}"
                val startMs = currentGroup.first().startMs
                val endMs = currentGroup.last().endMs
                val text = formatWordsToText(currentGroup)

                utterances.add(
                    Utterance(
                        id = uttId,
                        speakerId = currentSpeaker,
                        startMs = startMs,
                        endMs = max(startMs + 1L, endMs),
                        rawText = text,
                        displayText = text
                    )
                )
            }
            currentGroup.clear()
            currentSpeaker = ""
        }

        for (item in wordAssignments) {
            val word = item.word
            val speaker = item.speakerId

            if (currentGroup.isEmpty()) {
                currentGroup.add(word)
                currentSpeaker = speaker
            } else if (currentSpeaker == speaker) {
                val pause = word.startMs - currentGroup.last().endMs
                if (pause > maxIntraUtterancePauseMs) {
                    flushGroup()
                    currentGroup.add(word)
                    currentSpeaker = speaker
                } else {
                    currentGroup.add(word)
                }
            } else {
                flushGroup()
                currentGroup.add(word)
                currentSpeaker = speaker
            }
        }

        flushGroup()
        return utterances
    }

    private fun findBestSpeakerForWord(
        word: AsrWord,
        turns: List<SpeakerTurn>,
        fallbackSpeaker: String
    ): String {
        if (turns.isEmpty()) return fallbackSpeaker

        // 1. Direct temporal overlap: choose turn with maximum intersection
        var bestTurn: SpeakerTurn? = null
        var maxOverlap = 0L

        for (turn in turns) {
            val overlapStart = max(word.startMs, turn.startMs)
            val overlapEnd = min(word.endMs, turn.endMs)
            val overlap = max(0L, overlapEnd - overlapStart)

            if (overlap > maxOverlap) {
                maxOverlap = overlap
                bestTurn = turn
            }
        }

        if (bestTurn != null && maxOverlap > 0L) {
            return bestTurn.speakerId
        }

        // 2. Outside all turn intervals: associate with the temporally closest turn.
        // Words before the first turn, after the last turn, or inside pauses are never dropped.
        var minDistance = Long.MAX_VALUE
        var nearestTurn: SpeakerTurn? = null

        for (turn in turns) {
            val dist = if (word.startMs >= turn.endMs) {
                word.startMs - turn.endMs
            } else if (turn.startMs >= word.endMs) {
                turn.startMs - word.endMs
            } else {
                0L
            }

            if (dist < minDistance) {
                minDistance = dist
                nearestTurn = turn
            }
        }

        return nearestTurn?.speakerId ?: fallbackSpeaker
    }

    private fun deduplicateSeamWords(words: List<AsrWord>): List<AsrWord> {
        if (words.size <= 1) return words

        val result = mutableListOf<AsrWord>()
        result.add(words.first())

        for (i in 1 until words.size) {
            val prev = result.last()
            val curr = words[i]

            // Two words are seam duplicates ONLY if they have identical text AND actually overlap in time.
            // Sequential words (even with identical text like "да, да") must NEVER be dropped.
            val overlapStart = max(curr.startMs, prev.startMs)
            val overlapEnd = min(curr.endMs, prev.endMs)
            val overlap = max(0L, overlapEnd - overlapStart)
            val prevDuration = max(1L, prev.endMs - prev.startMs)

            val isDuplicate = curr.text.equals(prev.text, ignoreCase = true) &&
                    (overlap >= prevDuration / 2 || (curr.startMs == prev.startMs && curr.endMs == prev.endMs))

            if (!isDuplicate) {
                result.add(curr)
            }
        }

        return result
    }

    private fun formatWordsToText(words: List<AsrWord>): String {
        val sb = StringBuilder()
        for (w in words) {
            val text = w.text.trim()
            if (text.isEmpty()) continue

            val isPunctuationOnly = text.length == 1 && text[0] in ".,!?:;-"
            if (sb.isNotEmpty() && !isPunctuationOnly) {
                sb.append(" ")
            }
            sb.append(text)
        }
        return sb.toString()
    }

    private data class WordWithSpeaker(
        val word: AsrWord,
        val speakerId: String
    )
}

