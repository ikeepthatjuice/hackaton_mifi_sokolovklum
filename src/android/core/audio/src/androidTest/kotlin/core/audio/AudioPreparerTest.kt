package core.audio

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import core.contracts.PipelineError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

// Real MediaExtractor/MediaCodec on device; input is a generated WAV, never case audio.
@RunWith(AndroidJUnit4::class)
class AudioPreparerTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val preparer = AudioPreparer(context)

    private fun writeWav(file: File, rate: Int, channels: Int, seconds: Double, freq: Double) {
        val frames = (rate * seconds).toInt()
        val dataBytes = frames * channels * 2
        val b = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + dataBytes).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(channels.toShort())
        b.putInt(rate).putInt(rate * channels * 2).putShort((channels * 2).toShort()).putShort(16)
        b.put("data".toByteArray()).putInt(dataBytes)
        for (i in 0 until frames) {
            val s = (sin(2 * PI * freq * i / rate) * 0.5 * 32767).toInt().toShort()
            repeat(channels) { b.putShort(s) }
        }
        file.writeBytes(b.array())
    }

    @Test
    fun stereo48kWavBecomesMono16kFloat() = runBlocking {
        val wav = File(context.cacheDir, "synthetic.wav")
        writeWav(wav, rate = 48000, channels = 2, seconds = 2.0, freq = 1000.0)

        val input = preparer.prepare(Uri.fromFile(wav).toString(), jobId = "test-job")

        assertEquals(16000, input.sampleRateHz)
        assertEquals(1, input.channels)
        assertEquals(2000L, input.durationMs)
        val pcm = File(input.pcmFilePath)
        assertTrue(pcm.path.endsWith("jobs/test-job/audio.f32"))
        val samples = ByteBuffer.wrap(pcm.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        assertEquals(32000, samples.remaining())
        var peak = 0f
        for (i in 8000 until 24000) peak = maxOf(peak, abs(samples.get(i)))
        assertEquals(0.5f, peak, 0.03f)
    }

    @Test
    fun notAudioIsUnsupportedAndLeavesNoFile() = runBlocking {
        val junk = File(context.cacheDir, "junk.wav").apply { writeText("not audio") }
        try {
            preparer.prepare(Uri.fromFile(junk).toString(), jobId = "junk-job")
            fail("expected AudioPreparationException")
        } catch (e: AudioPreparationException) {
            assertEquals(PipelineError.UNSUPPORTED_FORMAT, e.error)
        }
        assertFalse(File(context.filesDir, "jobs/junk-job/audio.f32").exists())
    }

    @Test
    fun cancelWhileResultIsHandedBackRemovesFileAndRerunWorks() = runBlocking {
        val wav = File(context.cacheDir, "boundary.wav")
        writeWav(wav, rate = 48000, channels = 2, seconds = 2.0, freq = 1000.0)
        val uri = Uri.fromFile(wav).toString()
        val out = File(context.filesDir, "jobs/boundary-job/audio.f32")
        out.delete()

        val caller = Executors.newSingleThreadExecutor()
        val dispatcher = caller.asCoroutineDispatcher()
        val job = CoroutineScope(dispatcher).launch { preparer.prepare(uri, "boundary-job") }

        waitUntil { out.exists() } // inside withContext(IO) now
        val release = CountDownLatch(1)
        caller.execute { release.await() } // caller thread busy: the result cannot be delivered back
        waitUntil { out.length() == 32000L * 4 } // IO body wrote and closed the whole file
        Thread.sleep(300) // let codec release finish
        job.cancel()
        release.countDown()
        job.join()

        assertFalse("partial PCM left after cancel", out.exists())
        val again = preparer.prepare(uri, "boundary-job")
        assertEquals(2000L, again.durationMs)
        assertTrue(File(again.pcmFilePath).exists())
        dispatcher.close()
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15_000
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) fail("condition not reached in 15 s")
            Thread.sleep(5)
        }
    }
}
