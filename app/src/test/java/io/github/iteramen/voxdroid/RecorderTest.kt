package io.github.iteramen.voxdroid

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class RecorderTest {

    /** The pill shows this app's own message as written and nothing of the platform's but its kind. */
    @Test
    fun aRecorderThatWouldNotStartIsNamedByAFixedLine() {
        assertEquals("mic: 16 kHz capture unsupported", micFailureBar(IllegalStateException(MIC_RATE_UNSUPPORTED)))
        assertEquals("mic: refused by the platform", micFailureBar(SecurityException("uid 10452 /data/private path")))
        assertEquals("mic: unavailable", micFailureBar(IllegalStateException("startRecording() called on an uninitialized AudioRecord.")))
        assertEquals("mic: unavailable", micFailureBar(IllegalStateException()))
    }

    @Test
    fun aRecorderWhoseStartThrowsIsReleasedOnceAndTheFailureStillReachesThePill() {
        val refused = IllegalStateException("startRecording() called on an uninitialized AudioRecord.")
        var releases = 0

        val thrown = assertThrows(IllegalStateException::class.java) {
            startOrRelease({ throw refused }, { releases++ })
        }

        assertSame(refused, thrown)
        assertEquals(1, releases)
        assertEquals("mic: unavailable", micFailureBar(thrown))
    }

    @Test
    fun aRecorderThatStartsIsNotReleased() {
        var releases = 0

        startOrRelease({}, { releases++ })

        assertEquals(0, releases)
    }

    /** The recorder itself, not only the helper: a refused start releases that microphone, and nothing is left capturing. */
    @Test
    fun aRecorderWhoseMicrophoneRefusesToStartReleasesItAndCapturesNothing() {
        var starts = 0
        var stops = 0
        var releases = 0
        val recorder = AudioRecorder {
            object : Mic {
                override val bufferBytes = 16
                override fun startRecording() {
                    starts++
                    throw IllegalStateException("startRecording() called on an uninitialized AudioRecord.")
                }
                override fun read(buffer: ByteArray) = -1
                override fun stop() { stops++ }
                override fun release() { releases++ }
            }
        }

        assertThrows(IllegalStateException::class.java) { recorder.start() }

        assertEquals(1, starts)
        assertEquals(1, releases)
        assertFalse(recorder.capturing)
        assertEquals(HEADER, recorder.stop().size)
        assertEquals(0, stops)
        assertEquals(1, releases)
    }

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
