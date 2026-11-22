package io.github.iteramen.voxdroid

import android.media.AudioManager
import android.text.InputType

/** [Field.identity] is whatever the host compares fields by: the `AccessibilityNodeInfo` itself, whose `equals` is window plus node id. */
data class Field(val identity: Any, val isPassword: Boolean, val multiLine: Boolean = false)

enum class NoFieldMode(val stored: String) {
    Refuse("refuse"),
    Clipboard("clipboard");

    companion object {
        fun parse(raw: String): NoFieldMode = if (raw == "clipboard") Clipboard else Refuse
    }
}

enum class Notice { NoField, FieldChanged, PasswordField }

sealed interface Admission {
    data object Record : Admission
    data class Refuse(val notice: Notice) : Admission
    data object InCall : Admission
}

sealed interface Delivery {
    data object Insert : Delivery
    data class Clipboard(val notice: Notice) : Delivery
    data class Drop(val notice: Notice) : Delivery
}

fun admit(field: Field?, mode: NoFieldMode, inCall: Boolean): Admission = when {
    inCall -> Admission.InCall
    field?.isPassword == true -> Admission.Refuse(Notice.PasswordField)
    field != null -> Admission.Record
    mode == NoFieldMode.Clipboard -> Admission.Record
    else -> Admission.Refuse(Notice.NoField)
}

fun delivery(start: Field?, now: Field?, mode: NoFieldMode): Delivery {
    if (now?.isPassword == true) return Delivery.Drop(Notice.PasswordField)
    // The same node, even if its EditorInfo read differently at the start; a password `now` is handled above.
    if (start != null && start.identity == now?.identity) return Delivery.Insert
    val notice = if (start == null) Notice.NoField else Notice.FieldChanged
    return if (mode == NoFieldMode.Clipboard) Delivery.Clipboard(notice) else Delivery.Drop(notice)
}

/** TextView's `isAnyPasswordInputType`, judged on the class and variation bits alone. */
fun isPasswordInputType(inputType: Int): Boolean =
    when (inputType and (InputType.TYPE_MASK_CLASS or InputType.TYPE_MASK_VARIATION)) {
        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
        InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD -> true
        else -> false
    }

/** A text field that takes more than one line; a single-line field only displays a committed `\n` as a space. */
fun isMultiLineTextType(inputType: Int): Boolean =
    (inputType and InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_TEXT &&
        (inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0

/** Only an insert into a multi-line field keeps the dictated breaks; a copy and every other field get spaces. */
fun keepsLineBreaks(lineBreaks: Boolean, decision: Delivery?, field: Field?): Boolean =
    lineBreaks && decision == Delivery.Insert && field?.multiLine == true

/** What a decision lands: [typed] only for the insert, the [spaced] text for a copy (a failed insert's fallback too), nothing for a drop. */
fun landedText(decision: Delivery, typed: String, spaced: String): String = when (decision) {
    Delivery.Insert -> typed
    is Delivery.Clipboard -> spaced
    is Delivery.Drop -> ""
}

fun isCallMode(mode: Int): Boolean = when (mode) {
    AudioManager.MODE_IN_CALL,
    AudioManager.MODE_IN_COMMUNICATION,
    AudioManager.MODE_CALL_SCREENING,
    AudioManager.MODE_CALL_REDIRECT,
    AudioManager.MODE_COMMUNICATION_REDIRECT -> true
    else -> false
}

enum class PillText {
    NoFieldNotRecorded,
    PasswordNotRecorded,
    InCallNotRecorded,
    NoFieldNotInserted,
    FieldChangedNotInserted,
    PasswordNotInserted,
    Copied,
    FieldChangedCopied,
}

fun refusalText(notice: Notice): PillText = when (notice) {
    Notice.NoField -> PillText.NoFieldNotRecorded
    Notice.PasswordField -> PillText.PasswordNotRecorded
    Notice.FieldChanged -> PillText.FieldChangedNotInserted
}

fun deliveryText(notice: Notice, copied: Boolean): PillText = when (notice) {
    Notice.NoField -> if (copied) PillText.Copied else PillText.NoFieldNotInserted
    Notice.FieldChanged -> if (copied) PillText.FieldChangedCopied else PillText.FieldChangedNotInserted
    Notice.PasswordField -> PillText.PasswordNotInserted
}

/** Only a copy delivered the text; every other notice reports a refusal or a drop. */
fun isFailure(text: PillText): Boolean = text != PillText.Copied && text != PillText.FieldChangedCopied

/** A notice joined from several parts stays as long as its most severe part. */
fun flashMs(vararg failures: Boolean): Long = if (failures.any { it }) FAILURE_FLASH_MS else NOTICE_FLASH_MS

/** An error pill holds the take's audio and its Retry until ✕, Retry or a new take, so no notice replaces it. */
fun flashesOver(state: TakeState): Boolean = state == TakeState.Idle

internal const val FAILURE_FLASH_MS = 4000L
internal const val NOTICE_FLASH_MS = 2500L

internal fun noFieldChecked(row: NoFieldMode, stored: String): Boolean = NoFieldMode.parse(stored) == row

internal fun noFieldTap(row: NoFieldMode, stored: String): String? =
    if (noFieldChecked(row, stored)) null else row.stored
