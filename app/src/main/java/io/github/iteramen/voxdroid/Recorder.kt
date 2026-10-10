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

/** 16 kHz mono PCM16; a handset that refuses it fails loudly, and [stop] after a [start] that threw returns an empty take. */
class AudioRecorder(private val context: Context) {

    private var samples = ByteArrayOutputStream()
    private var record: AudioRecord? = null
    private var reader: Thread? = null

    @Volatile
    private var capturing = false

    fun start() {
        samples.reset()
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            error(PERMISSION_MISSING)
        }
        val minBytes = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, ENCODING)
        if (minBytes <= 0) error(RATE_UNSUPPORTED)
        val bufferBytes = minBytes * BUFFER_FACTOR
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            CHANNEL_IN,
            ENCODING,
            bufferBytes,
        )
        // getMinBufferSize can answer for a configuration the constructor then refuses.
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            error(RATE_UNSUPPORTED)
        }
        capturing = true
        record.startRecording()
        this.record = record
        reader = Thread({ read(record, bufferBytes) }, READER_THREAD).apply { start() }
    }

    fun stop(): ByteArray {
        capturing = false
        // record.stop() first: it unblocks a read() blocked on the device, so the join below can finish.
        try {
            record?.stop()
        } catch (neverStarted: IllegalStateException) {
        }
        // The join is the happens-before edge: every byte the reader wrote is visible here.
        try {
            reader?.join()
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        reader = null
        record?.release()
        record = null
        // A fresh stream, not reset(): reset() keeps the grown backing array with every byte still in it.
        val pcm = samples.toByteArray()
        samples = ByteArrayOutputStream()
        return wav(pcm)
    }

    private fun read(record: AudioRecord, bufferBytes: Int) {
        val buffer = ByteArray(bufferBytes)
        while (capturing) {
            val read = record.read(buffer, 0, buffer.size)
            if (read > 0) samples.write(buffer, 0, read) else if (read < 0) break
        }
    }

    private companion object {
        const val RATE_UNSUPPORTED = "mic: 16 kHz capture unsupported"
        const val CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        const val BUFFER_FACTOR = 4
        const val READER_THREAD = "voxdroid-recorder"
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
