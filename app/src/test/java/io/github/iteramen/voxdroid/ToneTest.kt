package io.github.iteramen.voxdroid

import android.app.NotificationManager
import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.abs

class ToneTest {

    @Test
    fun theTonesAreByovoxs() {
        assertEquals(listOf(Note(880, 70)), Tone.Start.notes)
        assertEquals(listOf(Note(660, 60)), Tone.Landed.notes)
        assertEquals(listOf(Note(440, 110), Note(220, 220)), Tone.Error.notes)
    }

    @Test
    fun landedTextSoundsTheLandedTone() {
        assertEquals("inserted", Tone.Landed, toneFor(cueFor(Delivery.Insert, polishFailed = false), Tone.Landed))
        assertEquals("copied", Tone.Landed, toneFor(cueFor(Delivery.Clipboard(Notice.NoField), polishFailed = false), Tone.Landed))
    }

    @Test
    fun aFailedCleanupSoundsTheErrorToneEvenWhenItsRawTextLanded() {
        assertEquals(Tone.Error, toneFor(cueFor(Delivery.Insert, polishFailed = true), Tone.Landed))
    }

    @Test
    fun aRefusalADropAndAnErrorPillSoundTheErrorTone() {
        assertEquals("refused", Tone.Error, toneFor(cueFor(Admission.Refuse(Notice.NoField)), null))
        assertEquals("in a call", Tone.Error, toneFor(cueFor(Admission.InCall), null))
        assertEquals("dropped", Tone.Error, toneFor(cueFor(Delivery.Drop(Notice.FieldChanged), polishFailed = false), Tone.Landed))
        val stt = TakeState.Error("stt: timeout", canRetry = true)
        assertEquals("stt failed", Tone.Error, toneFor(cueFor(TakeState.Working(Stage.Stt), stt, sameTake = true), null))
    }

    @Test
    fun stateChangesOtherThanAnErrorAreSilent() {
        val silent = listOf(
            "the tone begins" to cueFor(TakeState.Idle, TakeState.Starting, sameTake = false),
            "the microphone opens" to cueFor(TakeState.Starting, TakeState.Recording, sameTake = true),
            "stop" to cueFor(TakeState.Recording, TakeState.Working(Stage.Stt), sameTake = true),
            "polish" to cueFor(TakeState.Working(Stage.Stt), TakeState.Working(Stage.Polish), sameTake = true),
            "silence" to cueFor(TakeState.Working(Stage.Stt), TakeState.Idle, sameTake = true),
            "cancel" to cueFor(TakeState.Recording, TakeState.Idle, sameTake = false),
            "cancel during the tone" to cueFor(TakeState.Starting, TakeState.Idle, sameTake = false),
            "an admitted trigger" to cueFor(Admission.Record),
        )
        for ((what, cue) in silent) assertNull(what, toneFor(cue, null))
        assertNull("an empty take", toneFor(cueFor(null, polishFailed = false), Tone.Landed))
    }

    @Test
    fun theDefaultIsOnAtFivePercentAndAudibleWithTheRingerOnAndNoDnd() {
        assertTrue("AUDIO_CUES_DEFAULT", AUDIO_CUES_DEFAULT)
        assertEquals(5, CUE_VOLUME_DEFAULT)
        assertTrue(audible(AUDIO_CUES_DEFAULT, CUE_VOLUME_DEFAULT, AudioManager.RINGER_MODE_NORMAL, NotificationManager.INTERRUPTION_FILTER_ALL))
    }

    @Test
    fun theSwitchOrAZeroVolumeSilencesTheTones() {
        assertFalse("off", audible(false, 50, AudioManager.RINGER_MODE_NORMAL, NotificationManager.INTERRUPTION_FILTER_ALL))
        assertFalse("0 %", audible(true, 0, AudioManager.RINGER_MODE_NORMAL, NotificationManager.INTERRUPTION_FILTER_ALL))
    }

    @Test
    fun silentVibrateAndDndSilenceTheTones() {
        for (mode in listOf(AudioManager.RINGER_MODE_SILENT, AudioManager.RINGER_MODE_VIBRATE)) {
            assertFalse("ringer $mode", audible(true, 100, mode, NotificationManager.INTERRUPTION_FILTER_ALL))
        }
        for (filter in listOf(
            NotificationManager.INTERRUPTION_FILTER_PRIORITY, NotificationManager.INTERRUPTION_FILTER_ALARMS,
            NotificationManager.INTERRUPTION_FILTER_NONE, NotificationManager.INTERRUPTION_FILTER_UNKNOWN,
        )) {
            assertFalse("filter $filter", audible(true, 100, AudioManager.RINGER_MODE_NORMAL, filter))
        }
    }

    @Test
    fun theVolumeIsBoundedToZeroToAHundred() {
        assertEquals(0, cueVolume(-1))
        assertEquals(0, cueVolume(0))
        assertEquals(5, cueVolume(5))
        assertEquals(100, cueVolume(100))
        assertEquals(100, cueVolume(101))
    }

    @Test
    fun theSamplesPeakAtTheVolumeAndRunAtTheNotesPitch() {
        val rate = 48_000
        for (percent in listOf(5, 100)) {
            val samples = pcm(Tone.Start, percent, rate)
            assertEquals(70 * rate / 1000, samples.size)
            val peak = samples.maxOf { abs(it.toInt()) }
            assertEquals("peak at $percent %", percent / 100.0 * Short.MAX_VALUE, peak.toDouble(), Short.MAX_VALUE * 0.005)
            assertEquals("880 Hz over 70 ms", 2 * 880 * 70 / 1000.0, signChanges(samples).toDouble(), 2.0)
        }
        val error = pcm(Tone.Error, 5, rate)
        assertEquals((110 + 220) * rate / 1000, error.size)
        assertEquals("440 Hz then 220 Hz", (2 * 440 * 110 + 2 * 220 * 220) / 1000.0, signChanges(error).toDouble(), 4.0)
        assertTrue("0 % is silence", pcm(Tone.Landed, 0, rate).all { it.toInt() == 0 })
    }

    @Test
    fun theTriggersPageHasItsOwnToneSwitchAndAVolumeSliderBesideTheHapticSwitch() {
        val doc = DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(triggersFile())
        fun rows(tag: String, key: String) = doc.getElementsByTagName(tag).let { list ->
            (0 until list.length).map { list.item(it) as Element }.filter { it.getAttributeNS(ANDROID_NS, "key") == key }
        }
        assertEquals("haptic_cues", 1, rows("SwitchPreferenceCompat", "haptic_cues").size)
        val audio = rows("SwitchPreferenceCompat", "audio_cues").single()
        assertEquals("audio_cues defaultValue", AUDIO_CUES_DEFAULT.toString(), audio.getAttributeNS(ANDROID_NS, "defaultValue"))
        val volume = rows("SeekBarPreference", "cue_volume").single()
        assertEquals("cue_volume defaultValue", CUE_VOLUME_DEFAULT.toString(), volume.getAttributeNS(ANDROID_NS, "defaultValue"))
        assertEquals("cue_volume min", "0", volume.getAttributeNS(APP_NS, "min"))
        assertEquals("cue_volume max", "100", volume.getAttributeNS(ANDROID_NS, "max"))
    }

    private fun signChanges(samples: ShortArray): Int {
        val signs = samples.filter { it.toInt() != 0 }.map { it > 0 }
        return signs.zipWithNext().count { (a, b) -> a != b }
    }

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
        const val APP_NS = "http://schemas.android.com/apk/res-auto"

        fun triggersFile(): File = sequenceOf(
            "src/main/res/xml/section_triggers.xml",
            "app/src/main/res/xml/section_triggers.xml",
        ).map(::File).first(File::exists)
    }
}
