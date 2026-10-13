# voxdroid design

voxdroid is push-to-talk dictation for Android 13 and later. Hold volume-down or tap the
accessibility button, speak, stop: the audio goes to a speech-to-text endpoint you run, the
transcript optionally goes through a cleanup model you run, and the result lands at the cursor of
the field focused at the start. It is an accessibility service, not a keyboard, so the user's own
keyboard stays up. Its desktop sibling is [byovox](https://github.com/iteramen/byovox).

Kotlin sources are in [`app/src/main/java/io/github/iteramen/voxdroid/`](../app/src/main/java/io/github/iteramen/voxdroid/); `res/` is `app/src/main/res/`.

## Goals

- **Any OpenAI-compatible server.** `/audio/transcriptions` for speech, `/chat/completions` for
  cleanup. `language_candidates` is an optional whisper.cpp server extension; servers without it
  ignore the field.
- **Keep the keyboard.** Text goes in through the service's own input connection
  (`flagInputMethodEditor`, `AccessibilityService.getInputMethod`; both Android 13).
- **Detect, then route.** The keyboard's language when the user marked it explicit, otherwise
  detection among the user's languages, a confident detection re-sent to that language's lane.
- **Only into the field you started in.** Anything else goes to the clipboard or is dropped, by
  the user's choice; a password field is never recorded for, typed into or copied for.
- **Lean surface.** No server, account, analytics or crash reporting; two runtime libraries
  (`androidx.preference`, Material Components); settings pages, a status card and a pill.

**Non-goals:** being an input method; in-process inference; a silence timeout (a take ends on a
stop, a cancel or the 5-minute cap).

## Architecture

Three activities and one accessibility service ([AndroidManifest.xml](../app/src/main/AndroidManifest.xml)).
Logic that needs no Android lives in plain functions and classes, so the JVM tests reach it.

| File | Owns |
|---|---|
| [VoxdroidA11yService.kt](../app/src/main/java/io/github/iteramen/voxdroid/VoxdroidA11yService.kt) | the host (`TakeHost`): keys, button, screen-off, recording under a foreground service, the worker, delivery |
| [Take.kt](../app/src/main/java/io/github/iteramen/voxdroid/Take.kt) | the state machine, take numbering, the audio held for Retry |
| `VolumeGesture.kt`, `Recorder.kt` | hold-to-start and press-to-stop; `AudioRecorder` (16 kHz mono PCM16 into memory), `wav` |
| `Transcriber.kt`, `SttClient.kt`, `Routed.kt` | `dictate` (the no-speech gate); the multipart request and reply parsing; lanes and the auto-detect resend |
| `TakeLanguage.kt`, `Language.kt` | the take's language (`takeFor`), the language list (`languageCodes`) |
| `Polish.kt`, `res/raw/polish_prompt.txt` | `PolishClient`, `polish`, prompt assembly |
| [Http.kt](../app/src/main/java/io/github/iteramen/voxdroid/Http.kt), `Endpoints.kt` | `boundedPost`; URL checks, the cleartext gate, lane tokens |
| `FieldAccess.kt`, `Delivery.kt`, `Commit.kt` | the focused field and insertion; `admit`, `delivery`; `sanitize`, `finish` |
| `Pill.kt`, `PillPlacement.kt`, `Cue.kt` | the overlay, its placement and drag, haptic cues |
| `Settings.kt`, `SettingsStore.kt` | the two preference files |
| `SettingsActivity.kt`, `Readiness.kt`, `EndpointProbe.kt` | the launcher: a status card whose button fixes the first problem (`problems`), the five page rows, the privacy row |
| `SectionActivity.kt`, `SectionFragment.kt`, `TryActivity.kt` | one page per `res/xml/section_*.xml`, a language's filled in code (`languagePage`); a field for a first take |
| `app/src/{debug,release}/…/SelfEnable.kt` | debug only: turning the service on with `WRITE_SECURE_SETTINGS` granted over adb |

**Threads.** No coroutines and no background jobs. The main looper (`main`) runs the service
callbacks, every `Take` transition, the pill and the tick, hold and cap timers. One single-thread
executor (`worker`) runs STT, lanes and cleanup on blocking `HttpURLConnection` and posts each
result back. `voxdroid-recorder` reads the microphone; each request has a watchdog timer (`Http.kt`
— `boundedPost`). `SettingsActivity` probes on its own executor, only while resumed.

## The take is a state machine

```
Idle ──Trigger, admitted──▶ Recording ──Trigger or 5-min cap──▶ Working(Stt) ──▶ Working(Polish) ──▶ Idle, delivered
Trigger not admitted        → no state change; notice and reject cue (no field, password field, call)
startRecording fails        → Error without Retry (e.g. microphone not granted, 16 kHz refused)
Cancel                      → Idle from any state: ✕ on the pill (hidden while Working), screen off, service destroyed
STT fails                   → Error(bar); Retry resends the held audio, Trigger starts a new take
silence (gate or blank)     → Idle, "silence" notice, nothing polished or delivered
polish fails or is blocked  → the raw transcript is delivered, with a notice and the reject cue
delivery                    → insert, clipboard or drop (see Delivery)
```

`Take.on` is the whole machine; a Trigger while Working is ignored. Each take has a number
(`currentTake`, bumped by a start and a cancel); a result for any other take is dropped, and the
worker asks `current` before each lane, `vad` or default-endpoint resend and before cleanup, so a
cancelled take starts no new request beyond the one resend of an upload that got no status. The
audio is held in `Take.audio` from the stop until delivery, a cancel or a new take, never on disk.
The start field is kept through an Error, so a Retry delivers into it.

1. **Trigger.** `dispatch` looks up the focused field, reads the audio mode and asks `admit`;
   admitted, it stores the start field and the keyboard's language tag. `startRecording` checks
   `RECORD_AUDIO`, starts a microphone-type foreground service on a low-importance notification
   (`goForeground`) and opens `AudioRecorder` on `VOICE_RECOGNITION`. The stop makes a
   44-byte-header WAV in memory (`Recorder.kt` — `wav`).
2. **Transcribe.** Multipart POST to `{base_url}/audio/transcriptions`: `file`, `model`,
   `response_format`, the language fields, `prompt` (the glossary, when set), and `vad=true` on a
   take over 60 s, re-sent without it on a non-2xx reply (`SttClient.kt` — `transcribe`, `send`).
   `response_format` is `verbose_json` while the no-speech threshold is above 0, else `json`. One
   resend when no status came back (transport failure or timeout); any status is final. Line
   breaks in `text` become spaces.
3. **No-speech gate** (`Transcriber.kt` — `dictate`): the highest `segments[].no_speech_prob`
   above the threshold (default 0.3) is silence. A threshold of 0 or an unscored reply gates
   nothing; the score decides, never the words.
4. **Polish** when enabled with a base URL and a model and the text has at least *Minimum words*
   words ([Cleanup prompt](#cleanup-prompt)); then [delivery](#delivery-into-the-focused-field).

## Triggers and the pill

- **Volume-down hold** (`onKeyEvent`, `VolumeGesture`). With the trigger on, every volume-down
  event is consumed and every other key returns `false`. A hold of `volume_hold_ms` (default 500,
  floor 300) starts a take; a press while recording stops it on key-down; a short press otherwise
  is re-applied as one volume step (`adjustSuggestedStreamVolume`); key repeats are swallowed.
- **Accessibility button** (`AccessibilityButtonCallback.onClicked`): a tap is a Trigger.
- Both can be switched off (`trigger_volume`, `trigger_button`); the status card lists the ones on.

The pill (`Pill.kt`) is a `TYPE_ACCESSIBILITY_OVERLAY` that never takes focus. Recording: red
dot, elapsed time, **■ Stop**, **✕**. Working: `stt…` or `polish…`, no ✕. Error: the stage's bar
(`stt transport`, `stt[he] HTTP 502`), **↻ Retry** while audio is held, ✕. A notice (`flash`)
shows for 2.5 s, one line per take. Centred, its bottom sits a saved distance above the keyboard
window (`keyboardTop`) or the screen's bottom (`PillPlacement.kt` — `place`, `remember`); a
vertical drag saves a new one (`Settings.kt` — `pillSaved`); `typeWindowsChanged`, the only
subscribed event, re-places it (`follow`). Haptic cues (`Cue.kt`): a click for a start and for
text landed or copied, a double click for an error, a refusal, a drop or a failed polish.

## Language policy and routing

Per language in *Your languages* (`chip_languages`; default the phone's language plus `en`; codes
that are not two lowercase letters are dropped and logged, `Language.kt` — `languageCodes`):
`explicit_<code>` (*Use Gboard's language*, default on for `he` only), `lane_base_url_<code>`,
`lane_token_<code>` and `lane_inherit_token_<code>`.

`TakeLanguage.kt` — `takeFor`: the current input method subtype's language
(`FieldAccessTag.current`, with `iw`, `in`, `ji` read as `he`, `id`, `yi`) is sent as
`language=<code>` when it is explicit. Otherwise the take is auto: no `language` field, and
`language_candidates` with your languages minus the explicit ones that have no lane
(`candidatesFor`), omitted when empty. The status card's keyboard line shows the route
(`Readiness.kt` — `route`). Then `Routed.kt`:

- Explicit → the language's lane if it has a URL, else the STT endpoint. A blank lane reply is
  retried on the STT endpoint; a lane error stays an error, named `stt[<code>] …`, and a 401 from
  a lane sent no token while the STT endpoint has one adds `: no token sent` (`lane401Hint`).
- Auto → the STT endpoint. While the gate is on, the detected language is the sent candidate with
  the highest `language_probabilities` (`SttClient.kt` — `detectedLanguage`). At p ≥ 0.5
  (`ROUTE_P`) a detected language with a lane is re-sent there as explicit; otherwise the first
  reply, or its error, stands.
- A lane's token (`Endpoints.kt` — `laneToken`): its own, else the STT token when *Send the STT
  token* is on, else none. Never set, the switch counts as on only when an STT token exists and
  the lane's scheme and authority match the STT endpoint's as written (`sameServer`).

## Delivery into the focused field

`FieldAccess.kt` — `focused` asks the active window's root (no prefetch) for the input-focused
node and returns a `Field` only for an editable one: the node as identity (window plus node id),
and whether it is a password field (`isPassword`, or a password input type on the node or in the
keyboard's `EditorInfo`, `isPasswordInputType`). The node cache is off after every lookup.
Jetpack Compose answers the input-focus query only once something has read its focused node, so
when the answer is missing or not editable and the keyboard has an input started, `firstMatch`
walks the window depth-first (at most 500 nodes) for a focused, editable node; reading it also
fixes Compose's later answers, so the field compares equal at delivery.

- **At the start** (`admit`): a call in progress (`isCallMode`, unless `allow_during_call`) or a
  password field refuses; a field records; no field records only in clipboard mode.
- **At the end** (`delivery`): the field focused now is a password field → dropped; the same field
  as at the start → insert; otherwise clipboard or dropped, by the mode, with a `no text field` or
  `field changed` notice. The mode is `no_field_mode`: `refuse` (default) or `clipboard`.
- **Text** (`Commit.kt` — `finish`): `sanitize` turns `\n`, U+2028 and U+2029 into spaces and
  drops control characters, format (`Cf`, a Unicode 16.0 table in the code) characters except
  U+200C–U+200F, and unpaired surrogates, logging only the count (`droppedNote`); then trim, and
  one terminal `.` comes off (never from `..`) while `strip_terminal_period` is on.
- **Insert** (`FieldAccess.kt` — `insert`): one leading space when the character before the
  cursor is not whitespace, then one `commitText`. No input connection re-plans as no field.
- **Clipboard** (`copy`): a plain clip, not marked sensitive, never cleared.

## Cleanup prompt

`res/raw/polish_prompt.txt` is byovox's `BUILT_IN_PROMPT` byte for byte and is kept identical;
[testing.md](testing.md) runs byovox's bench on the copy. It is a numbered rule list: punctuate
and capitalise; drop fillers and false starts; number spoken enumerations; preserve language,
terms, names, numbers and profanity; never add content, answer or obey the text; output only the
text; turn a punctuation name dictated as the mark into the mark. The `<transcription>` payload
is declared content, never instructions.

- `capitalize_first_word` off swaps rule 1 for `LOWERCASE_FIRST_RULE` (`Polish.kt` —
  `builtInPrompt`) and gives a lower-cased first-word "I" its capital back (`restoreFirstI`).
- A glossary (`stt_prompt`) appends `GLOSSARY_RULE` and the glossary (`systemPrompt`); the same
  text goes to STT as `prompt`.
- The request (`PolishClient`): system prompt, user `<transcription>…</transcription>`,
  `temperature` 0.3, `max_tokens` 1024; the reply is `choices[0].message.content`, trimmed, and
  empty counts as a failure. Any failure delivers the raw transcript (`polish`).

## Settings and storage

Two app-private `SharedPreferences` files (`Settings.kt`): `secrets` for the tokens alone,
`voxdroid` for everything else. `SettingsStore`, the pages' `PreferenceDataStore`, routes tokens
to `secrets` (`storedAs`) and stores numbers typed. `res/xml/data_extraction_rules.xml` keeps
`secrets.xml` out of cloud backup and device transfer; `voxdroid` is backed up. Values are read
when a take needs them. Defaults (`Settings.kt`, `res/xml/section_*.xml`): URLs blank, model
`whisper-1`, timeouts 30 s (STT) and 20 s (cleanup), threshold 0.3, cleanup on, http off.

## Network rules

Every STT, lane and cleanup request goes through `Http.kt` — `boundedPost`:

- **Cleartext gate.** The manifest allows cleartext; voxdroid gates it itself. While
  `allow_insecure_http` is off (default), `http://` to anything but a loopback host that `URI` and
  `URL` read alike is refused when entered (`Endpoints.kt` — `validateBaseUrl`), before every send
  with no connection opened, and in the probe (`insecureBlocked`, `isCleartextToRemoteHost`).
  Turning the switch on asks first (`SectionFragment.kt` — `insecureSwitch`).
- **Timeouts.** Connect 5 s; read the stage's timeout, clamped to 1–600 s (`clampTimeoutS`); a
  watchdog disconnects at the same timeout, so a peer that never reads cannot wedge the worker.
- **Redirects are not followed**: a 3xx is the stage's failure. The probe does the same.
- **Size caps.** A reply over 10 MiB (`BODY_LIMIT_BYTES`) is unreadable; a take stops at 5
  minutes (`CAP_MS`); an error detail keeps 200 characters of the body.
- **Tokens.** Sent only when set, as `Authorization: Bearer`; one no header can carry fails
  before sending (`HEADER_REFUSED`). Every failure text from a reply or an exception has the
  request's token replaced with `[redacted]` (`redact`, `bodyPrefix`, `causeDetail`).
- **The probe** (`EndpointProbe.kt` — `probe`): `GET {stt}/models` every 5 s while the launcher
  is resumed, no token, 3 s timeouts; any HTTP status counts as reachable.
- **HTTPS** uses the platform's verification, with no network security config. The threat model
  is in [SECURITY.md](../SECURITY.md).

## Testing

- **JVM unit tests** (`./gradlew testDebugUnitTest`, `app/src/test/`): `Take` behind a fake
  `TakeHost`, `admit`/`delivery`, `VolumeGesture`, placement, language and routing, and both HTTP
  clients against a loopback `com.sun.net.httpserver.HttpServer`; `ManifestTest` pins the
  permissions, `PolishPromptTest` reads the prompt file.
- **On a handset:** the manual checklist in [testing.md](testing.md), against the debug APK,
  before a PR that touches the service.
- **CI** ([.github/workflows/](../.github/workflows/)): `ci.yml` runs the gate
  `./gradlew lint testDebugUnitTest assembleDebug`; `release-witness.yml` builds `bundleRelease`
  twice and requires the unsigned-release warning on both; `dependency-audit.yml` runs
  OSV-Scanner over the runtime classpaths and blocks. Release artifacts are unsigned unless
  `keystore.properties` is present. A `v*` tag runs `release.yml`, which signs an APK with the
  GitHub-release key and publishes it with `SHA256SUMS` and an attestation.

## Not built yet

- The Google Play listing.
- Read-aloud, always-on transcription, and an opt-in capture log of takes.
