package io.github.iteramen.voxdroid

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.util.Log
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

data class Note(val hz: Int, val ms: Int)

/** byovox's cue tones: plain sine notes, played one after the other. */
enum class Tone(val notes: List<Note>) {
    Start(listOf(Note(880, 70))),
    Landed(listOf(Note(660, 60))),
    Error(listOf(Note(440, 110), Note(220, 220)));

    val ms: Int get() = notes.sumOf { it.ms }
}

/** The tone for a haptic [cue]; a Confirm plays [confirm], since the start tone plays before the microphone opens, not with the start's click. */
fun toneFor(cue: Cue, confirm: Tone?): Tone? = when (cue) {
    Cue.None -> null
    Cue.Confirm -> confirm
    Cue.Reject -> Tone.Error
}

/** Silent, vibrate-only and any Do Not Disturb filter mute the tones; nothing here changes a volume or a policy. */
fun audible(enabled: Boolean, percent: Int, ringerMode: Int, interruptionFilter: Int): Boolean =
    enabled && percent > 0 && ringerMode == AudioManager.RINGER_MODE_NORMAL &&
        interruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL

/** Mono PCM16 peaking at [percent] of full scale, as byovox amplifies its sine by the cue volume. */
fun pcm(tone: Tone, percent: Int, rate: Int): ShortArray {
    val peak = percent / 100.0 * Short.MAX_VALUE
    val samples = ShortArray(tone.notes.sumOf { it.ms * rate / 1000 })
    var i = 0
    for (note in tone.notes) {
        for (k in 0 until note.ms * rate / 1000) {
            samples[i++] = (peak * sin(2 * PI * note.hz * k / rate)).roundToInt().toShort()
        }
    }
    return samples
}

/** Plays [Tone]s on the notification stream under transient focus, so the system volume, routing and ducking apply; every failure is soft. */
class TonePlayer(context: Context, private val settings: Settings, private val main: Handler) {

    private val audio = context.getSystemService(AudioManager::class.java)
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private var playing: Playing? = null

    private class Playing(val track: AudioTrack, val focus: AudioFocusRequest)

    /**
     * Returns false, and never calls [done], when nothing plays: the tones are off or muted, focus is
     * refused, or the device will not open a track. Otherwise [done] runs on [main] once the tone has
     * left the speaker, never before this returns.
     */
    fun play(tone: Tone, done: (() -> Unit)? = null): Boolean {
        val percent = settings.cueVolume
        if (!audible(settings.audioCues, percent, audio.ringerMode, notifications.currentInterruptionFilter)) return false
        stop()
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes)
            .build()
        if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return false
        val samples = pcm(tone, percent, SAMPLE_RATE)
        var track: AudioTrack? = null
        try {
            track = AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(samples.size * 2)
                .build()
            val written = track.write(samples, 0, samples.size)
            check(written == samples.size) { "track write returned $written" }
            val p = Playing(track, focus)
            playing = p
            var finished = false
            val finish = Runnable {
                if (finished) return@Runnable
                finished = true
                if (playing === p) stop()
                done?.invoke()
            }
            // The marker is the last frame played out; the guard covers the output latency after it.
            track.notificationMarkerPosition = samples.size
            track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                override fun onMarkerReached(t: AudioTrack) {
                    main.postDelayed(finish, OUTPUT_GUARD_MS)
                }

                override fun onPeriodicNotification(t: AudioTrack) = Unit
            }, main)
            track.play()
            // A marker that never arrives must not hold a take at Starting.
            main.postDelayed(finish, tone.ms + FALLBACK_MS)
        } catch (failed: RuntimeException) {
            // UnsupportedOperationException from build, IllegalStateException from write or play.
            Log.w(TAG, "cue tone: ${failed.javaClass.simpleName}")
            if (playing?.track === track) playing = null
            track?.release()
            audio.abandonAudioFocusRequest(focus)
            return false
        }
        return true
    }

    /** Releases the tone playing, if any; a pending [play] `done` still runs on [main] unless its callbacks are removed. */
    fun stop() {
        val p = playing ?: return
        playing = null
        p.track.release()
        audio.abandonAudioFocusRequest(p.focus)
    }

    private companion object {
        const val SAMPLE_RATE = 48_000
        const val OUTPUT_GUARD_MS = 100L
        const val FALLBACK_MS = 500L
        const val TAG = "voxdroid"
    }
}
