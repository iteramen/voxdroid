# Play accessibility declaration — voxdroid

The wording voxdroid gives Play for its `AccessibilityService`. Statements about the service cite
the code by file and symbol; statements about Play cite the Play page they rest on.

## Purpose

voxdroid is a voice-input accessibility service for people whose hands hurt or do not obey: RSI,
tendinitis, arthritis, motor impairment. Dictating instead of typing is the accommodation itself,
and the service puts it where the person already is: one gesture starts a dictation in the field
they are typing in, with their own keyboard staying up, and the transcript lands at the cursor.
General hands-free dictation for anyone else is a use of the same service, not a different one.

Two gestures reach the service (`res/xml/a11y_entry.xml`):

- the **accessibility button** (`flagRequestAccessibilityButton`), assigned by the user in
  Settings → Accessibility;
- a **hold of volume-down**, which the service sees through key filtering
  (`flagRequestFilterKeyEvents`; `VoxdroidA11yService.kt` — `onKeyEvent`).

## Who it is for

- **RSI, tendinitis, arthritis — anything that makes typing hurt.** One hold of a hardware key or
  one tap of the accessibility button starts dictation, with no keyboard to switch and no field to
  leave.
- **Motor impairment.** Play's policy names the class: "Voice-based input systems which support
  people with motor impairments" ([Use of the AccessibilityService
  API](https://support.google.com/googleplay/android-developer/answer/10964491)). Both gestures
  work without a precise touch on a small target.
- **Low vision.** Speaking is not a visual act.

## What the service does

- **Reads the focused text field** when a dictation starts and again when its text arrives:
  whether it is editable, whether it is a password field (`isPassword`, or a password input type
  on its node or in the keyboard's editor info), and its identity, to tell whether it is the same
  field as at the start (`FieldAccess.kt` — `focused`). A dictation is refused in a password field,
  and at delivery one whose focused field is a password field is dropped, never typed or copied
  (`Delivery.kt` — `admit`, `delivery`). A dictation whose field changed is dropped, or copied to
  the clipboard if the user chose that mode.
- **Types the transcript** once, through the service's own input connection, into the field
  captured at the start. To decide whether a space goes in front, it reads the character before
  the cursor and any selection through that connection; that text only builds the string to type
  (`FieldAccess.kt` — `insert`).
- **Filters key events.** Every hardware key event reaches the service; any key but volume-down
  is passed on at once, as is volume-down when its trigger is off. With the trigger on, a hold of
  volume-down starts a dictation, a press stops one, and a short press while idle is re-applied as
  a volume step. The only state kept is two booleans (`VoxdroidA11yService.kt` — `onKeyEvent`;
  `VolumeGesture.kt`).
- **Places the pill above the keyboard.** It subscribes to window changes only and, while a
  dictation runs, uses the keyboard window's bounds alone; the call that fetches them returns every
  interactive window's info, filtered by type (`Pill.kt` — `keyboardTop`, `follow`).
- **Reads the keyboard's current language**, to choose the transcription language
  (`FieldAccess.kt` — `keyboardTag`; `TakeLanguage.kt` — `takeFor`).
- **Reads the audio mode** when a dictation starts, and refuses it during a call unless the user
  turned on "Dictate during a call" (`Delivery.kt` — `isCallMode`, `admit`). The mode is not stored
  or sent.
- **Records the microphone** under a microphone foreground service, from a start until a stop, a
  cancel, the 5-minute cap or the screen turning off, and sends the audio and the transcript to the
  servers the user configured (`VoxdroidA11yService.kt` — `startRecording`, `transcribe`).
- **Writes the dictated text to the clipboard** only in the mode the user chose, off by default
  (`FieldAccess.kt` — `copy`).

## What the service does not access

Declared in `res/xml/a11y_entry.xml` and enforced by the platform:

- **No events but window changes.** Only `typeWindowsChanged` is subscribed to, so the service
  receives no clicks, focus changes, text changes or notifications through it. The service turns
  the accessibility node cache off when it connects and again after every field lookup, so it keeps
  no copy of another app's nodes (`VoxdroidA11yService.kt` — `onServiceConnected`;
  `FieldAccess.kt` — `focused`).
- **No standing read of the screen.** At a start and at delivery it fetches the active window's
  root with `getRootInActiveWindow(0)`, no prefetch flag set, and asks that root for the focused
  input field only. The start field's node is held until the dictation ends, or through a failure
  until a retry delivers or the dictation is cancelled.
- **No gestures, screenshots, magnification, fingerprint gestures or touch exploration.** The
  capabilities are `canRetrieveWindowContent` and `canRequestFilterKeyEvents`, and the flags
  `flagRequestAccessibilityButton`, `flagRequestFilterKeyEvents`, `flagInputMethodEditor` and
  `flagRetrieveInteractiveWindows`; nothing else is declared.

And by the code:

- **No text read from another app is logged, written to a file or sent.** What leaves the device is
  the recording, the transcript and, for an explicit language, the keyboard's language code as the
  `language` field, to the servers the user configured (`SttClient.kt`; `Routed.kt`). The only thing
  the service stores is how high above the keyboard the user last dropped the pill
  (`Settings.kt` — `pillSaved`).

## Why an accessibility service

Dictation over the user's own keyboard needs three things no ordinary app has: the **focused field
of another app** (`canRetrieveWindowContent`), to know whether there is a field to type into and
whether it is a password field; the **hardware volume key** before the foreground app sees it
(`canRequestFilterKeyEvents`); and an **input connection into that field** without being the
keyboard (`flagInputMethodEditor`). The pill is an accessibility overlay
(`TYPE_ACCESSIBILITY_OVERLAY`, `Pill.kt`), placed by the keyboard window's bounds
(`flagRetrieveInteractiveWindows`).

voxdroid does not claim to be used only by people with disabilities; anyone may install it. It
claims that the service is built to assist users with disabilities, and that it asks the platform
for the least access that does that one thing.

## The `isAccessibilityTool` declaration

`a11y_entry.xml` declares `android:isAccessibilityTool="true"`, on Play's policy: "only services
that are designed to help people with disabilities access their device or otherwise overcome
challenges stemming from their disabilities are eligible to declare that they are accessibility
tools", with "Voice-based input systems which support people with motor impairments" among its
examples ([Use of the AccessibilityService
API](https://support.google.com/googleplay/android-developer/answer/10964491)).

The same page says: "Apps declaring isAccessibilityTool are exempt from the prominent disclosure
and consent requirement described below." voxdroid therefore has no consent screen. The privacy
policy is a separate requirement ("a privacy policy link or text within the app itself", Play's
[User Data policy](https://support.google.com/googleplay/android-developer/answer/9888076)): the
main screen's **Privacy policy** row opens it (`strings.xml` — `privacy_policy_url`).

On the phone, the attribute also decides:

- **A reminder notification.** "Note: If this flag is false, system will show a notification after
  a duration to inform the user about the privacy implications of the service."
  ([R.attr](https://developer.android.com/reference/android/R.attr)).
- **Sensitive views, from Android 14.** `accessibilityDataSensitive`: "Allow interactions from
  AccessibilityServices only if the service sets the isAccessibilityTool property"
  ([R.attr](https://developer.android.com/reference/android/R.attr)).
- **Advanced Protection**, which "Restricts accessibility services to verified accessibility
  tools" ([Advanced Protection](https://support.google.com/android/answer/16339980)); that page
  does not say how a service is verified.

## Play Console

Play's policy says "all apps targeting Android 12 using AccessibilityService API will need to
complete the Permission Declaration Form and receive approval from Google Play" ([Use of the AccessibilityService
API](https://support.google.com/googleplay/android-developer/answer/10964491)). voxdroid targets
API 36 (`app/build.gradle.kts` — `targetSdk`), so it completes the form, with "a link to a short
video which shows your app being opened, the accessibility services being enabled, and the
accessibility services being used."

If Play declines the `true` declaration, the policy's other route is `false` with an in-app
disclosure and consent that immediately precede enabling the service
([User Data policy](https://support.google.com/googleplay/android-developer/answer/9888076)).
voxdroid does not implement that route.
