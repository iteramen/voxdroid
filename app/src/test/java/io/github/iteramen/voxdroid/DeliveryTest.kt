package io.github.iteramen.voxdroid

import android.media.AudioManager
import android.text.InputType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeliveryTest {

    private val chat = Field(identity = "whatsapp#12", isPassword = false)
    private val other = Field(identity = "keep#7", isPassword = false)
    private val password = Field(identity = "bank#3", isPassword = true)

    @Test
    fun aFocusedFieldIsRecordedInEitherMode() {
        assertEquals(Admission.Record, admit(chat, NoFieldMode.Refuse, inCall = false))
        assertEquals(Admission.Record, admit(chat, NoFieldMode.Clipboard, inCall = false))
    }

    @Test
    fun noFieldRefusesOrRecordsForTheClipboardPerTheSetting() {
        assertEquals(Admission.Refuse(Notice.NoField), admit(null, NoFieldMode.Refuse, inCall = false))
        assertEquals(Admission.Record, admit(null, NoFieldMode.Clipboard, inCall = false))
    }

    @Test
    fun aPasswordFieldIsAlwaysRefused() {
        assertEquals(Admission.Refuse(Notice.PasswordField), admit(password, NoFieldMode.Refuse, inCall = false))
        assertEquals(Admission.Refuse(Notice.PasswordField), admit(password, NoFieldMode.Clipboard, inCall = false))
    }

    @Test
    fun aCallRefusesATakeInAFocusedField() {
        assertEquals(Admission.InCall, admit(chat, NoFieldMode.Refuse, inCall = true))
        assertEquals(Admission.InCall, admit(chat, NoFieldMode.Clipboard, inCall = true))
    }

    @Test
    fun aCallRefusesATakeWithNoFieldInEitherMode() {
        assertEquals(Admission.InCall, admit(null, NoFieldMode.Refuse, inCall = true))
        assertEquals(Admission.InCall, admit(null, NoFieldMode.Clipboard, inCall = true))
    }

    @Test
    fun aCallRefusesATakeInAPasswordFieldAsACall() {
        assertEquals(Admission.InCall, admit(password, NoFieldMode.Refuse, inCall = true))
        assertEquals(Admission.InCall, admit(password, NoFieldMode.Clipboard, inCall = true))
    }

    @Test
    fun theFiveCallModesAreACall() {
        val calls = mapOf(
            "MODE_IN_CALL" to AudioManager.MODE_IN_CALL,
            "MODE_IN_COMMUNICATION" to AudioManager.MODE_IN_COMMUNICATION,
            "MODE_CALL_SCREENING" to AudioManager.MODE_CALL_SCREENING,
            "MODE_CALL_REDIRECT" to AudioManager.MODE_CALL_REDIRECT,
            "MODE_COMMUNICATION_REDIRECT" to AudioManager.MODE_COMMUNICATION_REDIRECT,
        )
        for ((name, mode) in calls) assertTrue(name, isCallMode(mode))
    }

    @Test
    fun normalAndRingtoneAreNotACall() {
        assertFalse("MODE_NORMAL", isCallMode(AudioManager.MODE_NORMAL))
        assertFalse("MODE_RINGTONE", isCallMode(AudioManager.MODE_RINGTONE))
    }

    @Test
    fun theSameFieldStillFocusedGetsTheText() {
        assertEquals(Delivery.Insert, delivery(chat, chat.copy(), NoFieldMode.Refuse))
    }

    @Test
    fun aChangedFieldNeverGetsTheText() {
        assertEquals(Delivery.Drop(Notice.FieldChanged), delivery(chat, other, NoFieldMode.Refuse))
        assertEquals(Delivery.Clipboard(Notice.FieldChanged), delivery(chat, other, NoFieldMode.Clipboard))
        assertEquals(Delivery.Drop(Notice.FieldChanged), delivery(chat, null, NoFieldMode.Refuse))
    }

    @Test
    fun aTakeStartedWithNoFieldGoesToTheClipboardEvenIfAFieldIsFocusedNow() {
        assertEquals(Delivery.Clipboard(Notice.NoField), delivery(null, chat, NoFieldMode.Clipboard))
    }

    @Test
    fun theStartFieldTurnedIntoAPasswordFieldIsDroppedAsOneInEitherMode() {
        assertEquals(Delivery.Drop(Notice.PasswordField), delivery(chat, chat.copy(isPassword = true), NoFieldMode.Refuse))
        assertEquals(Delivery.Drop(Notice.PasswordField), delivery(chat, chat.copy(isPassword = true), NoFieldMode.Clipboard))
    }

    @Test
    fun aDifferentPasswordFieldFocusedAtDeliveryIsNeverCopiedNorTypedInto() {
        assertEquals(Delivery.Drop(Notice.PasswordField), delivery(chat, password, NoFieldMode.Refuse))
        assertEquals(Delivery.Drop(Notice.PasswordField), delivery(chat, password, NoFieldMode.Clipboard))
    }

    @Test
    fun aTakeStartedWithNoFieldIsNotCopiedWhileAPasswordFieldIsFocused() {
        assertEquals(Delivery.Drop(Notice.PasswordField), delivery(null, password, NoFieldMode.Clipboard))
        assertEquals(Delivery.Drop(Notice.PasswordField), delivery(null, password, NoFieldMode.Refuse))
    }

    @Test
    fun aPasswordFieldAtBothEndsIsDroppedNotInserted() {
        assertEquals(Delivery.Drop(Notice.PasswordField), delivery(password, password.copy(), NoFieldMode.Refuse))
    }

    @Test
    fun thePasswordVariationsAreRecognisedAlsoWithFlags() {
        val flags = InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        val text = InputType.TYPE_CLASS_TEXT
        val passwords = listOf(
            text or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            text or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            text or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD,
        )
        for (type in passwords) {
            assertTrue(type.toString(), isPasswordInputType(type))
            assertTrue((type or flags).toString(), isPasswordInputType(type or flags))
        }
    }

    @Test
    fun aNearMissIsNotAPasswordType() {
        assertFalse(isPasswordInputType(InputType.TYPE_CLASS_TEXT))
        assertFalse(isPasswordInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS))
        assertFalse(isPasswordInputType(InputType.TYPE_CLASS_NUMBER))
        assertFalse(isPasswordInputType(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL))
        assertFalse(isPasswordInputType(0))
    }

    @Test
    fun theVariationBitsUnderTheWrongClassAreNotAPasswordType() {
        assertFalse(isPasswordInputType(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_TEXT_VARIATION_PASSWORD))
        assertFalse(isPasswordInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_NUMBER_VARIATION_PASSWORD))
        assertFalse(isPasswordInputType(InputType.TYPE_CLASS_PHONE or InputType.TYPE_TEXT_VARIATION_PASSWORD))
        assertFalse(isPasswordInputType(InputType.TYPE_CLASS_DATETIME or InputType.TYPE_NUMBER_VARIATION_PASSWORD))
        assertFalse(isPasswordInputType(InputType.TYPE_TEXT_VARIATION_PASSWORD))
        assertFalse(isPasswordInputType(InputType.TYPE_NUMBER_VARIATION_PASSWORD))
    }

    @Test
    fun theModeParsesWithRefuseAsTheDefault() {
        assertEquals(NoFieldMode.Clipboard, NoFieldMode.parse("clipboard"))
        assertEquals(NoFieldMode.Refuse, NoFieldMode.parse("refuse"))
        assertEquals(NoFieldMode.Refuse, NoFieldMode.parse(""))
    }

    @Test
    fun aNoFieldDropAfterARecordingSaysItWasNotInserted() {
        assertEquals(PillText.NoFieldNotInserted, deliveryText(Notice.NoField, copied = false))
    }

    @Test
    fun withNoStartFieldRefuseModeDropsAsNoFieldWhateverIsFocusedNow() {
        assertEquals(Delivery.Drop(Notice.NoField), delivery(null, chat, NoFieldMode.Refuse))
        assertEquals(Delivery.Drop(Notice.NoField), delivery(null, null, NoFieldMode.Refuse))
    }

    @Test
    fun noDeliveryNoticeSaysNotRecorded() {
        for (notice in Notice.values()) {
            for (copied in listOf(false, true)) {
                val text = deliveryText(notice, copied)
                assertTrue("$notice copied=$copied -> $text", text != PillText.NoFieldNotRecorded)
                assertTrue("$notice copied=$copied -> $text", text != PillText.PasswordNotRecorded)
            }
        }
    }

    @Test
    fun theTriggerRefusalsKeepTheirWords() {
        assertEquals(PillText.NoFieldNotRecorded, refusalText(Notice.NoField))
        assertEquals(PillText.PasswordNotRecorded, refusalText(Notice.PasswordField))
        assertEquals(PillText.FieldChangedNotInserted, refusalText(Notice.FieldChanged))
    }

    @Test
    fun theCopiedAndFieldChangedTextsAreUnchanged() {
        assertEquals(PillText.Copied, deliveryText(Notice.NoField, copied = true))
        assertEquals(PillText.FieldChangedCopied, deliveryText(Notice.FieldChanged, copied = true))
        assertEquals(PillText.FieldChangedNotInserted, deliveryText(Notice.FieldChanged, copied = false))
    }

    @Test
    fun aPasswordFieldIsNotInsertedWhetherOrNotTheTextWasCopied() {
        assertEquals(PillText.PasswordNotInserted, deliveryText(Notice.PasswordField, copied = false))
        assertEquals(PillText.PasswordNotInserted, deliveryText(Notice.PasswordField, copied = true))
    }

    @Test
    fun theCheckedRowIsTheOneTheStoredValueNames() {
        assertTrue("refuse checks Refuse", noFieldChecked(NoFieldMode.Refuse, "refuse"))
        assertFalse("refuse leaves Clipboard unchecked", noFieldChecked(NoFieldMode.Clipboard, "refuse"))
        assertTrue("clipboard checks Clipboard", noFieldChecked(NoFieldMode.Clipboard, "clipboard"))
        assertFalse("clipboard leaves Refuse unchecked", noFieldChecked(NoFieldMode.Refuse, "clipboard"))
        assertTrue("the default is Refuse", noFieldChecked(NoFieldMode.Refuse, ""))
        assertFalse("the default is not Clipboard", noFieldChecked(NoFieldMode.Clipboard, ""))
    }

    @Test
    fun aTapWritesTheRowsValueOnlyWhenItIsNotTheChosenOne() {
        assertEquals("tap Clipboard while refuse", "clipboard", noFieldTap(NoFieldMode.Clipboard, "refuse"))
        assertEquals("tap Refuse while clipboard", "refuse", noFieldTap(NoFieldMode.Refuse, "clipboard"))
        assertEquals("tap checked Refuse", null, noFieldTap(NoFieldMode.Refuse, "refuse"))
        assertEquals("tap checked Clipboard", null, noFieldTap(NoFieldMode.Clipboard, "clipboard"))
        assertEquals("tap Refuse on a fresh install", null, noFieldTap(NoFieldMode.Refuse, ""))
    }

    @Test
    fun theStoredValuesAreTheOnesTheReadSideParses() {
        assertEquals("refuse", NoFieldMode.Refuse.stored)
        assertEquals("clipboard", NoFieldMode.Clipboard.stored)
        NoFieldMode.entries.forEach { assertEquals(it, NoFieldMode.parse(it.stored)) }
    }
}
