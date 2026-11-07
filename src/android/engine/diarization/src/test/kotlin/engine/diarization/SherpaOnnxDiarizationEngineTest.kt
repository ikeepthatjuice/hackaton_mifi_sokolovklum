package engine.diarization

import core.contracts.AudioInput
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SherpaOnnxDiarizationEngineTest {

    @Test
    fun `test single speaker gets speaker_1`() {
        val engine = SherpaOnnxDiarizationEngine()
        val segments = listOf(
            SherpaOnnxSegment(clusterId = 0, startMs = 500, endMs = 2500),
            SherpaOnnxSegment(clusterId = 0, startMs = 3000, endMs = 5500)
        )

        val turns = engine.mapSegmentsToSpeakerTurns(segments, maxDurationMs = 6000)

        assertEquals(2, turns.size)
        assertEquals("speaker_1", turns[0].speakerId)
        assertEquals("speaker_1", turns[1].speakerId)
        assertEquals(500L, turns[0].startMs)
        assertEquals(2500L, turns[0].endMs)
    }

    @Test
    fun `test two speakers alternate and retain respective IDs`() {
        val engine = SherpaOnnxDiarizationEngine()
        val segments = listOf(
            SherpaOnnxSegment(clusterId = 5, startMs = 100, endMs = 1500),
            SherpaOnnxSegment(clusterId = 12, startMs = 2000, endMs = 3500),
            SherpaOnnxSegment(clusterId = 5, startMs = 4000, endMs = 5000),
            SherpaOnnxSegment(clusterId = 12, startMs = 5200, endMs = 6000)
        )

        val turns = engine.mapSegmentsToSpeakerTurns(segments, maxDurationMs = 7000)

        assertEquals(4, turns.size)
        assertEquals("speaker_1", turns[0].speakerId)
        assertEquals("speaker_2", turns[1].speakerId)
        assertEquals("speaker_1", turns[2].speakerId)
        assertEquals("speaker_2", turns[3].speakerId)
    }

    @Test
    fun `test returning first voice retains original ID across multi-speaker conversation`() {
        val engine = SherpaOnnxDiarizationEngine()
        val segments = listOf(
            SherpaOnnxSegment(clusterId = 100, startMs = 1000, endMs = 3000),
            SherpaOnnxSegment(clusterId = 200, startMs = 3500, endMs = 5000),
            SherpaOnnxSegment(clusterId = 300, startMs = 5500, endMs = 7000),
            SherpaOnnxSegment(clusterId = 100, startMs = 7500, endMs = 9000)
        )

        val turns = engine.mapSegmentsToSpeakerTurns(segments, maxDurationMs = 10000)

        assertEquals(4, turns.size)
        assertEquals("speaker_1", turns[0].speakerId)
        assertEquals("speaker_2", turns[1].speakerId)
        assertEquals("speaker_3", turns[2].speakerId)
        assertEquals("speaker_1", turns[3].speakerId)
    }

    @Test
    fun `test dynamic cluster count without pre-fixing number of participants`() {
        val engine = SherpaOnnxDiarizationEngine()
        val segments = listOf(
            SherpaOnnxSegment(clusterId = 10, startMs = 100, endMs = 500),
            SherpaOnnxSegment(clusterId = 20, startMs = 600, endMs = 1200),
            SherpaOnnxSegment(clusterId = 30, startMs = 1500, endMs = 2000),
            SherpaOnnxSegment(clusterId = 40, startMs = 2500, endMs = 3000)
        )

        val turns = engine.mapSegmentsToSpeakerTurns(segments, maxDurationMs = 4000)

        assertEquals(4, turns.size)
        assertEquals("speaker_1", turns[0].speakerId)
        assertEquals("speaker_2", turns[1].speakerId)
        assertEquals("speaker_3", turns[2].speakerId)
        assertEquals("speaker_4", turns[3].speakerId)
    }

    @Test
    fun `test pauses and silence do not shift time coordinates`() = runBlocking {
        val segments = listOf(
            SherpaOnnxSegment(clusterId = 1, startMs = 1000, endMs = 2000),
            SherpaOnnxSegment(clusterId = 2, startMs = 10000, endMs = 11500)
        )

        val engine = SherpaOnnxDiarizationEngine(
            mockSegmentProvider = { segments }
        )

        val turns = engine.diarize(
            AudioInput(pcmFilePath = "pause.pcm", sampleRateHz = 16000, channels = 1, durationMs = 15000)
        )

        assertEquals(2, turns.size)
        assertEquals(1000L, turns[0].startMs)
        assertEquals(2000L, turns[0].endMs)

        assertEquals(10000L, turns[1].startMs)
        assertEquals(11500L, turns[1].endMs)
    }

    @Test
    fun `test empty diarization input produces empty turns without error`() = runBlocking {
        val engine = SherpaOnnxDiarizationEngine(
            mockSegmentProvider = { emptyList() }
        )

        val turns = engine.diarize(
            AudioInput(pcmFilePath = "silent.pcm", sampleRateHz = 16000, channels = 1, durationMs = 5000)
        )

        assertTrue(turns.isEmpty())
    }
}
