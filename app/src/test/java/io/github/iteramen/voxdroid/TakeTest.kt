package io.github.iteramen.voxdroid

import org.junit.Assert.assertEquals
import org.junit.Test

class TakeTest {

    private class FakeHost(var startError: String? = null) : TakeHost {
        val calls = mutableListOf<String>()
        val delivered = mutableListOf<String>()
        var sent: ByteArray? = null
        override fun startRecording(): String? { calls += "start"; return startError }
        override fun stopRecording(): ByteArray { calls += "stop"; return byteArrayOf(1, 2, 3) }
        override fun armCap() { calls += "arm" }
        override fun cancelCap() { calls += "cancel-cap" }
        override fun transcribe(wav: ByteArray, take: Int) { calls += "transcribe#$take"; sent = wav }
        override fun deliver(text: String) { calls += "deliver"; delivered += text }
        override fun ended() { calls += "ended" }
    }

    private val host = FakeHost()
    private val take = Take(host)

    @Test
    fun aTriggerRecordsAndASecondTriggerTranscribes() {
        take.on(TakeEvent.Trigger)
        assertEquals(TakeState.Recording, take.state)
        take.on(TakeEvent.Trigger)
        assertEquals(TakeState.Working(Stage.Stt), take.state)
        assertEquals(listOf("start", "arm", "cancel-cap", "stop", "transcribe#${take.currentTake}"), host.calls)
    }

    @Test
    fun theCapStopsLikeATrigger() {
        take.on(TakeEvent.Trigger)
        take.on(TakeEvent.CapReached)
        assertEquals(TakeState.Working(Stage.Stt), take.state)
    }

    @Test
    fun theTranscriptIsDeliveredAndTheTakeEnds() {
        take.on(TakeEvent.Trigger); take.on(TakeEvent.Trigger)
        take.on(TakeEvent.Polishing(take.currentTake))
        assertEquals(TakeState.Working(Stage.Polish), take.state)
        take.on(TakeEvent.Transcript("hello", take.currentTake))
        assertEquals(listOf("hello"), host.delivered)
        assertEquals(TakeState.Idle, take.state)
    }

    @Test
    fun anEmptyTranscriptDeliversNothing() {
        take.on(TakeEvent.Trigger); take.on(TakeEvent.Trigger)
        take.on(TakeEvent.Transcript("", take.currentTake))
        assertEquals(emptyList<String>(), host.delivered)
        assertEquals(TakeState.Idle, take.state)
    }

    @Test
    fun aFailureHoldsTheAudioAndRetrySendsTheSameBytes() {
        take.on(TakeEvent.Trigger); take.on(TakeEvent.Trigger)
        val first = host.sent
        take.on(TakeEvent.Failure("stt transport", take.currentTake))
        assertEquals(TakeState.Error("stt transport", canRetry = true), take.state)
        take.on(TakeEvent.Retry)
        assertEquals(TakeState.Working(Stage.Stt), take.state)
        assertEquals(first, host.sent)
    }

    @Test
    fun aRecorderThatWillNotStartIsAnErrorWithoutRetry() {
        val missing = FakeHost(startError = "mic: permission missing")
        val t = Take(missing)
        t.on(TakeEvent.Trigger)
        assertEquals(TakeState.Error("mic: permission missing", canRetry = false), t.state)
        t.on(TakeEvent.Retry)
        assertEquals(TakeState.Error("mic: permission missing", canRetry = false), t.state)
        assertEquals(listOf("start"), missing.calls)
    }

    @Test
    fun aTriggerAfterAnErrorStartsAFreshTake() {
        take.on(TakeEvent.Trigger); take.on(TakeEvent.Trigger)
        take.on(TakeEvent.Failure("stt HTTP 502", take.currentTake))
        take.on(TakeEvent.Trigger)
        assertEquals(TakeState.Recording, take.state)
    }

    @Test
    fun cancelWhileRecordingStopsAndALateResultIsDropped() {
        take.on(TakeEvent.Trigger)
        val stale = take.currentTake
        take.on(TakeEvent.Cancel)
        assertEquals(TakeState.Idle, take.state)
        assertEquals(listOf("start", "arm", "cancel-cap", "stop", "ended"), host.calls)
        take.on(TakeEvent.Transcript("late", stale))
        assertEquals(emptyList<String>(), host.delivered)
    }

    @Test
    fun cancelWhileWorkingDropsTheResult() {
        take.on(TakeEvent.Trigger); take.on(TakeEvent.Trigger)
        val stale = take.currentTake
        take.on(TakeEvent.Cancel)
        take.on(TakeEvent.Transcript("late", stale))
        assertEquals(emptyList<String>(), host.delivered)
        assertEquals(TakeState.Idle, take.state)
    }

    @Test
    fun aTriggerWhileWorkingIsIgnored() {
        take.on(TakeEvent.Trigger); take.on(TakeEvent.Trigger)
        val before = host.calls.toList()
        take.on(TakeEvent.Trigger)
        assertEquals(TakeState.Working(Stage.Stt), take.state)
        assertEquals(before, host.calls)
    }

    @Test
    fun aStaleFailureDoesNotErrorTheNextTake() {
        take.on(TakeEvent.Trigger); take.on(TakeEvent.Trigger)
        val a = take.currentTake
        take.on(TakeEvent.Cancel)
        take.on(TakeEvent.Trigger); take.on(TakeEvent.Trigger)
        take.on(TakeEvent.Failure("stt transport", a))
        assertEquals(TakeState.Working(Stage.Stt), take.state)
    }

    @Test
    fun aStalePolishingDoesNotRelabelTheNextTake() {
        take.on(TakeEvent.Trigger); take.on(TakeEvent.Trigger)
        val a = take.currentTake
        take.on(TakeEvent.Cancel)
        take.on(TakeEvent.Trigger); take.on(TakeEvent.Trigger)
        take.on(TakeEvent.Polishing(a))
        assertEquals(TakeState.Working(Stage.Stt), take.state)
    }

    @Test
    fun aRetryAfterAFailedStartSendsNoEarlierAudio() {
        take.on(TakeEvent.Trigger); take.on(TakeEvent.Trigger)
        take.on(TakeEvent.Failure("stt transport", take.currentTake))
        host.startError = "mic: permission missing"
        take.on(TakeEvent.Trigger)
        assertEquals(TakeState.Error("mic: permission missing", canRetry = false), take.state)
        take.on(TakeEvent.Retry)
        assertEquals(1, host.calls.count { it.startsWith("transcribe") })
    }

    @Test
    fun aResultFromACancelledTakeIsDroppedEvenAfterANewTakeStarts() {
        take.on(TakeEvent.Trigger); take.on(TakeEvent.Trigger)
        val a = take.currentTake
        take.on(TakeEvent.Cancel)
        take.on(TakeEvent.Trigger); take.on(TakeEvent.Trigger)
        val b = take.currentTake
        take.on(TakeEvent.Transcript("stale", a))
        assertEquals(emptyList<String>(), host.delivered)
        assertEquals(TakeState.Working(Stage.Stt), take.state)
        take.on(TakeEvent.Transcript("fresh", b))
        assertEquals(listOf("fresh"), host.delivered)
    }

    @Test
    fun cancelWhileRecordingEnds() {
        take.on(TakeEvent.Trigger)
        take.on(TakeEvent.Cancel)
        assertEquals("ended", host.calls.last())
        assertEquals(1, host.calls.count { it == "ended" })
    }

    @Test
    fun cancelWhileWorkingEnds() {
        take.on(TakeEvent.Trigger); take.on(TakeEvent.Trigger)
        take.on(TakeEvent.Cancel)
        assertEquals("ended", host.calls.last())
        assertEquals(1, host.calls.count { it == "ended" })
    }

    @Test
    fun cancelInErrorEnds() {
        take.on(TakeEvent.Trigger); take.on(TakeEvent.Trigger)
        take.on(TakeEvent.Failure("stt transport", take.currentTake))
        take.on(TakeEvent.Cancel)
        assertEquals("ended", host.calls.last())
        assertEquals(1, host.calls.count { it == "ended" })
    }

    @Test
    fun anEmptyTranscriptEndsWithoutDelivering() {
        take.on(TakeEvent.Trigger); take.on(TakeEvent.Trigger)
        take.on(TakeEvent.Transcript("", take.currentTake))
        assertEquals("ended", host.calls.last())
        assertEquals(0, host.calls.count { it == "deliver" })
    }

    @Test
    fun aDeliveredTranscriptEndsAfterDeliver() {
        take.on(TakeEvent.Trigger); take.on(TakeEvent.Trigger)
        take.on(TakeEvent.Transcript("hello", take.currentTake))
        assertEquals(listOf("deliver", "ended"), host.calls.takeLast(2))
        assertEquals(1, host.calls.count { it == "ended" })
    }

    @Test
    fun aFailureDoesNotEndAndARetriedTranscriptDeliversThenEnds() {
        take.on(TakeEvent.Trigger); take.on(TakeEvent.Trigger)
        take.on(TakeEvent.Failure("stt transport", take.currentTake))
        assertEquals(0, host.calls.count { it == "ended" })
        take.on(TakeEvent.Retry)
        take.on(TakeEvent.Transcript("hello", take.currentTake))
        assertEquals(listOf("deliver", "ended"), host.calls.takeLast(2))
        assertEquals(1, host.calls.count { it == "ended" })
    }

    @Test
    fun aStaleTranscriptOrFailureDoesNotEnd() {
        take.on(TakeEvent.Trigger); take.on(TakeEvent.Trigger)
        val a = take.currentTake
        take.on(TakeEvent.Cancel)
        take.on(TakeEvent.Trigger); take.on(TakeEvent.Trigger)
        val before = host.calls.count { it == "ended" }
        take.on(TakeEvent.Transcript("stale", a))
        take.on(TakeEvent.Failure("stale", a))
        assertEquals(before, host.calls.count { it == "ended" })
        assertEquals(0, host.calls.count { it == "deliver" })
    }
}
