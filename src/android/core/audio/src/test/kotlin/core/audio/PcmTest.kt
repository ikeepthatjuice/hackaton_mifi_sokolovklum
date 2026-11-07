package core.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PcmTest {

    @Test
    fun pcm16StereoIsAveragedToMono() {
        val buf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(16384).putShort(0).putShort(-32768).putShort(-32768).flip()
        assertArrayEquals(floatArrayOf(0.25f, -1f), pcm16ToMono(buf, channels = 2), 1e-6f)
    }

    @Test
    fun pcmFloatIsClampedToUnitRange() {
        val buf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        buf.putFloat(1.5f).putFloat(-0.25f).flip()
        assertArrayEquals(floatArrayOf(1f, -0.25f), pcmFloatToMono(buf, channels = 1), 0f)
    }

    @Test
    fun requiredBytesCoversFloat32At16kWithHeadroom() {
        // 10 s → 160 000 samples × 4 bytes = 640 000, +10%.
        assertEquals(704_000L, requiredPcmBytes(10_000_000L))
        assertNull(requiredPcmBytes(null))
        assertNull(requiredPcmBytes(0))
    }
}
