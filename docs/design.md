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
| `CaptionProcessor.kt`, `CaptionAudio.kt`, `CaptionMediaDecoder.kt` | voice-message processing: acquisition, fingerprint, Ogg/Opus decode, 16 kHz WAV, STT and cleanup; used by the private caption controller ([below](#voice-message-processing)) |
| `SettingsActivity.kt`, `Readiness.kt`, `EndpointProbe.kt` | the launcher: a status card whose button fixes the first problem (`problems`), the settings page rows, the privacy row |
| `SectionActivity.kt`, `SectionFragment.kt`, `TryActivity.kt` | one page per `res/xml/section_*.xml`, a language's filled in code (`languagePage`); a field for a first take |
| `app/src/{debug,release}/…/SelfEnable.kt` | debug only: turning the service on with `WRITE_SECURE_SETTINGS` granted over adb |

**Threads.** No coroutines and no background jobs. The main looper (`main`) runs the service
callbacks, every `Take` transition, the pill and the tick, hold and cap timers. One single-thread
executor (`worker`) runs STT, lanes and cleanup on blocking `HttpURLConnection` and posts each
result back. `voxdroid-recorder` reads the microphone; each request has a watchdog timer (`Http.kt`
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

### Private caption integration

`CaptionController` owns one `CaptionSession` on the accessibility service's main thread and a
single worker with one bounded queued job. **Enable voice-message captions** defaults off and asks
before observing source metadata. With dictation idle, `CaptionAccessibility` inspects only the
supported foreground header outside message lists, then selected-note metadata when selection
chrome is present. Source package, window and header are RAM-only context, not cache identity.
Native English Share controls are supported initially; an unknown or multi-selection is refused.

Select one downloaded note, tap the accessibility button, and choose **Caption with Voxdroid** in
Android's chooser. The controller invokes Telegram Share or WhatsApp's overflow → Share; it never
chooses a recipient, taps Send, inserts into the composer or records the microphone. Normal
volume-down dictation is unchanged, and the accessibility button still stops an active dictation.

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
never returned. An HTTP call already under way runs to its own timeout.

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
  permissions, `PolishPromptTest` reads the prompt file. The caption cache and the voice-message
  pipeline run behind a fake decoder, STT and cleanup, on synthetic Ogg headers and PCM;
  `CaptionPolicyTest` reads the backup rules and limits foundation callers to the explicit
  caption integration; import code cannot record or deliver text to a field.
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
- Handset acceptance of the private caption integration, including app-version-specific selection,
  cache reuse and origin invalidation; see [testing.md](testing.md#private-caption-integration).
