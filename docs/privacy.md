# Privacy policy — voxdroid

**Effective: 2026-10-10.**

voxdroid is an Android accessibility service that dictates into the text field you are typing in,
with your own keyboard staying up. You hold volume-down or tap the accessibility button, speak,
stop, and the transcript lands where your cursor was. voxdroid has no server of its own: it sends
your speech to servers you configure yourself, as its desktop sibling
[byovox](https://github.com/iteramen/byovox) does. This policy describes what the app does. Each
statement names the source file and symbol that does it, in the
[voxdroid repository](https://github.com/iteramen/voxdroid), so it can be checked.

## Permissions

voxdroid declares four Android permissions (`AndroidManifest.xml`):

- **Microphone** (`RECORD_AUDIO`), to record what you say. The app's status card asks for it, and
  you can take it back at any time in Android's app settings; without it nothing is recorded
  (`VoxdroidA11yService.kt` — `startRecording`).
- **Network** (`INTERNET`), so the recording can reach the servers you configure.
- **Foreground service, microphone type** (`FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE`),
  so the service can record while the app you dictate into is on screen. It runs only from a
  start until a stop, a cancel, the 5-minute cap or the screen turning off, while the dictation
  pill and Android's microphone indicator show. voxdroid does not request the notification
  permission.

The built app also carries `io.github.iteramen.voxdroid.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`,
which an AndroidX library adds: it is signature-level, so only an app signed with voxdroid's own
key can hold it. voxdroid asks for no storage, contacts, location, phone-state or other permission.

## The microphone

Holding volume-down or tapping the accessibility button starts recording at once. Recording stops
when you press volume-down, tap the button or the pill's Stop, or by itself after 5 minutes
(`VoxdroidA11yService.kt` — `CAP_MS`); the audio is then sent for transcription (below).

During a call — a phone call, or any app's call that sets Android's audio mode to a call — a
start records nothing, and an idle pill says `call in progress — not recorded`, unless you turn on
**Dictate during a call**. The audio mode is read at the start only and is not kept; a call that
begins during a recording does not stop it (`Delivery.kt` — `isCallMode`, `admit`).

Tapping the pill's ✕ while recording, or the screen turning off at any stage, abandons the
dictation: a recording in progress is dropped unsent and nothing is typed (`Take.kt` — `cancel`).
An upload already under way is not cancelled, nor is its one retry when no response came back: it
completes at your server and its reply is thrown away. No request the dictation had not yet
started is sent afterwards — not the cleanup pass, not a resend to a language lane or without
`vad`, and not a retry on your default server (`Routed.kt`, `SttClient.kt` — the `current`
checks; `VoxdroidA11yService.kt` — `transcribe`).

Audio is captured straight into memory and never written to a file or a database
(`Recorder.kt` — `AudioRecorder`). If a transcription fails, the audio stays in memory so that
Retry can send it again without re-recording; it is discarded when the text is delivered, when you
tap ✕, when the screen turns off and when a new dictation starts (`Take.kt` — `audio`).

## Where the audio goes

voxdroid ships with no server address: every address below is blank until you set it
(`Settings.kt` — `sttBaseUrl`, `polishBaseUrl`, `laneBaseUrl`). Once you do:

- **Your speech server.** The recording is uploaded to the address you set,
  `POST {address}/audio/transcriptions` — once, or twice to that same address when no response
  arrives at all (`SttClient.kt` — `send`). Your glossary, if you set one, goes with every upload as
  the `prompt` field. A recording longer than 60 seconds carries `vad=true`, which asks the server
  to skip silence; if the server answers such an upload with an HTTP error, the recording is sent
  once more without it (`SttClient.kt` — `transcribe`, `isLongTake`). When the language is set
  explicitly (below), its two-letter code goes as the `language` field; otherwise the list of
  candidate language codes may go as `language_candidates`.
- **A language lane.** If you set a separate server address for a language and that language is
  sent explicitly, the recording goes to that lane. If the lane answers with no text, the same
  recording goes once more to your default speech server (`Routed.kt`). On auto-detect the
  recording goes to your default server first; if its reply detects, with probability 0.5 or more,
  a language that has a lane, the same recording is sent again to that lane, and to the default
  once more if the lane answers with no text (`Routed.kt` — `resendTo`; `SttClient.kt` —
  `detectedLanguage`). Detection is read only while the no-speech gate is on. Every server in
  this list is one you configured.
- **The cleanup server (optional).** If the cleanup pass is on and you set its address and model,
  the transcript text — not the audio — is sent as one request to
  `POST {address}/chat/completions`, with the cleanup instructions and your glossary
  (`Polish.kt` — `PolishClient`, `systemPrompt`; `VoxdroidA11yService.kt` — `polisher`). A blank
  transcript, or one shorter than *Minimum words*, is not sent. The pass is on by default and
  does nothing until both fields are set.
- **Bearer tokens.** A token you set travels only as an `Authorization: Bearer` header
  (`Http.kt` — `boundedPost`): the speech token to your speech server, the cleanup token to the
  cleanup server, and to a lane its own token if it has one, else the speech token if you turned on
  that lane's *Send the STT token* switch, else nothing. Until you touch that switch, it counts as
  on only for a lane whose scheme and host:port are written the same as your speech server's
  (`Endpoints.kt` — `laneToken`, `sameServer`).
- **The status check.** While voxdroid's main screen is open, it sends
  `GET {speech server address}/models` every five seconds, with no token and no body, to show
  whether the server answers (`EndpointProbe.kt` — `probe`; `SettingsActivity.kt` — `reprobe`). It
  stops when the screen goes to the background.

voxdroid contacts no server operated by its developer, and no address other than the ones you
set. The servers you configure keep or delete what they receive under their own policies, which
voxdroid neither sets nor sees; check the policy of any server you did not set up yourself.

## What stays on the device

voxdroid keeps its settings in its own private storage, in two files: `secrets`, holding only the
bearer tokens, and `voxdroid`, holding everything else — the server addresses, the language
settings, the glossary, the other options and how high above the keyboard you last dropped the
pill (`Settings.kt`). The tokens are excluded from Android's cloud backup and from device-to-device
transfer (`res/xml/data_extraction_rules.xml`); everything else is included in Android's normal
backup, so it can follow you to a new phone, where the tokens are entered again. Uninstalling the
app removes both files.

## What is not collected

- No recording is kept: it is dropped when a dictation finishes or is discarded, and is never
  written to disk.
- No transcript is kept after it is typed. It exists only in memory and is never written to a file
  or a database, nor to a log except in the debug-level case under [Logs](#logs).
- There is no record of what you dictated, or when; no account, no profile.
- voxdroid has no analytics, telemetry, advertising or crash-reporting code. What ships is the
  app, the Kotlin standard library, AndroidX Preference and Material Components
  (`app/build.gradle.kts` — `dependencies`).

## Network security

voxdroid sends to `https://` addresses, and to plain `http://` only on the phone itself
(loopback), unless you turn on **Allow insecure (http) endpoints** on the Speech page. That switch
is off by default and asks you to confirm before it turns on (`Endpoints.kt` — `insecureBlocked`).
While it is off, an `http://` address to any other host is refused when you enter it, before every
request, and by the status check — for the speech server, every lane and the cleanup server alike.
With it on, your audio, glossary, transcript and bearer tokens cross the network unencrypted to an
`http://` server; unless that path is protected otherwise, for example by Tailscale or WireGuard,
anyone who can observe it can read them. HTTPS uses Android's own certificate checks.

## The accessibility service

voxdroid's accessibility service reaches the following, and only when stated
(`res/xml/a11y_entry.xml`; `FieldAccess.kt`):

- **The focused text field**, looked up when a dictation starts and again when its text arrives:
  whether it is editable, whether it is a password field (by its masking or its input type,
  including the input type your keyboard reports), and whether it is the same field as at the
  start. Dictation is refused in a password field, and text is never typed or copied into one. A
  dictation whose field changed is not typed: it is dropped, or copied to the clipboard if you
  chose that. The lookup fetches the active window's root without prefetching its contents and
  asks it for the focused field only; the accessibility node cache is off, and the field is held
  until the dictation is typed or cancelled, including through an error and its retry
  (`FieldAccess.kt` — `focused`; `VoxdroidA11yService.kt` — `startField`).
- **The character before the cursor, and any selected text**, read when the text is typed, to
  decide whether a space goes in front (`FieldAccess.kt` — `insert`).
- **The volume-down key.** The service receives every hardware key event, acts on volume-down
  only, passes every other key on untouched and keeps none (`VoxdroidA11yService.kt` —
  `onKeyEvent`).
- **The keyboard window's position**, to place the pill above it. The only accessibility event the
  service subscribes to is a change in the windows on screen (`Pill.kt` — `keyboardTop`).
- **The keyboard's current language**, to choose the transcription language (`FieldAccess.kt` —
  `keyboardTag`; `TakeLanguage.kt` — `takeFor`).
- **The audio mode**, at the start, to refuse a dictation during a call.
- **The clipboard**, written only in the mode you chose, with the dictated text, which Android's
  clipboard preview shows (`FieldAccess.kt` — `copy`).

None of the text read from the field or around the cursor is written to a log, a file or the
network. What leaves the device is what [Where the audio goes](#where-the-audio-goes) lists.

## Logs

At the default level, voxdroid logs no transcript and no token. It logs: a language code from
your settings that was dropped as malformed (`Settings.kt` — `warn`); that a language lane returned
no text and the default server was asked, naming the language code (`Routed.kt`); once, that the
cleanup pass is on with no address or model (`VoxdroidA11yService.kt` — `POLISH_UNCONFIGURED`);
the HTTP status when a long recording was resent without `vad` (`SttClient.kt`); and how many
control or format characters were removed from a dictation, never the text (`Commit.kt` —
`droppedNote`).

At debug level, a failed transcription or cleanup request logs one line describing the failure
(`VoxdroidA11yService.kt` — `answer`, `answerPolished`), and release builds keep that line
(`app/build.gradle.kts` — `isMinifyEnabled = false`). It can include the server's hostname from a
network error and, for a reply the app could not use, its first 200 characters — which, from a
server answering in the wrong shape, could be your dictation. The request's own bearer token is
replaced with `[redacted]` in that line (`Http.kt` — `redact`, `bodyPrefix`); a token the server
re-encodes before echoing it is not recognised.

## Children

voxdroid is not directed at children and keeps nothing about who is using it.

## Changes to this policy

Changes to this policy are announced in the repository's
[CHANGELOG](https://github.com/iteramen/voxdroid/blob/main/CHANGELOG.md), with a new effective date
here.

## Contact

Questions about this policy or your data: byovox.licensing@gmail.com. Non-sensitive questions are
also welcome as [GitHub issues](https://github.com/iteramen/voxdroid/issues).
