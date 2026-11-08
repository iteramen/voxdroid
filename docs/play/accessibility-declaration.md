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

- the **floating 🎤 control** (`ReadAloudController`), with the original contextual dictation/caption action;
- a **hold of volume-down**, which the service sees through key filtering
  (`flagRequestFilterKeyEvents`; `VoxdroidA11yService.kt` — `onKeyEvent`).

## Who it is for

- **RSI, tendinitis, arthritis — anything that makes typing hurt.** One hold of a hardware key or
  one tap of the floating 🎤 control starts dictation, with no keyboard to switch and no field to
  leave.
- **Motor impairment.** Play's policy names the class: "Voice-based input systems which support
  people with motor impairments" ([Use of the AccessibilityService
  API](https://support.google.com/googleplay/android-developer/answer/10964491)). Both gestures
  work without a precise touch on a small target.
- **Low vision.** Speaking is not a visual act.

### Captions for people who are deaf or hard of hearing

Private readable voice-message captions are the second accessibility purpose. With captions
explicitly enabled, the user selects one downloaded Telegram or WhatsApp voice note and presses the
floating 🎤 control. The service reads the supported foreground conversation header and selected
note's accessibility metadata, invokes native Share, and the user chooses **Caption with Voxdroid**.
The received audio goes to the user's speech server and, when configured, its transcript to cleanup.
A scrollable accessibility overlay shows the result privately over the verified original chat.
There is no automatic paste, Send, inbox scan, microphone recording or background chat processing
(`CaptionAccessibility`, `CaptionController`, `CaptionReceiveActivity`, `CaptionPanel`).

The additional events are window-state, content, scroll, click and long-click, alongside window
changes. They identify selection and invalidate stale captions when the foreground context changes.
Conversation headers/window identity stay in memory; selected audio alone goes off the device.
The additional `flagReportViewIds` exposes resource names needed to identify WhatsApp header,
selection and Share controls. No additional accessibility capabilities, gesture injection, storage
permission or overlay permission is requested. The native Share action is a deterministic response
to the user's selected-note button press, not autonomous conversation navigation.

The first implementation's native Share controls require the supported English app UI. The in-app
enable dialog describes this access and data flow. Remembering text is a separate off-by-default
control, limited to 30 days with Delete all; no sender/chat identity or audio is stored there.
Handset acceptance and remaining qualification are in [`testing.md`](../testing.md#private-caption-integration).
The feature ships in 0.3.0. Its hearing-accessibility purpose does not establish Google approval.

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
- **Places the pill above the keyboard.** On window changes, while a
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

The manifest capabilities and the code's narrower access rules:

- **Bounded event use.** Window, window-state, content, scroll, click and long-click events are
  subscribed to. Caption observation acts only when enabled, dictation is idle and the supported
  source is foreground. Routine header inspection skips the message list. Selected-message
  metadata is inspected only in selected mode; no composer or unselected message text is read.
- **No retained accessibility tree.** The node cache is disabled when the service connects and
  after field lookup. Dictation holds its start field through delivery/cancel/retry. Captions keep
  only RAM origin metadata and temporary nodes. Read aloud holds bounded candidate nodes only during explicit selection; no tree is persisted.
- **No gestures, screenshots, magnification, fingerprint gestures or touch exploration.** The
  capabilities are `canRetrieveWindowContent` and `canRequestFilterKeyEvents`, and the flags
  `flagRequestFilterKeyEvents`, `flagInputMethodEditor`,
  `flagRetrieveInteractiveWindows` and `flagReportViewIds`; nothing else is declared.

And by the code:

- **No text read from another app is logged or written to a file.** Only explicitly selected
  read-aloud text is sent to the configured TTS server. What leaves the device is
  the recording, the transcript and, for an explicit language, the keyboard's language code as the
  `language` field, to the servers the user configured (`SttClient.kt`; `Routed.kt`). Conversation
  headers/selection metadata are never logged, saved or sent. Optional remembered
  captions store only the raw/displayed transcript, outcome, write time and a key digest; no audio
  or chat identity (`CaptionCache`).

## Why an accessibility service

Dictation over the user's own keyboard needs three things no ordinary app has: the **focused field
of another app** (`canRetrieveWindowContent`), to know whether there is a field to type into and
whether it is a password field; the **hardware volume key** before the foreground app sees it
(`canRequestFilterKeyEvents`); and an **input connection into that field** without being the
keyboard (`flagInputMethodEditor`). The pill is an accessibility overlay
(`TYPE_ACCESSIBILITY_OVERLAY`, `Pill.kt`), placed by the keyboard window's bounds
(`flagRetrieveInteractiveWindows`). Caption selection additionally needs WhatsApp control resource
names (`flagReportViewIds`).

voxdroid does not claim to be used only by people with disabilities; anyone may install it. It
claims that the service is built to assist users with disabilities, and that it asks the platform
for access needed by dictation and private selected-note captions, plus explicit read aloud.

## The `isAccessibilityTool` declaration

`a11y_entry.xml` declares `android:isAccessibilityTool="true"`, on Play's policy: "only services
that are designed to help people with disabilities access their device or otherwise overcome
challenges stemming from their disabilities are eligible to declare that they are accessibility
tools", with "Voice-based input systems which support people with motor impairments" among its
examples ([Use of the AccessibilityService
API](https://support.google.com/googleplay/android-developer/answer/10964491)).

The same page says: "Apps declaring isAccessibilityTool are exempt from the prominent disclosure
and consent requirement described below." voxdroid retains that declaration and additionally asks before enabling voice-message captions.
This is a feature disclosure, not evidence of a Play approval or a substitute for the non-tool
disclosure route below. The privacy
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
API 36 (`app/build.gradle.kts` — `targetSdk`), so the owner must complete and update the form for motor, hearing and read-aloud purposes, with "a link to a short
video which shows your app being opened, the accessibility services being enabled, and the
accessibility services being used."

No Console submission is performed by this implementation. The caption access and demonstration
video must be included in the owner's next declaration review; the existing declaration is not
assumed to cover changed behavior.

If Play declines the `true` declaration, the policy's other route is `false` with an in-app
disclosure and consent that immediately precede enabling the service
([User Data policy](https://support.google.com/googleplay/android-developer/answer/9888076)).
voxdroid does not implement that route.

## Read aloud for access to written text

An additional off-by-default accommodation reads a user-chosen visible text block aloud through
their configured TTS server. This supports people who have difficulty seeing or reading text.
The speaker press outlines visible read-only blocks; the subsequent block tap authorizes its
transfer. Password subtrees and editable blocks are excluded. Candidate access is bounded to
600 foreground nodes, no background scan. Selected text and generated speech stay in memory;
Stop, source-view changes, lock, dictation or audio-focus loss cancel it (`ReadAloudController`).
The new mediaPlayback foreground-service permission supports the Stop notification without mic
access. The enable dialog, privacy policy and next Console declaration/video must cover this
text-to-TTS data flow. No Google approval is claimed by adding this accessibility purpose.
