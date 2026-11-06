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

        val vadName = "silero_vad.onnx"
        val vadFile = resolveOrExtractModel(testContext, appContext, vadName)
        if (!vadFile.exists() || vadFile.length() == 0L) {
            val errorMsg = "MODEL_MISSING $vadName"
            Log.e(tag, errorMsg)
            throw AssertionError(errorMsg)
        }

        val embName = "3dspeaker_speech_campplus_sv_ru_voxceleb_16k.onnx"
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
            vadModelPath = vadFile.absolutePath,
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

        // Log measurement: diar turns=N speakers=K time_ms=T
        Log.i(tag, "diar turns=${turns.size} speakers=${distinctSpeakers.size} time_ms=$elapsedMs")

        // Validations
        assertTrue("Expected at least 1 SpeakerTurn, got ${turns.size}", turns.isNotEmpty())

        val speakerRegex = Regex("^speaker_\\d+$")
        for (turn in turns) {
            assertTrue("SpeakerId '${turn.speakerId}' must match format 'speaker_N'", speakerRegex.matches(turn.speakerId))
            assertTrue("startMs (${turn.startMs}) must be < endMs (${turn.endMs})", turn.startMs < turn.endMs)
            assertTrue("startMs (${turn.startMs}) must be >= 0", turn.startMs >= 0L)
            assertTrue("endMs (${turn.endMs}) must be <= durationMs ($durationMs)", turn.endMs <= durationMs + 1000L)
        }

        // JFK speech is a single speaker monologue
        assertEquals("Single speaker audio should result in exactly 1 speaker ID", 1, distinctSpeakers.size)
        assertEquals("First speaker must be speaker_1", "speaker_1", distinctSpeakers.first())
    }

    private fun resolveOrExtractModel(testContext: android.content.Context, appContext: android.content.Context, modelName: String): File {
        try {
            val assetStream = testContext.assets.open(modelName)
            val cacheFile = File(appContext.cacheDir, modelName)
            if (!cacheFile.exists() || cacheFile.length() == 0L) {
                FileOutputStream(cacheFile).use { out -> assetStream.copyTo(out) }
            }
            if (cacheFile.exists() && cacheFile.length() > 0L) {
                return cacheFile
            }
        } catch (_: Exception) {
        }

        val filesDirModel = File(File(appContext.filesDir, "models"), modelName)
        if (filesDirModel.exists() && filesDirModel.length() > 0L) {
            return filesDirModel
        }

        val externalFilesModel = File(File(appContext.getExternalFilesDir(null), "models"), modelName)
        if (externalFilesModel.exists() && externalFilesModel.length() > 0L) {
            return externalFilesModel
        }

        return File(appContext.cacheDir, modelName)
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

