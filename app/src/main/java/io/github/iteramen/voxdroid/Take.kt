package io.github.iteramen.voxdroid

sealed interface TakeState {
    data object Idle : TakeState
    /** The start tone is playing; the microphone opens once it has finished, so the tone is not recorded. */
    data object Starting : TakeState
    data object Recording : TakeState
    data class Working(val stage: Stage) : TakeState
    data class Error(val message: String, val canRetry: Boolean) : TakeState
}

enum class Stage { Stt, Polish }

/** An automatic screen timeout is the same ACTION_SCREEN_OFF as the power button, which cancels: hold the screen while a take is in flight. */
fun keepsScreenOn(state: TakeState): Boolean = state == TakeState.Recording || state is TakeState.Working

sealed interface TakeEvent {
    data object Trigger : TakeEvent
    data object Retry : TakeEvent
    data object Cancel : TakeEvent
    data object CapReached : TakeEvent
    data class StartToneEnded(val take: Int) : TakeEvent
    data class Polishing(val take: Int) : TakeEvent
    data class Transcript(val text: String, val take: Int) : TakeEvent
    data class Failure(val message: String, val take: Int) : TakeEvent
}

interface TakeHost {
    /** True when the start tone plays: [TakeEvent.StartToneEnded] for [take] then follows, never before this returns. */
    fun startTone(take: Int): Boolean
    fun startRecording(): String?
    fun stopRecording(): ByteArray
    fun armCap()
    fun cancelCap()
    fun transcribe(wav: ByteArray, take: Int)
    /** Hands the final text to delivery (insert, clipboard or drop). Never called with "". */
    fun deliver(text: String)
    /** Called when the take is back at Idle, not on a failure: its audio is kept for a Retry into the same field. */
    fun ended()
}

class Take(private val host: TakeHost) {

    var state: TakeState = TakeState.Idle
        private set

    /** A result for any take but the current one is dropped; read off the main thread too. */
    @Volatile var currentTake = 0
        private set

    private var audio: ByteArray? = null

    fun on(event: TakeEvent) {
        when (event) {
            TakeEvent.Trigger -> when (state) {
                TakeState.Idle, is TakeState.Error -> begin()
                TakeState.Recording -> stopAndTranscribe()
                TakeState.Starting, is TakeState.Working -> Unit
            }
            TakeEvent.CapReached -> if (state == TakeState.Recording) stopAndTranscribe()
            is TakeEvent.StartToneEnded -> if (event.take == currentTake && state == TakeState.Starting) start()
            TakeEvent.Retry -> {
                val held = audio
                if (state is TakeState.Error && held != null) {
                    state = TakeState.Working(Stage.Stt)
                    host.transcribe(held, currentTake)
                }
            }
            TakeEvent.Cancel -> cancel()
            is TakeEvent.Polishing ->
                if (event.take == currentTake && state is TakeState.Working) state = TakeState.Working(Stage.Polish)
            is TakeEvent.Transcript -> if (event.take == currentTake && state is TakeState.Working) {
                audio = null
                state = TakeState.Idle
                if (event.text.isNotEmpty()) host.deliver(event.text)
                host.ended()
            }
            is TakeEvent.Failure -> if (event.take == currentTake && state is TakeState.Working) {
                state = TakeState.Error(event.message, canRetry = audio != null)
            }
        }
    }

    private fun begin() {
        currentTake++
        audio = null
        if (host.startTone(currentTake)) state = TakeState.Starting else start()
    }

    private fun start() {
        val error = host.startRecording()
        if (error != null) {
            state = TakeState.Error(error, canRetry = false)
            return
        }
        state = TakeState.Recording
        host.armCap()
    }

    private fun stopAndTranscribe() {
        host.cancelCap()
        val wav = host.stopRecording()
        audio = wav
        state = TakeState.Working(Stage.Stt)
        host.transcribe(wav, currentTake)
    }

    private fun cancel() {
        if (state == TakeState.Recording) {
            host.cancelCap()
            host.stopRecording()
        }
        currentTake++
        audio = null
        state = TakeState.Idle
        host.ended()
    }
}
