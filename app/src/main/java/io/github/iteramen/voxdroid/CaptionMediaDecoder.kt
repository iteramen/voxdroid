package io.github.iteramen.voxdroid

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.io.IOException
import java.nio.ByteOrder

/**
 * Ogg/Opus through the platform's `MediaExtractor` and `MediaCodec`, both released on every path. Each dequeue waits at most
 * [DEQUEUE_TIMEOUT_US] and `check` runs between them, so a codec that stops producing ends at the request's deadline or
 * cancellation. A native call that blocks inside the platform (`setDataSource`, `configure`) is not bounded here; its
 * failures come back as [CaptionOutcome.Malformed]. Only a handset exercises this class (`docs/testing.md`).
 */
internal class CaptionMediaDecoder : CaptionDecoder {

    override fun decode(file: File, sink: CaptionPcmSink, check: () -> Unit) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(file.path)
            if (extractor.trackCount != 1) throw CaptionStop(CaptionOutcome.Unsupported)
            val format = extractor.getTrackFormat(0)
            if (format.getString(MediaFormat.KEY_MIME) != MediaFormat.MIMETYPE_AUDIO_OPUS) throw CaptionStop(CaptionOutcome.Unsupported)
            extractor.selectTrack(0)
            val decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start()
            pump(extractor, decoder, sink, check)
        } catch (failed: IOException) {
            throw CaptionStop(CaptionOutcome.Malformed)
        } catch (failed: IllegalStateException) {
            // MediaCodec.CodecException is one.
            throw CaptionStop(CaptionOutcome.Malformed)
        } catch (failed: IllegalArgumentException) {
            throw CaptionStop(CaptionOutcome.Malformed)
        } finally {
            codec?.release()
            extractor.release()
        }
    }

    private fun pump(extractor: MediaExtractor, codec: MediaCodec, sink: CaptionPcmSink, check: () -> Unit) {
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var formatted = false
        while (true) {
            check()
            if (!inputDone) {
                val index = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                if (index >= 0) {
                    val buffer = codec.getInputBuffer(index) ?: throw CaptionStop(CaptionOutcome.Malformed)
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val index = codec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                report(codec.outputFormat, sink)
                formatted = true
            } else if (index >= 0) {
                if (!formatted) {
                    report(codec.outputFormat, sink)
                    formatted = true
                }
                val buffer = codec.getOutputBuffer(index) ?: throw CaptionStop(CaptionOutcome.Malformed)
                if (info.size > 0) {
                    buffer.position(info.offset)
                    buffer.limit(info.offset + info.size)
                    sink.samples(buffer.slice().order(ByteOrder.nativeOrder()).asShortBuffer())
                }
                codec.releaseOutputBuffer(index, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
            }
        }
    }

    /** A missing key reads as 0, which the sink refuses; an absent PCM encoding is the platform's default, 16-bit. */
    private fun report(format: MediaFormat, sink: CaptionPcmSink) {
        val encoding = format.getInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
        sink.format(
            format.getInteger(MediaFormat.KEY_SAMPLE_RATE, 0),
            format.getInteger(MediaFormat.KEY_CHANNEL_COUNT, 0),
            encoding == AudioFormat.ENCODING_PCM_16BIT,
        )
    }

    private companion object {
        const val DEQUEUE_TIMEOUT_US = 10_000L
    }
}
