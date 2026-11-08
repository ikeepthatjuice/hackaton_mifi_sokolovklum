package engine.diarization

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import core.contracts.AudioInput
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SherpaOnDeviceTest {

    private val tag = "ENGINE_TEST"

    @Test
    fun testSherpaOnDeviceWithJfkWav() = runBlocking {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val testContext = InstrumentationRegistry.getInstrumentation().context

        val segName = "sherpa-onnx-pyannote-segmentation-3-0.onnx"
        val segFile = resolveOrExtractModel(testContext, appContext, segName)
        if (!segFile.exists() || segFile.length() == 0L) {
            val errorMsg = "MODEL_MISSING $segName"
            Log.e(tag, errorMsg)
            throw AssertionError(errorMsg)
        }

        val embName = "3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx"
        val embFile = resolveOrExtractModel(testContext, appContext, embName)
        if (!embFile.exists() || embFile.length() == 0L) {
            val errorMsg = "MODEL_MISSING $embName"
            Log.e(tag, errorMsg)
            throw AssertionError(errorMsg)
        }

        val pcmFile = extractAndConvertWavToFloat32Pcm(testContext, appContext, "jfk.wav")
        val durationMs = 11000L

        val audioInput = AudioInput(
            pcmFilePath = pcmFile.absolutePath,
            sampleRateHz = 16000,
            channels = 1,
            durationMs = durationMs
        )

        val config = SherpaOnnxDiarizationConfig(
            segmentationModelPath = segFile.absolutePath,
            embeddingModelPath = embFile.absolutePath,
            clusteringThreshold = 0.6f,
            minSpeechDurationMs = 250L,
            minSilenceDurationMs = 500L,
            isFloat32Pcm = true
        )
        val engine = SherpaOnnxDiarizationEngine(config)

        val startTime = System.currentTimeMillis()
        val turns = engine.diarize(audioInput)
        val elapsedMs = System.currentTimeMillis() - startTime

        val distinctSpeakers = turns.map { it.speakerId }.distinct()

        Log.i(tag, "diar turns=${turns.size} speakers=${distinctSpeakers.size} time_ms=$elapsedMs")

        assertTrue("Expected at least 1 SpeakerTurn, got ${turns.size}", turns.isNotEmpty())

        val speakerRegex = Regex("^speaker_\\d+$")
        for (turn in turns) {
            assertTrue("SpeakerId '${turn.speakerId}' must match format 'speaker_N'", speakerRegex.matches(turn.speakerId))
            assertTrue("startMs (${turn.startMs}) must be < endMs (${turn.endMs})", turn.startMs < turn.endMs)
            assertTrue("startMs (${turn.startMs}) must be >= 0", turn.startMs >= 0L)
            assertTrue("endMs (${turn.endMs}) must be <= durationMs ($durationMs)", turn.endMs <= durationMs + 1000L)
        }

        assertEquals("Single speaker audio should result in exactly 1 speaker ID", 1, distinctSpeakers.size)
        assertEquals("First speaker must be speaker_1", "speaker_1", distinctSpeakers.first())
    }

    private fun resolveOrExtractModel(testContext: android.content.Context, appContext: android.content.Context, modelName: String): File {
        val cacheFile = File(appContext.cacheDir, modelName)
        if (cacheFile.exists() && cacheFile.length() > 0L) {
            return cacheFile
        }

        try {
            testContext.assets.open(modelName).use { assetStream ->
                FileOutputStream(cacheFile).use { out -> assetStream.copyTo(out) }
            }
        } catch (e: Exception) {
            throw AssertionError("MODEL_MISSING $modelName: asset not found (${e.message})")
        }

        if (!cacheFile.exists() || cacheFile.length() == 0L) {
            throw AssertionError("MODEL_MISSING $modelName: failed to extract model to cache")
        }

        return cacheFile
    }

    private fun extractAndConvertWavToFloat32Pcm(
        testContext: android.content.Context,
        appContext: android.content.Context,
        wavName: String
    ): File {
        val pcmOutputFile = File(appContext.cacheDir, "jfk_converted_diar.pcm")

        val wavBytes = try {
            testContext.assets.open(wavName).use { it.readBytes() }
        } catch (e: Exception) {
            throw AssertionError("MODEL_MISSING $wavName asset: ${e.message}")
        }

        val headerSize = 44
        if (wavBytes.size <= headerSize) {
            throw IllegalArgumentException("Invalid WAV file size")
        }

        val byteBuffer = ByteBuffer.wrap(wavBytes, headerSize, wavBytes.size - headerSize)
            .order(ByteOrder.LITTLE_ENDIAN)

        val numShorts = (wavBytes.size - headerSize) / 2
        val floatByteBuffer = ByteBuffer.allocate(numShorts * 4).order(ByteOrder.LITTLE_ENDIAN)

        for (i in 0 until numShorts) {
            val shortSample = byteBuffer.short
            val floatSample = shortSample / 32768.0f
            floatByteBuffer.putFloat(floatSample)
        }

        FileOutputStream(pcmOutputFile).use { out ->
            out.write(floatByteBuffer.array())
        }

        return pcmOutputFile
    }
}
