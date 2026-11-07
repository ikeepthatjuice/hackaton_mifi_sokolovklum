package engine.asr

import core.contracts.AudioInput
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WhisperAsrEngineTest {

    @Test
    fun `test token aggregation into words with correct millisecond timestamps`() {
        val engine = WhisperAsrEngine()

        val tokens = listOf(
            WhisperToken(" Доб", startMs = 500, endMs = 700),
            WhisperToken("рый", startMs = 700, endMs = 950),
            WhisperToken(" день", startMs = 1100, endMs = 1450),
            WhisperToken(",", startMs = 1450, endMs = 1500),
            WhisperToken(" как", startMs = 1800, endMs = 2050),
            WhisperToken(" дела", startMs = 2100, endMs = 2500),
            WhisperToken("?", startMs = 2500, endMs = 2550)
        )

        val words = engine.aggregateTokensToWords(tokens, maxDurationMs = 5000L)

        assertEquals(4, words.size)

        assertEquals("Добрый", words[0].text)
        assertEquals(500L, words[0].startMs)
        assertEquals(950L, words[0].endMs)

        assertEquals("день,", words[1].text)
        assertEquals(1100L, words[1].startMs)
        assertEquals(1500L, words[1].endMs)

        assertEquals("как", words[2].text)
        assertEquals(1800L, words[2].startMs)
        assertEquals(2050L, words[2].endMs)

        assertEquals("дела?", words[3].text)
        assertEquals(2100L, words[3].startMs)
        assertEquals(2550L, words[3].endMs)

        for (w in words) {
            assertTrue("startMs must be strictly less than endMs", w.startMs < w.endMs)
        }
    }

    @Test
    fun `test short answers like yes and no retain accurate short timestamps`() {
        val engine = WhisperAsrEngine()

        val tokens = listOf(
            WhisperToken(" Да", startMs = 3200, endMs = 3450),
            WhisperToken(".", startMs = 3450, endMs = 3500),
            WhisperToken(" Нет", startMs = 5100, endMs = 5400)
        )

        val words = engine.aggregateTokensToWords(tokens, maxDurationMs = 6000L)

        assertEquals(2, words.size)
        assertEquals("Да.", words[0].text)
        assertEquals(3200L, words[0].startMs)
        assertEquals(3500L, words[0].endMs)

        assertEquals("Нет", words[1].text)
        assertEquals(5100L, words[1].startMs)
        assertEquals(5400L, words[1].endMs)
    }

    @Test
    fun `test pauses and silence do not compress or shift time coordinates`() = runBlocking {
        val mockTokens = listOf(
            WhisperToken(" Раз", startMs = 1000, endMs = 1300),
            WhisperToken(" Два", startMs = 6300, endMs = 6600)
        )

        val engine = WhisperAsrEngine(
            mockTokenProvider = { mockTokens }
        )

        val input = AudioInput(
            pcmFilePath = "dummy.pcm",
            sampleRateHz = 16000,
            channels = 1,
            durationMs = 10000
        )

        val words = engine.transcribe(input)

        assertEquals(2, words.size)
        assertEquals("Раз", words[0].text)
        assertEquals(1000L, words[0].startMs)
        assertEquals(1300L, words[0].endMs)

        assertEquals("Два", words[1].text)
        assertEquals(6300L, words[1].startMs)
        assertEquals(6600L, words[1].endMs)
    }

    @Test
    fun `test synthetic tokens with overlaps special tokens inverted times and out of bounds`() {
        val engine = WhisperAsrEngine()
        val durationMs = 5000L

        val syntheticTokens = listOf(
            WhisperToken("<|startoftranscript|>", startMs = 0, endMs = 100),
            WhisperToken("<|ru|>", startMs = 100, endMs = 200),
            WhisperToken(" При", startMs = 300, endMs = 600),
            WhisperToken("вет", startMs = 600, endMs = 900),
            WhisperToken("[_BEG_]", startMs = 900, endMs = 950),
            // Overlapping word with previous end
            WhisperToken(" мир", startMs = 800, endMs = 1200),
            // Inverted timestamps (t1 < t0)
            WhisperToken(" тест", startMs = 2000, endMs = 1500),
            // Overlapping again
            WhisperToken(" еще", startMs = 1800, endMs = 2500),
            // Out of bounds (past durationMs)
            WhisperToken(" поздно", startMs = 5500, endMs = 6000),
            WhisperToken("<|endoftranscript|>", startMs = 6000, endMs = 6100)
        )

        val words = engine.aggregateTokensToWords(syntheticTokens, maxDurationMs = durationMs)

        assertTrue("Words must not be empty", words.isNotEmpty())

        var prevEnd = 0L
        for (w in words) {
            assertTrue("Text must not contain special tokens: ${w.text}", !w.text.contains("<|") && !w.text.contains("[_"))
            assertTrue("startMs (${w.startMs}) must be < endMs (${w.endMs})", w.startMs < w.endMs)
            assertTrue("startMs (${w.startMs}) must be >= 0", w.startMs >= 0L)
            assertTrue("endMs (${w.endMs}) must be <= durationMs ($durationMs)", w.endMs <= durationMs)
            assertTrue("Timestamps must be non-decreasing: startMs ${w.startMs} >= prevEnd $prevEnd", w.startMs >= prevEnd)
            prevEnd = w.endMs
        }

        assertEquals("Привет", words[0].text)
        assertEquals("мир", words[1].text)
        assertEquals("тест", words[2].text)
        assertEquals("еще", words[3].text)
        // Word 'поздно' must be dropped because start >= durationMs
        assertTrue("Tokens past durationMs must be dropped", words.none { it.text == "поздно" })
    }

    @Test
    fun `test empty tokens produce empty list without crashing`() = runBlocking {
        val engine = WhisperAsrEngine(
            mockTokenProvider = { emptyList() }
        )

        val input = AudioInput(
            pcmFilePath = "silent.pcm",
            sampleRateHz = 16000,
            channels = 1,
            durationMs = 5000
        )

        val words = engine.transcribe(input)
        assertTrue(words.isEmpty())
    }
}

