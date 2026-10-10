package io.github.iteramen.voxdroid

/** Haptic cue: a click when a take starts or its text lands, a double click on failure, nothing for a discard. */
enum class Cue { None, Confirm, Reject }

/** An unchanged state is silent; [sameTake] tells it from a restart that fails into an equal error. */
fun cueFor(before: TakeState, after: TakeState, sameTake: Boolean): Cue = when {
    before == after && sameTake -> Cue.None
    after == TakeState.Recording -> Cue.Confirm
    after is TakeState.Error -> Cue.Reject
    else -> Cue.None
}

fun cueFor(admission: Admission): Cue = if (admission == Admission.Record) Cue.None else Cue.Reject

/** A polish failure rejects even when its raw text landed. */
fun cueFor(landed: Delivery?, polishFailed: Boolean): Cue = when {
    polishFailed -> Cue.Reject
    landed == null -> Cue.None
    landed is Delivery.Drop -> Cue.Reject
    else -> Cue.Confirm
}
