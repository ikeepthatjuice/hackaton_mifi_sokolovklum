package engine.diarization

import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationSegment
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
            OfflineSpeakerDiarizationSegment(start = 0.5f, end = 2.5f, speaker = 0),
            OfflineSpeakerDiarizationSegment(start = 3.0f, end = 5.5f, speaker = 0)
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
            OfflineSpeakerDiarizationSegment(start = 0.1f, end = 1.5f, speaker = 5),
            OfflineSpeakerDiarizationSegment(start = 2.0f, end = 3.5f, speaker = 12),
            OfflineSpeakerDiarizationSegment(start = 4.0f, end = 5.0f, speaker = 5),
            OfflineSpeakerDiarizationSegment(start = 5.2f, end = 6.0f, speaker = 12)
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
            OfflineSpeakerDiarizationSegment(start = 1.0f, end = 3.0f, speaker = 100),
            OfflineSpeakerDiarizationSegment(start = 3.5f, end = 5.0f, speaker = 200),
            OfflineSpeakerDiarizationSegment(start = 5.5f, end = 7.0f, speaker = 300),
            OfflineSpeakerDiarizationSegment(start = 7.5f, end = 9.0f, speaker = 100)
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
            OfflineSpeakerDiarizationSegment(start = 0.1f, end = 0.5f, speaker = 10),
            OfflineSpeakerDiarizationSegment(start = 0.6f, end = 1.2f, speaker = 20),
            OfflineSpeakerDiarizationSegment(start = 1.5f, end = 2.0f, speaker = 30),
            OfflineSpeakerDiarizationSegment(start = 2.5f, end = 3.0f, speaker = 40)
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
            OfflineSpeakerDiarizationSegment(start = 1.0f, end = 2.0f, speaker = 1),
            OfflineSpeakerDiarizationSegment(start = 10.0f, end = 11.5f, speaker = 2)
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
