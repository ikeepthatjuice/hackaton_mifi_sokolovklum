package engine.alignment

import core.contracts.AsrWord
import core.contracts.SpeakerTurn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptAlignerTest {

    private val aligner = TranscriptAlignerImpl()

    @Test
    fun `test speaker change mid-sentence splits words into two distinct utterances`() {
        val words = listOf(
            AsrWord("Привет,", startMs = 1000, endMs = 1400),
            AsrWord("как", startMs = 1450, endMs = 1700),
            AsrWord("дела,", startMs = 1750, endMs = 2100),
            AsrWord("отлично!", startMs = 2150, endMs = 2700)
        )

        val turns = listOf(
            SpeakerTurn("speaker_1", startMs = 950, endMs = 1720),
            SpeakerTurn("speaker_2", startMs = 1730, endMs = 2800)
        )

        val utterances = aligner.align(words, turns)

        assertEquals(2, utterances.size)

        assertEquals("speaker_1", utterances[0].speakerId)
        assertEquals("Привет, как", utterances[0].rawText)
        assertEquals(1000L, utterances[0].startMs)
        assertEquals(1700L, utterances[0].endMs)

        assertEquals("speaker_2", utterances[1].speakerId)
        assertEquals("дела, отлично!", utterances[1].rawText)
        assertEquals(1750L, utterances[1].startMs)
        assertEquals(2700L, utterances[1].endMs)
    }

    @Test
    fun `test short answers like yes and no are correctly associated with speaker`() {
        val words = listOf(
            AsrWord("Вы", startMs = 1000, endMs = 1200),
            AsrWord("согласны?", startMs = 1250, endMs = 1800),
            AsrWord("Да.", startMs = 2300, endMs = 2600),
            AsrWord("Хорошо.", startMs = 3100, endMs = 3600)
        )

        val turns = listOf(
            SpeakerTurn("speaker_1", startMs = 900, endMs = 1900),
            SpeakerTurn("speaker_2", startMs = 2200, endMs = 2700),
            SpeakerTurn("speaker_1", startMs = 3000, endMs = 3700)
        )

        val utterances = aligner.align(words, turns)

        assertEquals(3, utterances.size)
        assertEquals("speaker_1", utterances[0].speakerId)
        assertEquals("Вы согласны?", utterances[0].rawText)

        assertEquals("speaker_2", utterances[1].speakerId)
        assertEquals("Да.", utterances[1].rawText)
        assertEquals(2300L, utterances[1].startMs)
        assertEquals(2600L, utterances[1].endMs)

        assertEquals("speaker_1", utterances[2].speakerId)
        assertEquals("Хорошо.", utterances[2].rawText)
    }

    @Test
    fun `test long pause within same speaker splits into separate utterances`() {
        val words = listOf(
            AsrWord("Первая", startMs = 1000, endMs = 1400),
            AsrWord("мысль.", startMs = 1450, endMs = 1800),
            AsrWord("Вторая", startMs = 5000, endMs = 5400),
            AsrWord("мысль.", startMs = 5450, endMs = 5800)
        )

        val turns = listOf(
            SpeakerTurn("speaker_1", startMs = 900, endMs = 2000),
            SpeakerTurn("speaker_1", startMs = 4900, endMs = 6000)
        )

        val utterances = aligner.align(words, turns)

        assertEquals(2, utterances.size)
        assertEquals("speaker_1", utterances[0].speakerId)
        assertEquals("Первая мысль.", utterances[0].rawText)
        assertEquals(1000L, utterances[0].startMs)
        assertEquals(1800L, utterances[0].endMs)

        assertEquals("speaker_1", utterances[1].speakerId)
        assertEquals("Вторая мысль.", utterances[1].rawText)
        assertEquals(5000L, utterances[1].startMs)
        assertEquals(5800L, utterances[1].endMs)
    }

    @Test
    fun `test boundary duplicate words are deduplicated`() {
        val words = listOf(
            AsrWord("Спасибо", startMs = 1000, endMs = 1500),
            AsrWord("большое", startMs = 1550, endMs = 2000),
            AsrWord("большое", startMs = 1560, endMs = 2000),
            AsrWord("до", startMs = 2100, endMs = 2300),
            AsrWord("свидания", startMs = 2350, endMs = 2800)
        )

        val turns = listOf(
            SpeakerTurn("speaker_1", startMs = 900, endMs = 3000)
        )

        val utterances = aligner.align(words, turns)

        assertEquals(1, utterances.size)
        assertEquals("Спасибо большое до свидания", utterances[0].rawText)
    }

    @Test
    fun `test empty words input produces empty utterances list`() {
        val utterances = aligner.align(emptyList(), emptyList())
        assertTrue(utterances.isEmpty())
    }

    @Test
    fun `test words without turns fall back to default speaker without crashing`() {
        val words = listOf(
            AsrWord("Привет", startMs = 1000, endMs = 1500)
        )

        val utterances = aligner.align(words, emptyList())

        assertEquals(1, utterances.size)
        assertEquals("speaker_1", utterances[0].speakerId)
        assertEquals("Привет", utterances[0].rawText)
    }

    @Test
    fun `test word before first turn is assigned to first turn speaker and not dropped`() {
        val words = listOf(
            AsrWord("Привет,", startMs = 200, endMs = 600),
            AsrWord("мир!", startMs = 2000, endMs = 2500)
        )
        val turns = listOf(
            SpeakerTurn("speaker_1", startMs = 1900, endMs = 3000)
        )

        val utterances = aligner.align(words, turns)

        val allWords = utterances.flatMap { it.rawText.split(" ") }
        assertTrue("Word before first turn must be kept", allWords.contains("Привет,"))
        assertTrue("Word in turn must be kept", allWords.contains("мир!"))
        assertEquals("speaker_1", utterances[0].speakerId)
    }

    @Test
    fun `test word after last turn is assigned to last turn speaker and not dropped`() {
        val words = listOf(
            AsrWord("Начало", startMs = 1000, endMs = 1500),
            AsrWord("конец", startMs = 5000, endMs = 5500)
        )
        val turns = listOf(
            SpeakerTurn("speaker_2", startMs = 900, endMs = 2000)
        )

        val utterances = aligner.align(words, turns)

        val allWords = utterances.flatMap { it.rawText.split(" ") }
        assertTrue("Word after last turn must be kept", allWords.contains("конец"))
        assertEquals("speaker_2", utterances.last().speakerId)
    }

    @Test
    fun `test word in pause between turns is assigned to nearest turn speaker and not dropped`() {
        val words = listOf(
            AsrWord("Слово1", startMs = 1000, endMs = 1400),
            AsrWord("В_паузе_к_первому", startMs = 2200, endMs = 2600), // dist to turn 1 = 200ms, to turn 2 = 3400ms
            AsrWord("В_паузе_ко_второму", startMs = 5300, endMs = 5700), // dist to turn 1 = 3300ms, to turn 2 = 300ms
            AsrWord("Слово2", startMs = 6100, endMs = 6500)
        )
        val turns = listOf(
            SpeakerTurn("speaker_1", startMs = 900, endMs = 2000),
            SpeakerTurn("speaker_2", startMs = 6000, endMs = 7000)
        )

        val utterances = aligner.align(words, turns)

        val allWords = utterances.flatMap { it.rawText.split(" ") }
        assertEquals(4, allWords.size)
        assertTrue(allWords.contains("В_паузе_к_первому"))
        assertTrue(allWords.contains("В_паузе_ко_второму"))

        val utt1 = utterances.first { it.rawText.contains("В_паузе_к_первому") }
        assertEquals("speaker_1", utt1.speakerId)

        val utt2 = utterances.first { it.rawText.contains("В_паузе_ко_второму") }
        assertEquals("speaker_2", utt2.speakerId)
    }

    @Test
    fun `test natural spoken repetition of same word is not dropped`() {
        val words = listOf(
            AsrWord("да,", startMs = 1000, endMs = 1200),
            AsrWord("да,", startMs = 1250, endMs = 1450)
        )
        val turns = listOf(
            SpeakerTurn("speaker_1", startMs = 900, endMs = 2000)
        )

        val utterances = aligner.align(words, turns)

        assertEquals(1, utterances.size)
        assertEquals("да, да,", utterances[0].rawText)
    }
}

