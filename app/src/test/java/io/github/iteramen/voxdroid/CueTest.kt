package io.github.iteramen.voxdroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class CueTest {

    private val sttError = TakeState.Error("stt: timeout", canRetry = true)

    @Test
    fun aRecordingThatStartsClicks() {
        assertEquals("from Idle", Cue.Confirm, cueFor(TakeState.Idle, TakeState.Recording, sameTake = false))
        assertEquals("from an error pill", Cue.Confirm, cueFor(sttError, TakeState.Recording, sameTake = false))
    }

    @Test
    fun textTypedIntoTheFieldClicks() {
        assertEquals(Cue.Confirm, cueFor(Delivery.Insert, polishFailed = false))
    }

    @Test
    fun textCopiedToTheClipboardClicks() {
        for (notice in listOf(Notice.NoField, Notice.FieldChanged)) {
            assertEquals("Clipboard($notice)", Cue.Confirm, cueFor(Delivery.Clipboard(notice), polishFailed = false))
        }
    }

    @Test
    fun aTakeDroppedAtDeliveryDoubleClicksForEachNotice() {
        for (notice in Notice.entries) {
            assertEquals("Drop($notice)", Cue.Reject, cueFor(Delivery.Drop(notice), polishFailed = false))
        }
    }

    @Test
    fun aRefusedTriggerDoubleClicksForEachNoticeAndForACall() {
        for (notice in Notice.entries) {
            assertEquals("Refuse($notice)", Cue.Reject, cueFor(Admission.Refuse(notice)))
        }
        assertEquals("InCall", Cue.Reject, cueFor(Admission.InCall))
    }

    @Test
    fun anAdmittedTriggerIsSilent() {
        assertEquals(Cue.None, cueFor(Admission.Record))
    }

    @Test
    fun anErrorPillDoubleClicks() {
        assertEquals("an STT failure", Cue.Reject, cueFor(TakeState.Working(Stage.Stt), sttError, sameTake = true))
        val mic = TakeState.Error("mic: unavailable", canRetry = false)
        assertEquals("the microphone at the start", Cue.Reject, cueFor(TakeState.Idle, mic, sameTake = false))
    }

    @Test
    fun aRestartThatFailsAgainIsANewErrorPill() {
        val take = Take(FailingHost())
        take.on(TakeEvent.Trigger)
        val before = take.state
        val beforeTake = take.currentTake
        take.on(TakeEvent.Trigger)
        assertEquals(before, take.state)
        assertEquals(Cue.Reject, cueFor(before, take.state, sameTake = beforeTake == take.currentTake))
    }

    @Test
    fun aPolishFallbackThatLandsDoubleClicks() {
        assertEquals("inserted", Cue.Reject, cueFor(Delivery.Insert, polishFailed = true))
        assertEquals("copied", Cue.Reject, cueFor(Delivery.Clipboard(Notice.NoField), polishFailed = true))
    }

    @Test
    fun anEmptyTranscriptAfterAPolishFailureDoubleClicks() {
        assertEquals(Cue.Reject, cueFor(null, polishFailed = true))
    }

    @Test
    fun aCancelIsSilent() {
        for (from in listOf(TakeState.Recording, TakeState.Working(Stage.Stt), sttError)) {
            assertEquals("Cancel from $from", Cue.None, cueFor(from, TakeState.Idle, sameTake = false))
        }
    }

    @Test
    fun silenceIsSilent() {
        assertEquals(Cue.None, cueFor(TakeState.Working(Stage.Stt), TakeState.Idle, sameTake = true))
    }

    @Test
    fun anEmptyTranscriptIsSilent() {
        assertEquals(Cue.None, cueFor(null, polishFailed = false))
    }

    @Test
    fun anEventThatChangesNothingIsSilent() {
        assertEquals("error pill", Cue.None, cueFor(sttError, sttError, sameTake = true))
        assertEquals("recording", Cue.None, cueFor(TakeState.Recording, TakeState.Recording, sameTake = true))
        assertEquals("polish", Cue.None, cueFor(TakeState.Working(Stage.Stt), TakeState.Working(Stage.Polish), sameTake = true))
    }

    @Test
    fun theSwitchIsOnByDefaultInCodeAndOnThePage() {
        assertTrue("HAPTIC_CUES_DEFAULT", HAPTIC_CUES_DEFAULT)
        val switches = DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(triggersFile())
            .getElementsByTagName("SwitchPreferenceCompat")
        val haptic = (0 until switches.length).map { switches.item(it) as Element }
            .filter { it.getAttributeNS(ANDROID_NS, "key") == "haptic_cues" }
        assertEquals("haptic_cues switches on the Triggers page", 1, haptic.size)
        assertEquals("defaultValue", HAPTIC_CUES_DEFAULT.toString(), haptic.single().getAttributeNS(ANDROID_NS, "defaultValue"))
    }

    private class FailingHost : TakeHost {
        override fun startRecording(): String = "mic: unavailable"
        override fun stopRecording(): ByteArray = ByteArray(0)
        override fun armCap() = Unit
        override fun cancelCap() = Unit
        override fun transcribe(wav: ByteArray, take: Int) = Unit
        override fun deliver(text: String) = Unit
        override fun ended() = Unit
    }

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

        fun triggersFile(): File = sequenceOf(
            "src/main/res/xml/section_triggers.xml",
            "app/src/main/res/xml/section_triggers.xml",
        ).map(::File).first(File::exists)
    }
}
