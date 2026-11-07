package engine.asr

import core.contracts.AsrEngine
import core.contracts.AsrWord
import core.contracts.AudioInput
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GigaAmAsrEngineTest {

    @Test
    fun `test bpe tokens reconstructed into words with punctuation attached`() {
        val engine = GigaAmAsrEngine()
        val tokens = listOf(
            GigaAmToken(text = "\u2581При", timestampMs = 200L),
            GigaAmToken(text = "вет", timestampMs = 400L),
            GigaAmToken(text = ",", timestampMs = 500L),
            GigaAmToken(text = "\u2581как", timestampMs = 700L),
            GigaAmToken(text = "\u2581де", timestampMs = 950L),
            GigaAmToken(text = "ла", timestampMs = 1100L),
            GigaAmToken(text = "?", timestampMs = 1250L)
        )

        val words = engine.reconstructWords(
            tokens = tokens,
            chunkStartOffsetMs = 0L,
            chunkDurationMs = 2000L
        )

        assertEquals(3, words.size)
        assertEquals("Привет,", words[0].text)
        assertEquals("как", words[1].text)
        assertEquals("дела?", words[2].text)

        // Monotonicity verification
        assertTrue("Word 0 start >= 0", words[0].startMs >= 0L)
        assertTrue("Word 0 end > start", words[0].endMs > words[0].startMs)
        assertTrue("Word 1 start >= Word 0 end", words[1].startMs >= words[0].endMs)
        assertTrue("Word 1 end > start", words[1].endMs > words[1].startMs)
        assertTrue("Word 2 start >= Word 1 end", words[2].startMs >= words[1].endMs)
        assertTrue("Word 2 end > start", words[2].endMs > words[2].startMs)
        assertTrue("Word 2 end <= duration", words[2].endMs <= 2000L)
    }

    @Test
    fun `test isolated whitespace token does not glue words and marks word boundary`() {
        val engine = GigaAmAsrEngine()

        // 1. [ ▁ , слово ] -> sequence starting with isolated space token
        val leadingSpaceTokens = listOf(
            GigaAmToken(text = "\u2581", timestampMs = 100L),
            GigaAmToken(text = "слово", timestampMs = 300L)
        )
        val leadingWords = engine.reconstructWords(
            tokens = leadingSpaceTokens,
            chunkStartOffsetMs = 0L,
            chunkDurationMs = 1000L
        )
        assertEquals(1, leadingWords.size)
        assertEquals("слово", leadingWords[0].text)

        // 2. [ слово , ▁ , слово ] -> isolated space token between words must split them, not glue
        val separatedTokens = listOf(
            GigaAmToken(text = "слово", timestampMs = 100L),
            GigaAmToken(text = "\u2581", timestampMs = 400L),
            GigaAmToken(text = "слово", timestampMs = 600L)
        )
        val separatedWords = engine.reconstructWords(
            tokens = separatedTokens,
            chunkStartOffsetMs = 0L,
            chunkDurationMs = 1000L
        )
        assertEquals(2, separatedWords.size)
        assertEquals("слово", separatedWords[0].text)
        assertEquals("слово", separatedWords[1].text)
        assertTrue("Second word starts after first ends", separatedWords[1].startMs >= separatedWords[0].endMs)
    }

    @Test
    fun `test chunk offset is applied to word timestamps`() {
        val engine = GigaAmAsrEngine()
        val tokens = listOf(
            GigaAmToken(text = "\u2581Привет", timestampMs = 500L),
            GigaAmToken(text = "!", timestampMs = 900L)
        )

        val words = engine.reconstructWords(
            tokens = tokens,
            chunkStartOffsetMs = 18000L,
            chunkDurationMs = 5000L
        )

        assertEquals(1, words.size)
        assertEquals("Привет!", words[0].text)
        assertEquals(18500L, words[0].startMs)
        assertTrue(words[0].endMs > 18500L)
    }

    @Test
    fun `test optimal split point selects quiet dip between 15s and 20s`() {
        val engine = GigaAmAsrEngine()
        val sampleRate = 16000
        val totalSamples = 20 * sampleRate // 20 seconds = 320,000 samples
        val samples = FloatArray(totalSamples) { 0.8f } // loud speech

        // Create a quiet silence dip at 17 seconds (sample index 272,000) of length 200ms
        val dipStart = 17 * sampleRate
        val dipEnd = dipStart + (sampleRate / 5)
        for (i in dipStart until dipEnd) {
            samples[i] = 0.001f
        }

        val searchStart = 15 * sampleRate
        val searchEnd = 20 * sampleRate
        val splitPoint = engine.findOptimalSplitPoint(samples, searchStart, searchEnd)

        // Split point should be inside the quiet dip
        assertTrue("Split point must be near silence dip", splitPoint in dipStart..dipEnd)
    }

    @Test
    fun `test language routing non-ru delegates to whisper fallback`() = runBlocking {
        var whisperCalled = false
        val mockWhisper = object : AsrEngine {
            override suspend fun transcribe(input: AudioInput): List<AsrWord> {
                whisperCalled = true
                return listOf(AsrWord(text = "Hello", startMs = 100L, endMs = 500L))
            }
        }

        val engine = GigaAmAsrEngine(
            config = GigaAmConfig(language = "en"),
            whisperFallback = mockWhisper
        )

        val result = engine.transcribe(
            AudioInput(pcmFilePath = "dummy.pcm", sampleRateHz = 16000, durationMs = 2000L)
        )

        assertTrue("Whisper fallback should be invoked for non-ru language", whisperCalled)
        assertEquals(1, result.size)
        assertEquals("Hello", result[0].text)
    }

    @Test
    fun `test language routing ru uses gigaam`() = runBlocking {
        val engine = GigaAmAsrEngine(
            config = GigaAmConfig(language = "ru"),
            mockTokenProvider = {
                listOf(
                    GigaAmToken(text = "\u2581Добрый", timestampMs = 100L),
                    GigaAmToken(text = "\u2581день", timestampMs = 400L),
                    GigaAmToken(text = ".", timestampMs = 600L)
                )
            }
        )

        val result = engine.transcribe(
            AudioInput(pcmFilePath = "dummy.pcm", sampleRateHz = 16000, durationMs = 2000L)
        )

        assertEquals(2, result.size)
        assertEquals("Добрый", result[0].text)
        assertEquals("день.", result[1].text)
        assertTrue(result[1].startMs >= result[0].endMs)
    }

    @Test
    fun `test empty or zero duration input produces empty list`() = runBlocking {
        val engine = GigaAmAsrEngine()
        val result = engine.transcribe(
            AudioInput(pcmFilePath = "nonexistent.pcm", sampleRateHz = 16000, durationMs = 0L)
        )
        assertTrue(result.isEmpty())
    }
}

