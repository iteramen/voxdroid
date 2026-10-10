package io.github.iteramen.voxdroid

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class RecorderTest {

    @Test
    fun theHeaderOfASixteenSampleTakeIsTheOneWhisperExpects() {
        val pcm = ByteArray(SAMPLES * BYTES_PER_SAMPLE) { it.toByte() }

        val out = wav(pcm)

        val expected = "RIFF".toByteArray() +
            byteArrayOf(68, 0, 0, 0) + // 36 + 32 data bytes
            "WAVE".toByteArray() +
            "fmt ".toByteArray() +
            byteArrayOf(16, 0, 0, 0) + // a 16-byte PCM fmt chunk
            byteArrayOf(1, 0) + // format 1, uncompressed PCM
            byteArrayOf(1, 0) + // one channel
            byteArrayOf(128.toByte(), 62, 0, 0) + // 16000 Hz
            byteArrayOf(0, 125, 0, 0) + // 32000 bytes a second
            byteArrayOf(2, 0) + // block align
            byteArrayOf(16, 0) + // bits a sample
            "data".toByteArray() +
            byteArrayOf(32, 0, 0, 0)
        assertEquals(HEADER + pcm.size, out.size)
        assertArrayEquals(expected, out.copyOf(HEADER))
        assertArrayEquals(pcm, out.copyOfRange(HEADER, out.size))
    }

    @Test
    fun anEmptyTakeIsStillAValidWav() {
        val out = wav(ByteArray(0))

        assertEquals(HEADER, out.size)
        assertArrayEquals(byteArrayOf(36, 0, 0, 0), out.copyOfRange(4, 8))
        assertArrayEquals(byteArrayOf(0, 0, 0, 0), out.copyOfRange(40, HEADER))
    }

    private companion object {
        const val HEADER = 44
        const val SAMPLES = 16
        const val BYTES_PER_SAMPLE = 2
    }
}
