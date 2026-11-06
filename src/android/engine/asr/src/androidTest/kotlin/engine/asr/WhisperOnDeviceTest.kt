package engine.asr

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import core.contracts.AudioInput
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WhisperOnDeviceTest {

    private val tag = "ENGINE_TEST"

    @Test
    fun testWhisperOnDeviceWithJfkWav() = runBlocking {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val testContext = InstrumentationRegistry.getInstrumentation().context

        // 1. Resolve model file (check assets -> cacheDir, filesDir, or /sdcard fallback)
        val modelName = "ggml-base.bin"
        val modelFile = resolveOrExtractModel(testContext, appContext, modelName)

        if (!modelFile.exists() || modelFile.length() == 0L) {
            val errorMsg = "MODEL_MISSING $modelName"
            Log.e(tag, errorMsg)
            throw AssertionError(errorMsg)
        }

        // 2. Extract jfk.wav from assets and convert to 16k float32 mono raw PCM
        val pcmFile = extractAndConvertWavToFloat32Pcm(testContext, appContext, "jfk.wav")

        val durationMs = 11000L // JFK sample is ~11 seconds
        val audioInput = AudioInput(
            pcmFilePath = pcmFile.absolutePath,
            sampleRateHz = 16000,
            channels = 1,
            durationMs = durationMs
        )

        // 3. Initialize engine and run transcription
        val config = WhisperAsrConfig(
            modelPath = modelFile.absolutePath,
            language = "en",
            threads = 4,
            enableTokenTimestamps = true,
            isFloat32Pcm = true
        )
        val engine = WhisperAsrEngine(config)

        val startTime = System.currentTimeMillis()
        val words = engine.transcribe(audioInput)
        val elapsedMs = System.currentTimeMillis() - startTime

        // 4. Log measurement: asr words=N time_ms=T audio_ms=D
        Log.i(tag, "asr words=${words.size} time_ms=$elapsedMs audio_ms=$durationMs")

        // 5. Validations
        assertTrue("Expected words > 0, got ${words.size}", words.isNotEmpty())

        var prevEnd = -1L
        val fullTextBuilder = StringBuilder()

        for (w in words) {
            assertTrue("startMs (${w.startMs}) must be < endMs (${w.endMs})", w.startMs < w.endMs)
            assertTrue("startMs (${w.startMs}) must be >= 0", w.startMs >= 0L)
            assertTrue("endMs (${w.endMs}) must be <= durationMs ($durationMs)", w.endMs <= durationMs + 1000L)
            assertTrue("Timestamps must be monotonic: startMs ${w.startMs} >= prevEnd $prevEnd", w.startMs >= prevEnd - 100L)
            prevEnd = w.endMs
            fullTextBuilder.append(w.text).append(" ")
        }

        val fullText = fullTextBuilder.toString().lowercase()
        assertTrue("Recognized text must contain 'country'. Recognized: '$fullText'", fullText.contains("country"))
    }

    private fun resolveOrExtractModel(testContext: android.content.Context, appContext: android.content.Context, modelName: String): File {
        // Option A: check test assets
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

        // Option B: check filesDir/models/
        val filesDirModel = File(File(appContext.filesDir, "models"), modelName)
        if (filesDirModel.exists() && filesDirModel.length() > 0L) {
            return filesDirModel
        }

        // Option C: check /sdcard/Android/data/<pkg>/files/models/
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
        val pcmOutputFile = File(appContext.cacheDir, "jfk_converted.pcm")

        val wavBytes = try {
            testContext.assets.open(wavName).use { it.readBytes() }
        } catch (e: Exception) {
            throw AssertionError("MODEL_MISSING $wavName asset: ${e.message}")
        }

        // Parse WAV header (skip 44-byte standard RIFF header) and convert 16-bit PCM to float32 LE
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

