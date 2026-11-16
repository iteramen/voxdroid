# voxdroid design

voxdroid is push-to-talk dictation for Android 13 and later. Hold volume-down or tap the
floating 🎤 control, speak, stop: the audio goes to a speech-to-text endpoint you run, the
transcript optionally goes through a cleanup model you run, and the result lands at the cursor of
the field focused at the start. It is an accessibility service, not a keyboard, so the user's own
keyboard stays up. Its desktop sibling is [byovox](https://github.com/iteramen/byovox).

Kotlin sources are in [`app/src/main/java/io/github/iteramen/voxdroid/`](../app/src/main/java/io/github/iteramen/voxdroid/); `res/` is `app/src/main/res/`.

## Goals

- **Any OpenAI-compatible server.** `/audio/transcriptions` for speech, `/chat/completions` for
  cleanup. `language_candidates` is an optional whisper.cpp server extension. A structured
  HTTP 400 rejection of that field triggers one retry with standard fields, retaining the
  user's configured languages and explicit-language requests.
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

**Easy start** is an explicit Speech-page action, not a default configuration. `EasyStart` produces
a Groq speech/cleanup snapshot after a destination/data-flow confirmation. `ConfigBackupStore`
applies it with the existing credential-first transaction while no operation is active. Old default
tokens are removed and lane token inheritance is disabled; unrelated settings and dedicated lane/TTS
tokens remain. The user enters their key separately into both token fields. Applying the preset
does not create an account, read the clipboard or send audio. The in-app guide links to the
published [setup guide](easy-start.md).

Configuration migration is explicit: `ConfigBackupActivity` uses the Android document picker to
write/read a password-protected `.vdxb` file. `ConfigBackup` defines the allowed typed settings and
tokens, bounded binary format, PBKDF2 key derivation and authenticated AES-GCM envelope.
`ConfigBackupStore` validates before mutation, clears credentials before changing endpoints, then
commits credentials and verifies the applied snapshot; failures attempt rollback, and an
interrupted restore can be retried from the encrypted file. It exports no audio, caption records,
caption consent or Android grants. It adds no runtime dependency or exported component. Normal
same-signer upgrades retain preferences without this path.

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
| `Pill.kt`, `PillPlacement.kt`, `Cue.kt`, `Tone.kt` | the overlay, its placement and drag, haptic cues; the cue tones, their mute policy (`audible`) and `TonePlayer` |
| `Settings.kt`, `SettingsStore.kt` | the two preference files |
| `CaptionCache.kt` | the opt-in, 30-day voice-message transcript cache ([below](#caption-cache)) |
| `DictationLog.kt`, `DictationCapture.kt`, `DictationLogActivity.kt`, `DictationLogView.kt`, `DictationReport.kt` | the opt-in, 30-day dictation log: storage and record format; the take's record as it is built; the viewer and its display and Copy text; the issue link and the report draft ([below](#dictation-log)) |
| `CaptionProcessor.kt`, `CaptionAudio.kt`, `CaptionMediaDecoder.kt` | voice-message processing: acquisition, fingerprint, Ogg/Opus decode, 16 kHz WAV, STT and cleanup; used by the private caption controller ([below](#voice-message-processing)) |
| `SettingsActivity.kt`, `Readiness.kt`, `EndpointProbe.kt` | the launcher: a status card whose button fixes the first problem (`problems`), the settings page rows, the privacy row |
| `SectionActivity.kt`, `SectionFragment.kt`, `TryActivity.kt` | one page per `res/xml/section_*.xml`, a language's filled in code (`languagePage`); a field for a first take |
| `app/src/{debug,release}/…/SelfEnable.kt` | debug only: turning the service on with `WRITE_SECURE_SETTINGS` granted over adb |

**Threads.** No coroutines and no background jobs. The main looper (`main`) runs the service
callbacks, every `Take` transition, the pill and the tick, hold and cap timers. One single-thread
executor (`worker`) runs STT, lanes and cleanup on blocking `HttpURLConnection` and posts each
result back. A second single-thread executor (`logWriter`) writes dictation log records.
`voxdroid-recorder` reads the microphone; each request has a watchdog timer (`Http.kt`
— `boundedPost`). `SettingsActivity` probes on its own executor, only while resumed.

## The take is a state machine

```
Idle ──Trigger, admitted──▶ [Starting] ──▶ Recording ──Trigger or 5-min cap──▶ Working(Stt) ──▶ Working(Polish) ──▶ Idle, delivered
Starting                    → only while the start tone plays; the microphone opens at its StartToneEnded
Trigger not admitted        → no state change; notice and reject cue (no field, password field, call)
startRecording fails        → Error without Retry (e.g. microphone not granted, 16 kHz refused)
Cancel                      → Idle from any state: ✕ on the pill (hidden while Working), screen off, service destroyed
STT fails                   → Error(bar); Retry resends the held audio, Trigger starts a new take
silence (gate or blank)     → Idle, "silence" notice, nothing polished or delivered
polish fails or is blocked  → the raw transcript is delivered, with a notice and the reject cue
delivery                    → insert, clipboard or drop (see Delivery)
```

`Take.on` is the whole machine; a Trigger while Starting or Working is ignored. Each take has a number
(`currentTake`, bumped by a start and a cancel); a result for any other take is dropped, and the
worker asks `current` before each lane, `vad` or default-endpoint resend and before cleanup, so a
cancelled take starts no new request beyond the one resend of an upload that got no status. The
audio is held in `Take.audio` from the stop until delivery, a cancel or a new take, never on disk.
The start field is kept through an Error, so a Retry delivers into it.

1. **Trigger.** `dispatch` looks up the focused field, reads the audio mode and asks `admit`;
   admitted, it stores the start field and the keyboard's language tag, and the start tone plays
   (`startTone`) before `startRecording`. `startRecording` checks
   `RECORD_AUDIO`, starts a microphone-type foreground service on a low-importance notification
   (`goForeground`) and opens `AudioRecorder` on `VOICE_RECOGNITION`. The stop makes a
   44-byte-header WAV in memory (`Recorder.kt` — `wav`).
2. **Transcribe.** Multipart POST to `{base_url}/audio/transcriptions`: `file`, `model`,
   `response_format`, the language fields, `prompt` (the glossary, when set), and `vad=true` on a
   take over 60 s, re-sent without it on a non-2xx reply (`SttClient.kt` — `transcribe`, `send`).
   `response_format` is `verbose_json` while the no-speech threshold is above 0, else `json`. One
   resend when no status came back (transport failure or timeout). An HTTP 400 with error code
   `unknown_param`/`unknown_parameter` and `param=language_candidates` (or Groq's exact
   ``unknown param `language_candidates` `` message with empty param) drops candidates and VAD
   for one compatibility retry. Each protocol fallback removes a field permanently for that
   take: at most two protocol resends if VAD is rejected first. Other statuses are final once
   the existing VAD fallback is exhausted. Cancellation prevents resends; endpoint, token,
   explicit language, model and prompt do not change. No provider body is shown or logged.
   Candidate constraints are unavailable on this standard-fields retry; configured languages
   still govern explicit selection and routing, and routing still requires server confidence.
   Line breaks in `text` become spaces.
3. **No-speech gate** (`Transcriber.kt` — `dictate`): the duration-weighted mean of scored
   `segments[].no_speech_prob` above the threshold (default 0.3) is silence, matching byovox.
   If any scored segment lacks usable start/end timing, or total duration is zero/non-finite,
   use the equal-weight mean. A short pause cannot veto a longer spoken take solely because
   its individual score is high. A threshold of 0 or an unscored reply gates
   nothing; the score decides, never the words.
4. **Polish** when enabled with a base URL and a model and the text has at least *Minimum words*
   words ([Cleanup prompt](#cleanup-prompt)); then [delivery](#delivery-into-the-focused-field).

## Triggers and the pill

Caption refusals and share-start failures use a separate non-focusable `Pill` overlay for four
seconds, with room for the full guidance. They do not depend on Android notification permission
or replace the recording/error pill. Caption cancellation and service destruction remove it.

- **Volume-down hold** (`onKeyEvent`, `VolumeGesture`). With the trigger on, every volume-down
  event is consumed and every other key returns `false`. A hold of `volume_hold_ms` (default 500,
  floor 300) starts a take; a press while recording stops it on key-down; a short press otherwise
  is re-applied as one volume step (`adjustSuggestedStreamVolume`); key repeats are swallowed.
- **Floating 🎤 control** (`ReadAloudController`, retaining the legacy `AccessibilityButtonCallback.onClicked` callback): a tap is a Trigger.
- Both can be switched off (`trigger_volume`, `trigger_button`); the status card lists the ones on.

The pill (`Pill.kt`) is a `TYPE_ACCESSIBILITY_OVERLAY` that never takes focus. Recording: red
dot, elapsed time, **■ Stop**, **✕**. Working: `stt…` or `polish…`, no ✕. Error: the stage's bar
(`stt timed out`, `stt[he] HTTP 502`), **↻ Retry** while audio is held, ✕; it stays until ✕,
Retry or a new take, and no refusal flashes over it (`Delivery.kt` — `flashesOver`). A notice
(`flash`), one line per take, shows for 4 s when any part of it is a failure or a refusal and for
2.5 s otherwise (`silence`, `copied to the clipboard`; `flashMs`, `isFailure`); a new take's pill
cancels its timer. A connection failure is named from the exception's type, never its text
(`Http.kt` — `reachOf`): `timed out` or `unreachable`, as the STT bar or as
`polish timed out, raw text`; any other send failure keeps `stt transport` or
`polish failed, raw text`. When the endpoint's host is in 100.64.0.0/10 or ends in `.ts.net`
(`tailnetHost`), the line ends `— Tailscale not connected?`: a guess from the host alone, never
from Tailscale's state, and never on an HTTP status, a malformed reply or a refusal.
While Recording and Working the pill's window carries
`FLAG_KEEP_SCREEN_ON` (`keepsScreenOn`), so the automatic timeout, which would send the same
`ACTION_SCREEN_OFF` as the power button and cancel, does not fire; an Error, a notice or the
pill's removal releases it, and no wake lock backs it. Centred, its bottom sits a saved distance
above the keyboard
window (`keyboardTop`) or the screen's bottom (`PillPlacement.kt` — `place`, `remember`); a
vertical drag saves a new one (`Settings.kt` — `pillSaved`); `typeWindowsChanged`, the only
subscribed event, re-places it (`follow`). Haptic cues (`Cue.kt`): a click for a start and for
text landed or copied, a double click for an error, a refusal, a drop or a failed polish.

Cue tones (`Tone.kt`), byovox's sine notes at `cue_volume` percent of full scale (default 5;
`audio_cues`, on by default, apart from `haptic_cues`): 880 Hz for 70 ms at a start, 660 Hz for
60 ms for text landed or copied, 440 Hz for 110 ms then 220 Hz for 220 ms wherever the double
click plays (`toneFor`). Nothing for a stop, a cancel, silence or an empty take. `TonePlayer`
plays a static `AudioTrack` with `USAGE_NOTIFICATION_EVENT` under
`AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`, so the notification volume and the normal speaker, headset
or Bluetooth routing apply; it plays nothing unless the ringer is normal and the interruption
filter is `ALL` (`audible`: silent, vibrate and any Do Not Disturb mute it). The start tone holds
the take at `Starting`: the microphone opens 100 ms after the track's last frame has played (or
500 ms past the tone's length if that marker never comes), so the tone is not recorded. A refused
focus, a track that will not open or a stale tone end leaves the take to start at once or be
dropped by its take number; `onDestroy` releases the track and removes the pending end.

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
  U+200C–U+200F, and unpaired surrogates, logging only the count (`droppedNote`); then trim,
  one terminal `.` comes off (never from `..`) while `strip_terminal_period` is on, and the text
  is cut to 100,000 UTF-16 units (`MAX_TYPED_CHARS`, never inside a surrogate pair).
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

Two app-private `SharedPreferences` files (`Settings.kt`): `secrets` for the tokens and the two
disclosed opt-ins (`voice_captions`, `tts_enabled`), `voxdroid` for everything else. `SettingsStore`,
the pages' `PreferenceDataStore`, routes tokens (`storedAs`) and those switches (`isConsent`)
to `secrets` and stores numbers typed. `res/xml/data_extraction_rules.xml` keeps
`secrets.xml` out of cloud backup and device transfer; `voxdroid` is backed up. Values are read
when a take needs them. Defaults (`Settings.kt`, `res/xml/section_*.xml`): URLs blank, model
`whisper-1`, timeouts 30 s (STT) and 20 s (cleanup), threshold 0.3, cleanup on, http off.

### Caption cache

`CaptionCache.kt` stores optional remembered voice-message transcripts. `CaptionController` opens
it; the Voice messages settings page controls remembering independently of enabling captions.
Dictation, its microphone and field delivery do not use it (`CaptionPolicyTest`).

- **Where.** `filesDir/captions/`, excluded as a whole directory from cloud backup and device
  transfer (`res/xml/data_extraction_rules.xml`), so the `.tmp` sidecar of an interrupted write is
  covered too. It holds `state` and one `<key digest>.json` per caption; nothing else of the app's
  is read or deleted.
- **On and off.** "Remember voice-message transcripts" is `state` existing: absent, the default,
  is off. Off deletes the directory's contents. A `state` that does not parse reads as off.
- **Key** (`CaptionKey`): the producer's fingerprint algorithm and version, the audio's SHA-256
  under it, and an opaque processing profile the producer derives without credentials. File
  names, URIs, senders, chats and screen positions are not identity. Only a SHA-256 digest of the
  key is stored.
- **Record:** format version, key digest, raw STT text, displayed text, the cleanup outcome
  (`CaptionCleanup`: `Cleaned`, `SkippedShort`, `Fallback`, `Off`) and the write time. No audio,
  profile, credential, URI or chat identity. A record over 256 KiB of UTF-8
  (`MAX_CAPTION_RECORD_BYTES`) is refused at write (`TooLarge`) and dropped at read, never cut.
- **Expiry.** 30 days from the write (`CAPTION_TTL_MS`); reads do not renew it. A prune runs at
  open, every `put` and every `get`, and drops expired, corrupt, foreign-key, future-format and
  future-dated records and any other file in the directory.
- **Fencing.** `begin()` issues a ticket naming the current epoch, a random UUID in `state`.
  Delete all and turning off replace or remove it, so a request begun before either gets `Stale`
  or `Disabled` from `put` and never writes, even after remembering is on again. A failed STT or
  a cancelled request has no text to `put`, and a blank transcript is `Invalid`.
- **Writes** go to a sidecar, are synced, then renamed over the record; a failure deletes the
  sidecar, keeps the old record and returns `Failed`. One process-wide lock orders every
  operation, so a ticket check and its write cannot interleave with Delete all.

### Dictation log

`DictationLog.kt` stores the optional per-take log; `DictationCapture` builds the record of the
take in flight from the service's existing call points. The Delivery page's **Keep a dictation
log** switch (off by default) and **Delete the dictation log** (with confirmation) control it;
**Show the dictation log** opens the viewer.

- **Where.** `filesDir/dictation_log/`, excluded whole from cloud backup and device transfer
  (`res/xml/data_extraction_rules.xml`), sidecars included. It holds `state` and one
  `<random UUID>.json` per take. On and off, Delete all, fencing by a `state` epoch, sidecar writes
  and the process-wide lock work as in the [caption cache](#caption-cache).
- **Record** (`DictationEntry`, format 1): id, take time, app version (`versionLine`), outcome
  (`LogOutcome`: `Cleaned`, `CleanupFailed`, `CleanupBlocked`, `SkippedShort`, `PolishOff`,
  `Silence`, `SttFailed`, `RecordingFailed`), raw and delivered text (either may be empty;
  delivered is empty when delivery dropped it), a failure detail, cleanup model and timeout, the
  language sent (`languageSent`: a code, `auto` or `auto:<candidates>`), the language detected
  (first scored reply, code and probability), the lane or host that answered last (`SttTrace`,
  `endpointHost`), STT and cleanup milliseconds, and events (`LogEvent`: `Retry`, `SttFailed`,
  `Transport`, `Note`). A field a stage did not produce is absent; a cleanup skipped as too short
  has no duration. No audio, token, endpoint userinfo or query, response body or exception text:
  `safeDetail` accepts only complete, known diagnostic shapes; unknown text is replaced by
  an omission marker instead of attempting partial secret redaction.
- **Capture.** `startTone` opens the record (`begin`) at take start, before recording, taking
  a ticket only if logging is already on. `transcribe` updates that record (`attempt`);
  a Retry of the same take adds a `Retry` event to the open record. Client and routing notes
  (`note`) become `Transport` events for a connection failure and its resend (`SttClient.send`),
  `Note` events otherwise. A failed STT attempt is saved at once and stays open, so a successful
  Retry rewrites the same record with the failure kept among its events. `land` saves the final
  outcome only when `Take` accepted the answer; every capture call names its take and is ignored
  for any take but the open one, so a late answer of a cancelled or earlier take writes nothing.
  Cancelling or superseding a take drops its unfinished attempt; an already-saved failure
  remains until expiry or deletion. A recording failure
  is a record of its own with a fixed detail, never the exception's text.
- **Off and Delete all** also call every open `DictationCapture` (`DictationLog.onDelete`), which
  drops the take it holds in memory; a write already queued carries the old ticket and gets
  `Stale` or `Disabled`. Neither the first transcription nor Retry acquires a new ticket;
  turning logging on during recording affects only subsequent takes. The temporary delivered
  text bridge is cleared immediately after capture, including when logging is off.
- **Expiry.** 30 days from the take time (`DICTATION_LOG_TTL_MS`). A prune runs when the service
  connects and when the settings screen opens (`openDictationLog`), at every `put` and every
  `entries`, and drops expired, future-dated, corrupt and foreign files. Writes with expired
  or future timestamps are rejected, so delayed work cannot recreate an expired record.
- **Viewer.** `DictationLogActivity` (not exported) lists `DictationLog.entries` newest first and,
  with an entry id, shows one entry: raw and delivered text side by side when the screen is at
  least 600 dp per unit of font scale, stacked otherwise, `(no text)` for an empty one, the
  metadata and the events. A serial worker reads/prunes the store; `LogViewSession` rejects
  obsolete load generations and changed deletion epochs before rendering. Resume, deletion,
  the nearest entry expiry and clock changes refresh the display; pause drops visible records.
  Turning the log off or Delete all therefore empties an open viewer without a late load
  repainting it. Copy and opening a row recheck the epoch and expiry before using its snapshot.
  `DictationLogView.kt` maps an entry to display and Copy text in fixed English; details and
  events pass `safeDetail` again and the answering name must be a lane, `default` or a bare host,
  so a record on disk is not trusted to be clean. **Copy** writes that report as a clip marked
  `EXTRA_IS_SENSITIVE`, so Android shows no preview. Transcript views do not offer a second
  system-selection Copy path. Viewing and Copy send nothing anywhere.
- **Report.** An entry's **Report** opens the same activity as an editable preview of the report
  text (`logReport`): both texts and an empty Expected block, each in a Markdown code block whose
  fence is longer than any backtick run inside it, then the metadata and events in another. That
  is the entry's Copy text too (a long entry's preview starts from it shortened, below), and it carries what a byovox `bench/polish_items.jsonl` row needs (raw
  text, language, expected); turning a report into a bench item stays manual. `reportDraft` fits
  the draft to a link under 8 KiB once percent-encoded (`REPORT_URL_MAX`): it drops the events
  first, then cuts both texts to one shared length, and says so in the body each time. Metadata
  values are bounded, so a long take still gets about half the link. Nothing leaves the phone until
  **Continue to GitHub**, which opens
  `github.com/iteramen/voxdroid/issues/new?template=cleanup-miss.md` with the title (`reportTitle`,
  the outcome only) and the edited text in the query (`reportUrl`), through an intent whose
  selector names any browser, so an app that claims github.com links does not receive it. An
  edit that grows the link past the limit is refused with its size, not cut. The app holds no
  GitHub credential and submits nothing; the person submits the issue in the browser. The draft
  (`ReportDraft`) lives in memory only, across redraws and rotation; when its entry expires or
  the log is deleted or switched off, the preview shows the gone message, the draft is dropped,
  and Copy and Continue recheck the entry first. A preview restored after its process died has
  lost the edits, so it closes back to the entry instead of showing the unedited text again.
- **Recents.** The activity turns its Recents screenshot off (`setRecentsScreenshotEnabled`):
  a thumbnail of a transcript would outlive deletion and expiry.

### Private caption integration

`CaptionController` owns one `CaptionSession` on the accessibility service's main thread and a
single worker with one bounded queued job. **Enable voice-message captions** defaults off and asks
before observing source metadata. With dictation idle, `CaptionAccessibility` inspects only the
supported foreground header outside message lists, then selected-note metadata when selection
chrome is present. Source package, window and header are RAM-only context, not cache identity.
Native English Share controls are supported initially; an unknown or multi-selection is refused.

Select one downloaded note, tap the floating 🎤 control, and choose **Caption with Voxdroid** in
Android's chooser. The controller invokes Telegram Share or WhatsApp's overflow → Share; it never
chooses a recipient, taps Send, inserts into the composer or records the microphone. Normal
volume-down dictation is unchanged, and the floating 🎤 control still stops an active dictation.

A Telegram Share click is not proof that sharing opened: an undownloaded note can expose an
enabled Share control and then stay in Telegram. A four-second start check invalidates a request
that reaches neither the native chooser nor the receiver. It shows download/retry guidance only
while the original source window is still foreground. A chooser event or live chooser root
suppresses that check, so the user can choose a target within the normal handoff deadline. Stale,
cancelled, superseded and already-reading requests are untouched (`CaptionSession`,
`CaptionController.checkTelegramShare`). No download control is clicked automatically.

`CaptionReceiveActivity` accepts only one content URI/read grant from the selected source's known
provider authority and owning package, within a 30-second selection/handoff session. It opens the
stream on the worker, finishes after acquisition and drops the grant; the processor never reopens
the URI. Receiver destruction before acquisition cancels. Direct unsolicited shares are refused.

Return must match the original source window/header within three seconds. Only then can a private
`TYPE_ACCESSIBILITY_OVERLAY` show processing/results. Header equality alone is not a durable chat
identity, so source window-state/content/click/scroll changes invalidate visible or processing
results even with an equal header. App switches, Close, lock, disable and starting dictation also
invalidate; late callbacks cannot display or save. The panel supports scroll/RTL, explicit sensitive
clipboard Copy, Close, saved-at text and raw-text cleanup fallback. Re-transcribe asks for a fresh
selection/share and bypasses the original fingerprint once, retaining no audio or expired grant.

The request snapshots settings and a cache epoch ticket. After acquisition and strict Ogg-header
validation, a matching cache hit returns before decoder/STT/cleanup. A fresh result writes only
after origin validation and with that request-start ticket. Delete all/off prevents late writes.

### Voice-message processing

`CaptionProcessor` converts one received stream into a typed `CaptionOutcome` off the main thread,
using `captionSettings(settings, prompt)`. It has no microphone, keyboard or text-field access.

**The input contract**, from controlled acquisition probes with prepared fixtures (no private
audio is kept in the repository):

- On a Pixel 10 with Android 17 (API 37), WhatsApp 2.26.42.74 supplied `audio/ogg; codecs=opus`
  from `com.whatsapp.provider.media`, and Telegram 13.1.1 supplied Ogg/Opus too. Every fixture
  decoded with `MediaExtractor` and `MediaCodec` to EOS as mono PCM16 at 48 kHz. Metadata durations
  differed slightly from the decoded frames, so the frames are what is counted.
- A native share grant was readable inside the receiver, but reopening the URI 2 s after returning
  failed: the stream is read once, at once. A controlled cold-start handoff passed for one fixture
  per app, with matching encoded fingerprints.
- These probes establish codec and acquisition facts. Subsequent isolated WhatsApp tests verified
  selection through native Share to a private caption, including an owner-confirmed real-server
  result, caching and several cancellation paths. Telegram later passed the two-note real-server
  flow, cache/refresh, several cancellation cases and multiple-selection refusal too. Undownloaded
  notes and the other named handset cases remain pending; see [the evidence](testing.md#private-caption-integration).

**The pipeline** (`process`), each stage able to end the whole request; there is no partial
caption:

1. **Acquire** (`CaptionAudio.kt` — `captionAcquire`): 64 KiB reads into a spool, hashed as they
   go, the source closed on every path. 32 MiB (`CAPTION_MAX_ENCODED_BYTES`) is accepted, byte
   32 MiB + 1 is `TooLarge`, and no declared size or type is consulted. A read or spool-write
   failure, or a spool that cannot be deleted before upload, is `ReadFailed`.
2. **Identify.** The SHA-256 of the encoded bytes, algorithm `encoded-sha256-v1`
   (`CAPTION_FINGERPRINT`), is the audio's identity. A URI, file name, sender, time, duration or
   screen position never is.
3. **Check the container** (`captionIsOggOpus`): an Ogg first page, version 0 with BOS set, whose
   only packet is `OpusHead` (RFC 7845); anything else is `Unsupported` and is never decoded.
4. **Decode** (`CaptionMediaDecoder.kt`): one `audio/opus` track through `MediaExtractor` and
   `MediaCodec`, 10 ms dequeues with the request's check between them, both objects released on
   every path. A platform exception is `Malformed`. A native call that blocks (`setDataSource`,
   `configure`) is outside voxdroid's bound; the handset rows in [testing.md](testing.md#voice-message-processing)
   cover this class, which the JVM cannot run.
5. **Normalize** (`CaptionNormalizer`): PCM16, mono or stereo, at a multiple of 16 kHz, else
   `Unsupported`; a format change mid-stream is `Unsupported`, samples before a format or half a
   frame `Malformed`. Channels are averaged and each run of `rate / 16000` frames is averaged into
   one sample (a box filter; a trailing partial run is dropped). Every decoded frame counts toward
   five minutes (`CAPTION_MAX_SECONDS`) at the decoder's rate: frame 5 min + 1 is `TooLong`. The
   output, at most 9.6 MB, is the 44-byte-header 16 kHz mono PCM16 WAV of `Recorder.kt` — `wav`. No
   frame at all is `Malformed`.
6. **Transcribe** (`captionTranscriber`): dictation's `Routed` over `SttClient`, the default
   endpoint and the lanes, with `SttLanguage.Auto` over the whole *Your languages* list, whatever
   the keyboard or the explicit flags say, and dictation's no-speech gate (`dictate`). Silence and a
   blank transcript are `Silence`; a failure is `SttFailed`.
7. **Clean up** (`captionPolisher`, `polish`): dictation's cleanup with its enabled switch, model,
   prompt, glossary, *Minimum words*, capitalization and timeout. `PolishOutcome` maps to
   `CaptionCleanup`: `Cleaned`, `SkippedShort`, `Fallback` (raw text kept) and `Off` (no pass
   configured). The displayed text goes through `finish`, so the terminal-period rule applies.

**Spool.** `filesDir/caption_spool/<random UUID>.ogg`, excluded whole from cloud backup and
device transfer (`res/xml/data_extraction_rules.xml`). It exists because `MediaExtractor` needs a
seekable source. It is deleted when decoding ends, before STT, and again in `process`'s `finally`.
`CaptionSpools` keeps the names of this process's running requests, and the sweep at open deletes
every other file: what a killed process left.

**Cancellation and deadline.** `process` supersedes any earlier request on the processor;
`cancel()` ends the running one. The check before every read, between decode buffers and after
each network stage throws `Cancelled` or `TimedOut`; `Routed` and `SttClient` get the same test as
`current`, so no resend or retry starts after it. A reply that comes back afterwards is dropped,
never returned. The shared STT transport now disconnects when this current/deadline check
fails. Caption cleanup still runs to its own timeout; dictation cleanup also receives its take
generation and is actively disconnected.

**Profile** (`captionProfile`): `caption-profile-v2:` and a SHA-256 over a length-prefixed list of
the normalization version, the STT address, model, glossary and no-speech threshold, the language
list, each lane's code and address, the cleanup address, model, prompt, *Minimum words* and
capitalization (or no cleanup), and the terminal-period switch. Tokens are left out; timeouts and the
insecure-http switch are included because they can change a raw-text cleanup fallback into cleaned text. An address with user info, a
query or a fragment returns `null`, and the caption comes back with no key: shown, never cached.
A hidden change on the server's side is the later Re-transcribe action's to refresh.

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
- **Size caps.** A reply over 1 MiB (`BODY_LIMIT_BYTES`) is unreadable; a take stops at 5
  minutes (`CAP_MS`); error details omit response bodies entirely.
- **Tokens.** Sent only when set, as `Authorization: Bearer`; one no header can carry fails
  before sending (`HEADER_REFUSED`). Failure diagnostics use only fixed classifications and
  status codes (`RESPONSE_DETAIL`, `causeDetail`); no reply or raw exception text is retained.
- **The probe** (`EndpointProbe.kt` — `probe`): `GET {stt}/models` every 5 s while the launcher
  is resumed, no token, 3 s timeouts; any HTTP status counts as reachable.
- **HTTPS** uses the platform's verification, with no network security config. The threat model
  is in [SECURITY.md](../SECURITY.md).

## Testing

- **JVM unit tests** (`./gradlew testDebugUnitTest`, `app/src/test/`): `Take` behind a fake
  `TakeHost`, `admit`/`delivery`, `VolumeGesture`, placement, language and routing, and both HTTP
  clients against a loopback `com.sun.net.httpserver.HttpServer`; `ManifestTest` pins the
  permissions, `PolishPromptTest` reads the prompt file. The caption cache and the voice-message
  pipeline run behind a fake decoder, STT and cleanup, on synthetic Ogg headers and PCM;
  `CaptionPolicyTest` reads the backup rules and limits foundation callers to the explicit
  caption integration; import code cannot record or deliver text to a field. `DictationLogTest`
  covers the log's format, capture outcomes, Retry, stale takes, expiry, deletion, the backup
  rules and redaction.
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
- Always-on transcription.
- A Report action for a dictation log entry in the viewer.
- Handset acceptance of the private caption integration, including app-version-specific selection,
  cache reuse and origin invalidation; see [testing.md](testing.md#private-caption-integration).

## Read-aloud control and pipeline

`ReadAloudController` owns the draggable 🎤/🔊 overlay. In the own Try screen, the focused editable
test field allows 🎤 only; own settings
and credential screens keep the overlay hidden. The floating-microphone toggle still applies,
and changing between own and external windows rebuilds the visible controls. No new exported
trigger is introduced. Read-only blocks are discovered only on a
speaker press, bounded to 600 foreground nodes; a second tap selects one refreshed block. Picker
and playback are mutually exclusive with dictation/caption processing. Source/window changes
invalidate them. One serial bounded worker plays validated PCM16 via `AudioTrack` under audio
focus and a mediaPlayback foreground service. A second thread requests speech through
`ReadAloud.kt` one chunk ahead of the one playing (`oneAhead`), so chunks follow without a pause. A
generation fence discards late work after cancellation; no text/audio history is created.
Content updates invalidate picker coordinates. During speech, an unrelated content update is
allowed only while the original selected node still refreshes with matching text, bounds,
package/window and visible/read-only/non-password attributes. A missing or changed node stops
the operation without selecting a replacement. Scroll and source window-state changes still
cancel. The glyph uses `FLAG_KEEP_SCREEN_ON` only during synthesis/playback, releasing it on
completion, cancellation or failure; manual screen-off remains cancellation.

Settings and encrypted portable backup scaffold en/he/ru endpoints/voices and explicit TTS-token
inheritance. English/default is configured first; HE/RU require real supported voices. Detection
is dominant script per ≤500-code-point chunk, with a 20,000-code-point total limit. A chunk ends
at a sentence end when its second half has one, otherwise at whitespace. All routes
must resolve before network begins. See SECURITY.md for byte, duration and format limits.

The TTS transport revalidates its endpoint before connection registration. Cancellation is
checked after registration, before upload, during response reads and before returning PCM. Its
watchdog polls the captured generation every 50 ms and disconnects blocked I/O off the main
thread. The total deadline also marks expiration so later phases cannot restart transport after
timeout. The controller cancels playback immediately and leaves transport teardown to that
watchdog; network I/O already admitted before cancellation, including the chunk requested ahead,
cannot be recalled from the server.

### Incomplete cleanup responses

A nonempty cleanup reply is rejected when its first choice explicitly reports a
finish_reason other than the string stop, including length, filtering, tool calls,
null or malformed values. Existing failure handling retains the complete raw transcript
and reports cleanup failure. Servers omitting finish_reason remain compatible; the client
cannot detect truncation that a server does not report. The failure message contains no
response text. The output token limit and prompt are unchanged.

### Active dictation transport cancellation

STT and dictation cleanup pass the captured take-generation check to boundedPost. Its
background watchdog checks every 50 ms and disconnects obsolete requests, including while
waiting for headers or response bytes; the existing total-timeout task remains. The caller
also checks before upload and after I/O. A cancelled STT request is not retried, and the
existing generation fence discards late results. Disconnect never runs on the UI thread.
The timer is removed in finally. Bytes already sent cannot be recalled from the server.

Desktop JVM and Android HTTP implementations differ: desktop disconnect may block behind
a body reader. JVM regressions exercise stalled headers for both clients; the APK transport
is additionally tested on Android for stalled headers and bodies (docs/testing.md).
