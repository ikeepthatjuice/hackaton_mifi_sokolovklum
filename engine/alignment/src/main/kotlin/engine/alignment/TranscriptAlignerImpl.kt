package engine.alignment

import core.contracts.AsrWord
import core.contracts.SpeakerTurn
import core.contracts.TranscriptAligner
import core.contracts.Utterance
import kotlin.math.max
import kotlin.math.min

/**
 * Robust implementation of TranscriptAligner.
 * Matches word-level ASR timestamps to speaker diarization intervals,
 * accounting for voice switches, short answers, overlaps, and pauses.
 *
 * @param maxIntraUtterancePauseMs Maximum pause (ms) allowed between consecutive words of the same speaker
 *                                before splitting into a new Utterance.
 * @param turnProximityToleranceMs Maximum distance (ms) to associate a word with a nearby turn if VAD cut too tight.
 */
class TranscriptAlignerImpl(
    private val maxIntraUtterancePauseMs: Long = 1500L,
    private val turnProximityToleranceMs: Long = 1000L
) : TranscriptAligner {

    override fun align(words: List<AsrWord>, turns: List<SpeakerTurn>): List<Utterance> {
        if (words.isEmpty()) return emptyList()

        // 1. Remove accidental boundary duplicates from chunk stitching
        val deduplicatedWords = deduplicateSeamWords(words)
        if (deduplicatedWords.isEmpty()) return emptyList()

        // 2. Assign each word to the most suitable speaker
        var lastAssignedSpeaker = turns.firstOrNull()?.speakerId ?: "speaker_1"
        val wordAssignments = mutableListOf<WordWithSpeaker>()

        for (word in deduplicatedWords) {
            val assignedSpeaker = findBestSpeakerForWord(word, turns, lastAssignedSpeaker)
            lastAssignedSpeaker = assignedSpeaker
            wordAssignments.add(WordWithSpeaker(word, assignedSpeaker))
        }

        // 3. Group consecutive words of the same speaker into utterances
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
                    // Significant pause within same speaker -> split into new utterance
                    flushGroup()
                    currentGroup.add(word)
                    currentSpeaker = speaker
                } else {
                    currentGroup.add(word)
                }
            } else {
                // Speaker changed mid-segment!
                flushGroup()
                currentGroup.add(word)
                currentSpeaker = speaker
            }
        }

        flushGroup()
        return utterances
    }

    /**
     * Determines which speaker turn has the strongest temporal overlap with the word.
     * If no overlap, finds the closest turn within tolerance, otherwise falls back to lastAssignedSpeaker.
     */
    private fun findBestSpeakerForWord(
        word: AsrWord,
        turns: List<SpeakerTurn>,
        fallbackSpeaker: String
    ): String {
        if (turns.isEmpty()) return fallbackSpeaker

        var bestTurn: SpeakerTurn? = null
        var maxOverlap = 0L

        // Check for direct overlap
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

        // Proximity search if word falls outside VAD speech regions
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

        return if (nearestTurn != null && minDistance <= turnProximityToleranceMs) {
            nearestTurn.speakerId
        } else {
            fallbackSpeaker
        }
    }

    /**
     * Removes duplicate words that might occur at audio block boundaries.
     */
    private fun deduplicateSeamWords(words: List<AsrWord>): List<AsrWord> {
        if (words.size <= 1) return words

        val result = mutableListOf<AsrWord>()
        result.add(words.first())

        for (i in 1 until words.size) {
            val prev = result.last()
            val curr = words[i]

            val isDuplicate = curr.text.equals(prev.text, ignoreCase = true) &&
                    (curr.startMs < prev.endMs + 100L && curr.startMs >= prev.startMs)

            if (!isDuplicate) {
                result.add(curr)
            }
        }

        return result
    }

    /**
     * Formats recognized words into natural sentence text, attaching punctuation properly.
     */
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

