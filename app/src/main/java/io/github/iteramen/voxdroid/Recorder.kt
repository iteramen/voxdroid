package io.github.iteramen.voxdroid

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal const val MIC_RATE_UNSUPPORTED = "mic: 16 kHz capture unsupported"

/** What the pill says when the recorder would not start: this app's own message as written, the platform's by its kind alone. */
internal fun micFailureBar(failed: RuntimeException): String = when {
    failed.message == MIC_RATE_UNSUPPORTED -> MIC_RATE_UNSUPPORTED
    failed is SecurityException -> "mic: refused by the platform"
    else -> "mic: unavailable"
}

/** Runs [start]; if it throws a RuntimeException, runs [release] once and rethrows, so a recorder that would not start is not left allocated. */
internal fun startOrRelease(start: () -> Unit, release: () -> Unit) {
    try {
        start()
    } catch (failed: RuntimeException) {
        release()
        throw failed
    }
}

/** The part of the platform's AudioRecord the recorder drives, so a JVM test can make the start throw. */
internal interface Mic {
    val bufferBytes: Int
    fun startRecording()
    fun read(buffer: ByteArray): Int
    fun stop()
    fun release()
}

/** 16 kHz mono PCM16; a handset that refuses it fails loudly, and [stop] after a [start] that threw returns an empty take. */
class AudioRecorder internal constructor(private val open: () -> Mic) {

    constructor(context: Context) : this({ platformMic(context) })

    private var samples = ByteArrayOutputStream()
    private var mic: Mic? = null
    private var reader: Thread? = null

    @Volatile
    internal var capturing = false
        private set

    fun start() {
        samples.reset()
        val mic = open()
        startOrRelease(mic::startRecording, mic::release)
        capturing = true
        this.mic = mic
        reader = Thread({ read(mic) }, READER_THREAD).apply { start() }
    }

    fun stop(): ByteArray {
        capturing = false
        // stop() first: it unblocks a read() blocked on the device, so the join below can finish.
        try {
            mic?.stop()
        } catch (neverStarted: IllegalStateException) {
        }
        // The join is the happens-before edge: every byte the reader wrote is visible here.
        try {
            reader?.join()
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        reader = null
        mic?.release()
        mic = null
        // A fresh stream, not reset(): reset() keeps the grown backing array with every byte still in it.
        val pcm = samples.toByteArray()
        samples = ByteArrayOutputStream()
        return wav(pcm)
    }

    private fun read(mic: Mic) {
        val buffer = ByteArray(mic.bufferBytes)
        while (capturing) {
            val read = mic.read(buffer)
            if (read > 0) samples.write(buffer, 0, read) else if (read < 0) break
        }
    }

    private companion object {
        const val READER_THREAD = "voxdroid-recorder"
    }
}

private fun platformMic(context: Context): Mic {
    if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
        error(PERMISSION_MISSING)
    }
    val minBytes = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, ENCODING)
    if (minBytes <= 0) error(MIC_RATE_UNSUPPORTED)
    val bytes = minBytes * BUFFER_FACTOR
    val record = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, CHANNEL_IN, ENCODING, bytes)
    // getMinBufferSize can answer for a configuration the constructor then refuses.
    if (record.state != AudioRecord.STATE_INITIALIZED) {
        record.release()
        error(MIC_RATE_UNSUPPORTED)
    }
    return object : Mic {
        override val bufferBytes = bytes
        override fun startRecording() = record.startRecording()
        override fun read(buffer: ByteArray) = record.read(buffer, 0, buffer.size)
        override fun stop() = record.stop()
        override fun release() = record.release()
    }
}

internal fun wav(pcm: ByteArray): ByteArray {
    val out = ByteBuffer.allocate(HEADER_BYTES + pcm.size).order(ByteOrder.LITTLE_ENDIAN)
    out.put(ascii("RIFF"))
    out.putInt(HEADER_BYTES - RIFF_PREFIX_BYTES + pcm.size)
    out.put(ascii("WAVE"))
    out.put(ascii("fmt "))
    out.putInt(PCM_CHUNK_BYTES)
    out.putShort(FORMAT_PCM)
    out.putShort(CHANNELS)
    out.putInt(SAMPLE_RATE)
    out.putInt(SAMPLE_RATE * CHANNELS * BYTES_PER_SAMPLE)
    out.putShort((CHANNELS * BYTES_PER_SAMPLE).toShort())
    out.putShort((BYTES_PER_SAMPLE * BITS_PER_BYTE).toShort())
    out.put(ascii("data"))
    out.putInt(pcm.size)
    out.put(pcm)
    return out.array()
}

private fun ascii(tag: String) = tag.toByteArray(Charsets.US_ASCII)

private const val HEADER_BYTES = 44
private const val RIFF_PREFIX_BYTES = 8
private const val PCM_CHUNK_BYTES = 16
private const val FORMAT_PCM: Short = 1
private const val CHANNELS: Short = 1
private const val SAMPLE_RATE = 16_000
private const val BYTES_PER_SAMPLE: Short = 2
private const val BITS_PER_BYTE = 8

private const val PERMISSION_MISSING = "mic: permission missing"
private const val CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO
private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
private const val BUFFER_FACTOR = 4
