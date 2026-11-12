# Security policy

voxdroid is an accessibility service. Once you enable it, it can read the focused text field of
whatever app is on screen, filters your hardware keys, opens your microphone, sends the audio to a
server you configure, and types that server's answer into the field. This file says where the
boundaries are, citing the code that enforces each one by file and symbol.

## Reporting a vulnerability

Report privately through GitHub's private vulnerability reporting:
[Security → Report a vulnerability](https://github.com/iteramen/voxdroid/security/advisories/new)
on iteramen/voxdroid. Please do not post details in a public issue.

Include the voxdroid version (the main screen shows it, with the build, at the right end of its
title row), your Android version and device, and the smallest steps that reproduce it. There is
no bounty. Expect a first response within a week.

The items under [Accepted by design](#accepted-by-design) are known; a concrete way to *break* one
of those boundaries is a vulnerability and is welcome.

## Supported versions

The latest release and `main`. Fixes land on `main` and ship in the next release; there are no
backports.

## Threat model

The user running voxdroid is trusted. Each boundary below is written from the point of view of an
attacker who is not that user.

### Accessibility service

- The focused field is looked up when a take starts and again at delivery: whether it is editable,
  whether it is a password field, and its identity (`FieldAccess.kt` — `focused`). The lookup
  fetches the active window's root with `getRootInActiveWindow(0)`, no prefetch flag set, and asks
  it for the focused input node only; voxdroid reads no text of the field itself.
- A password field is judged three ways: the field's masking (`isPassword`), a password input type
  on its node, or one in the keyboard's editor info, where Compose puts it. A take is refused in
  such a field, and one whose focused field is a password field at delivery is dropped, never typed
  or copied (`Delivery.kt` — `admit`, `delivery`, `isPasswordInputType`). A field that reports none
  of the three is not refused.
- The start field's node is held from the start until the take ends, or through a failure until a
  retry delivers or the take is cancelled (`VoxdroidA11yService.kt` — `startField`, `ended`). The
  accessibility node cache is off from the moment the service connects and is switched off again
  after every lookup, so no copy of another app's nodes is kept (`VoxdroidA11yService.kt` —
  `onServiceConnected`; `FieldAccess.kt` — `focused`).
- The service subscribes to window, window-state, content, scroll, click and long-click events.
  Caption observation uses these only when captions are enabled and dictation is idle in a
  supported foreground app (`CaptionController.observe`). While a take runs, a window change
  re-places the pill from the keyboard window's
  bounds; `keyboardTop` fetches every interactive window's info and uses the `TYPE_INPUT_METHOD`
  window's alone (`Pill.kt` — `keyboardTop`, `follow`).
- At delivery the character before the cursor and any selection are read through the service's
  own input connection, to decide on a leading space; they are not stored or sent. The text goes in
  as one `commitText`, only into the field captured at the start; with no input connection it goes
  to the clipboard or is dropped, by the user's mode (`FieldAccess.kt` — `insert`;
  `VoxdroidA11yService.kt` — `deliver`).
- Every hardware key reaches the service. Any key but volume-down returns `false`, as does
  volume-down while its trigger is off; with the trigger on, volume-down is consumed and a short
  press while idle is re-applied as a volume step. The only state kept is two booleans
  (`VoxdroidA11yService.kt` — `onKeyEvent`; `VolumeGesture.kt`).
- Dictation stores how high above the keyboard the pill was last dropped (`Settings.pillSaved`).
  Captions add transient origin/selection state in memory and optional transcript records below.
- `res/xml/a11y_entry.xml` declares `canRetrieveWindowContent`, `canRequestFilterKeyEvents`, the
  flags `flagRequestFilterKeyEvents`, `flagInputMethodEditor`,
  `flagRetrieveInteractiveWindows` and `flagReportViewIds`, and `isAccessibilityTool="true"`, on
  Play's policy that
  services "designed to help people with disabilities" may declare it
  ([Play policy](https://support.google.com/googleplay/android-developer/answer/10964491)). The
  reasoning is in [docs/play/accessibility-declaration.md](docs/play/accessibility-declaration.md).

**In scope:** text read from another app reaching a log or file, or reaching the network except
through the explicit read-aloud selection described below; any key but
volume-down consumed or kept; a take typing into a field other than the one captured at its start;
a take recording into, typing into or copying for a password field; the service subscribing to or
reading more than the declared set. **Out of scope:** that an enabled accessibility service can
read the screen; that is what the permission is.

### Microphone

- The microphone records only from a start until a stop, a cancel, the 5-minute cap, the screen
  turning off or the service being destroyed (`VoxdroidA11yService.kt` — `startRecording`,
  `stopRecording`, `CAP_MS`, `screenOff`, `onDestroy`). `RECORD_AUDIO` is checked before the
  foreground service starts.
- A take is refused at the start while Android's audio mode is a call, unless "Dictate during a
  call" is on (off by default). The mode is read at the start only, is never stored, needs no
  permission, and a call that starts during a take does not stop it (`Delivery.kt` — `isCallMode`,
  `admit`).
- The foreground service posts its notification on a low-importance channel and no notification
  permission is requested (`VoxdroidA11yService.kt` — `goForeground`); the pill and Android's
  microphone indicator show while recording.
- The recorder is released at stop. The WAV is held in memory for Retry until delivery, cancel or a
  new take, and is never written to disk (`Recorder.kt` — `AudioRecorder.stop`; `Take.kt` —
  `audio`). This memory-only rule is dictation's; voice-message processing below
  spools a received message's encoded bytes to a private file while it decodes them.

**In scope:** the microphone capturing outside a take; dictation audio written to disk; a recording
kept after delivery or cancel. **Out of scope:** what the platform's microphone arbitration does to
a take.

### Imported voice messages

Captions are a separate, off-by-default feature (`Settings.voiceCaptions`). On supported foreground
Telegram and WhatsApp conversations, the enabled controller reads the header outside the message
list and selected-note accessibility metadata, never the composer's text. The header and window
identity stay in memory. One selected voice note and an explicit accessibility-button press invoke
its native Share action; the user chooses the target in Android's chooser. Ambiguous selections
are refused. There is no inbox scan, microphone recording, field insertion or Send action
(`CaptionAccessibility`, `CaptionController`). Native Share controls initially require English UI.

`CaptionReceiveActivity` is exported for single-audio SEND intents. It requires an active bounded
selection session, one matching content URI with a read grant, and a provider whose authority and
owning package match the selected source. An unsolicited SEND, forged source or multiple attachments
is refused. The URI is consumed while the receiving activity owns the grant and never reopened
after return. MIME is a chooser hint, not a format trust decision.

The private overlay appears only after the original source window/header is verified again.
Source-view changes, app switches, Close, screen-off, a new dictation or disabling captions invalidate
the operation. Headers are not durable chat identifiers: source content, scroll, click and
window-state mutations conservatively dismiss results even if the header stays equal. An upload
already started can finish at the server, but its late reply is discarded. Copy is explicit and
marked sensitive; it does not paste. Re-transcribe requires reacquiring the original audio and
bypasses its fingerprint once. Handset acceptance is recorded separately in `docs/testing.md`.

The opt-in `.captiontest` debug APK has shell diagnostics for controlled handset checks. Its
`controls` dump returns only known control names and bounds from Voxdroid's own windows, helping
tests operate settings and panel buttons without disrupting accessibility. It prints no field,
caption, token, URI or conversation text and does not inspect other apps' controls. Normal debug
packages cannot use it; the release implementation is a no-op. No extra component or permission
is added (`HandsetTestControls`, `VoxdroidA11yService.dump`).

The processing pipeline (`CaptionProcessor`, `CaptionAudio`, `CaptionMediaDecoder`) enforces:

- **Bounded acquisition.** The stream is read once, in 64 KiB chunks, while the caller's URI grant
  is valid, and closed on every path. At most 32 MiB is accepted, counted over the bytes actually
  read; a declared size or type is never asked for. Byte 32 MiB + 1 rejects the request
  (`CaptionAudio.kt` — `captionAcquire`, `CAPTION_MAX_ENCODED_BYTES`).
- **Strict format.** The first Ogg page must carry the Opus ID header alone (`captionIsOggOpus`),
  the extractor must report one `audio/opus` track, and the decoder must produce PCM16, mono or
  stereo, at a multiple of 16 kHz. At most five minutes of decoded frames are taken, counted at the
  decoder's rate, not read from metadata. Any failure rejects the whole request; there is no
  partial caption (`CaptionNormalizer`, `CAPTION_MAX_SECONDS`).
- **Private, short-lived spool.** The encoded bytes go to `filesDir/caption_spool/<random UUID>.ogg`
  so the platform extractor can seek, a directory excluded whole from cloud backup and device
  transfer (`res/xml/data_extraction_rules.xml`). The spool is deleted once decoding ends, before
  any network request, and again on every error, cancellation and timeout. Opening the processor
  deletes spools that no running request owns, which is what a killed process leaves
  (`CaptionSpools`). The decoded audio, 16 kHz mono and at most 9.6 MB, stays in memory. No URI,
  file name, sender or chat is stored, and no audio reaches the caption cache or a log.
- **Untrusted media in the platform codec.** `MediaExtractor` and `MediaCodec` parse the received
  file in voxdroid's process. Every dequeue waits at most 10 ms with a cancellation and deadline
  check between, and both objects are released on every path. A call that blocks inside the
  platform (`setDataSource`, `configure`) is not bounded by voxdroid; a platform exception is
  `Malformed` (`CaptionMediaDecoder.kt`).
- **Requests.** The normalized 16 kHz WAV, never the Ogg bytes, goes to the same STT endpoint, lanes
  and cleanup endpoint as dictation, under the same cleartext gate and tokens, auto-detected over the
  whole *Your languages* list. Cancel, a later request, or the request's deadline stop it: no
  request is started afterwards, and a reply that arrives afterwards is dropped (`CaptionProcessor`
  — `process`; `SttClient.kt` — `send`; `Routed.kt`). An upload already under way completes at
  the server.
- **Identity.** A caption's key is the SHA-256 of the encoded bytes (`encoded-sha256-v1`) and a
  versioned SHA-256 profile of the non-secret settings that shape it. Tokens are never part of it,
  and an address with user info, a query or a fragment makes the caption uncacheable
  (`captionProfile`). The controller uses a request-start cache ticket for lookup before decoding
  and network, and writes only after the result passes its origin fence. Turning remembering off
  or Delete all invalidates old tickets, including after an off/on cycle (`CaptionCache`).

**In scope:** an unselected message accepted; a result shown after an invalidated origin; an
automatically pasted or sent caption; imported audio in a backup, a device transfer or a log; a spool outliving its
request beyond the next open; a request sent after cancel or the deadline; more than 32 MiB read or
more than five minutes decoded; a token reaching the profile. **Out of scope:** vulnerabilities in
the platform's own media extractor and codecs, which voxdroid cannot patch.

### STT and cleanup endpoints

- The transcript the server returns is **typed into the focused field**. One sanitising pass runs
  first: every `\n`, U+2028 and U+2029 becomes a space; control characters, every Unicode format
  character (category `Cf`, from a Unicode 16.0 table in the code rather than the device's ICU)
  except LRM, RLM, ZWNJ and ZWJ, and any unpaired surrogate are removed; the text is trimmed and,
  while `strip_terminal_period` is on (the default), loses one terminal `.` (never from `..`). The count of removed characters is logged, never the text (`Commit.kt` —
  `sanitize`, `finish`, `droppedNote`).
- A language lane is sent its own token if it has one, else the STT token if its switch is on,
  else none. While the switch is untouched it counts as on only for a lane with the same scheme
  and authority, as written, as the STT endpoint (`https://h` and `https://h:443` differ), and only when an STT token is set (`Endpoints.kt` —
  `laneToken`, `sameServer`).
- A blank or discarded take never reaches the cleanup endpoint (`VoxdroidA11yService.kt` —
  `transcribe`).

**In scope:** a control or format character (bidi override, zero-width, tag) reaching the field; a
newline, U+2028 or U+2029 typed as a line break; a blank or discarded take sent to cleanup; a lane
sent a token the rule above does not give it. **Out of scope:** that a malicious endpoint can type
arbitrary text into the field. **You are trusting that server as much as your own keyboard**, and
every lane is one more such server. Dropping every format character has a cost: the subdivision
flags (England, Scotland, Wales) degrade to a plain black flag, and the Arabic number signs and
the pound and piastre marks (U+0600–U+0605, U+06DD, U+0890–U+0891, U+08E2) are dropped too.

### Clipboard

Dictation clipboard mode is off by default (`Settings.kt` — `noFieldMode`). The clip is not marked sensitive,
so the system's preview shows it, and nothing clears it (`FieldAccess.kt` — `copy`). A take whose
focused field is a password field is never copied (`Delivery.kt` — `delivery`).

**In scope:** text reaching the clipboard in Refuse mode, or for a password field. **Out of
scope:** clipboard managers and clipboard sync capturing the text once you chose clipboard mode.

Caption Copy is a separate explicit action, marked sensitive (`CaptionPanel`). Android and any
clipboard manager may still retain that text; closing the caption does not clear the clipboard.

### Network

- While "Allow insecure (http) endpoints" is off, the default, plain `http://` to a non-loopback
  host is refused: when it is entered (`Endpoints.kt` — `validateBaseUrl`), before every send to
  STT, a lane or cleanup, with no connection opened (`Http.kt` — `boundedPost`), and in the status
  probe (`EndpointProbe.kt` — `probe`), all through one rule (`Endpoints.kt` — `insecureBlocked`,
  `isCleartextToRemoteHost`). The rule reads a URL as `java.net.URL`, which is what connects, reads
  it: only `https://`, or `http://` to a loopback host that `URI` reads the same, passes; a string
  `URL` cannot parse is blocked too.
- A stored URL is checked at every send, so one restored from a backup is refused too. While the
  switch is off, its row and the status card say it is blocked (`Endpoints.kt` — `urlSummary`;
  `SettingsActivity.kt` — `endpointLine`), and a cleanup pass sent to a blocked endpoint types the
  raw text with `polish blocked (plain http), raw text`.
- Turning the switch on asks first, naming the audio, the glossary, the transcript and the tokens;
  turning it off asks nothing (`SectionFragment.kt` — `insecureSwitch`). With it on, a cleartext
  STT endpoint is named on the status card, and every cleartext base URL under its own row.
- HTTPS uses the platform's verification: there is no custom `TrustManager`, `HostnameVerifier` or
  network security config. Redirects are not followed and a reply is capped at 10 MiB (`Http.kt` —
  `boundedPost`, `BODY_LIMIT_BYTES`). The status probe sends no token, and a token is sent only when
  set.

**In scope:** a request to a non-loopback `http://` address while the switch is off; one sent with
it on without the warning shown; a token sent in the probe. **Out of scope:** what a cleartext path
exposes once you turned the switch on and were told: the audio, glossary, transcript and bearer
token cross it in clear, and anyone on the path can replace the reply with text voxdroid will type.

### Secrets and backup

- **Explicit encrypted configuration export:** `ConfigBackupActivity` is non-exported and uses
  Android's document picker, without storage permissions or a permanent URI grant. It exports
  only allowed configuration keys and API tokens, with a user-entered 12–1024-character passphrase.
  Format v1 uses PBKDF2-HMAC-SHA256 (600,000 iterations, random 16-byte salt), AES-256-GCM (random
  12-byte nonce, 128-bit tag), and authenticates its versioned header. Files are limited to 2 MiB.
  Unknown versions, keys, types, corrupt files and wrong passwords are refused before preference
  writes. Export decrypts its result in memory and reads back the saved encrypted document before
  reporting success (`ConfigBackup`, `ConfigBackupActivity`).
- Export/restore never logs values or writes a plaintext staging file. Password inputs are not
  saved or autofilled; the activity and dialogs block screenshots. Mutable password/key buffers
  are cleared where possible; managed runtime strings cannot be reliably erased. The passphrase
  is not retained. Anyone possessing both the file and passphrase can recover its API tokens.
  The chosen document provider can retain the encrypted file, including in cloud storage.
- Restore validates the entire payload and refuses while dictation/caption is active. Across the
  two preference files it clears credentials first, replaces configuration, then restores the new
  credentials and checks the applied snapshot. Failures attempt rollback; interruption can leave
  tokens empty, requiring another restore, rather than exposing old tokens to new endpoints
  (`ConfigBackupStore`, `restoreConfig`). No audio, transcript cache, caption consent, Android
  permission or accessibility grant is exported or imported. Existing local caption state is not
  changed by a configuration restore.

- The tokens live in the `secrets` preferences file alone and are never logged (`Settings.kt`;
  `SettingsStore.kt` — `storedAs`). The request's own token is replaced with `[redacted]` in every
  failure text derived from a reply or an exception, before the 200-character cut (`Http.kt` —
  `redact`, `bodyPrefix`, `causeDetail`). A token the HTTP stack refuses as a header value fails
  the request with fixed text, before anything is sent (`Http.kt` — `HEADER_REFUSED`).
- `secrets.xml` is excluded from cloud backup and device transfer
  (`res/xml/data_extraction_rules.xml`). No `fullBackupContent` is declared: Android ignores it on
  Android 12 and later once `dataExtractionRules` is set
  ([behaviour changes](https://developer.android.com/about/versions/12/behavior-changes-12)), and
  voxdroid needs Android 13 (`app/build.gradle.kts` — `minSdk`).
- The caption cache is off until the user enables **Remember voice-message transcripts**,
  independently of enabling captions (`CaptionCache`, `SectionFragment`). Its directory, `filesDir/captions/`, is excluded whole from cloud backup
  and device transfer, write sidecars included (`res/xml/data_extraction_rules.xml`). A record holds
  the raw and displayed text, the cleanup outcome and the write time, under a SHA-256 digest of its
  key; no audio, profile, credential, URI or chat identity. Records expire 30 days after the write
  and nothing in the cache is logged. Turning it off or Delete all removes every record, and a
  request begun before either cannot write afterwards (`CaptionCache.kt` — `begin`, `put`).
- The voice-message spool directory, `filesDir/caption_spool/`, is excluded whole the same way
  ([Imported voice messages](#imported-voice-messages)).

**In scope:** a token in a log, an error string, an automatic backup or a device transfer, or in
plaintext in an explicit configuration export; a caption record
or spool file in a backup or a device transfer; a caption record outliving 30 days, or written by
a request begun before Delete all or turning off. **Out of scope:**
the other preferences file, which holds the addresses, languages and glossary and is backed up; a
rooted device or a debugger attached to the app's process.

### Logs

At info and warn, voxdroid logs no transcript and no token. The two `Log.d` failure lines carry at
most 200 characters of reply- or exception-derived text, which can be the dictation, with the
request's token redacted (`VoxdroidA11yService.kt` — `answer`, `answerPolished`). Release builds
keep `Log.d` (`app/build.gradle.kts` — `isMinifyEnabled = false`, no `isLoggable` guard).

**In scope:** a transcript or a token in a log at info or warn. **Out of scope:** the `Log.d` reply
text; see [Known limitations](#known-limitations).

### Enablement

A release build never writes Android's accessibility settings: when the service is off, the status
card's button opens Android's Accessibility settings, where the user turns it on
(`SettingsActivity.kt` — `render`; `app/src/release/.../SelfEnable.kt`). A debug build granted
`WRITE_SECURE_SETTINGS` over adb can turn itself on from that button: it adds only its own service
to `enabled_accessibility_services` and `accessibility_button_targets`, keeping every other entry
(`app/src/debug/.../SelfEnable.kt` — `turnOn`; `Readiness.kt` — `withService`). The release build
carries neither that permission nor that code.

**In scope:** the debug path removing or changing any other service in those lists. **Out of
scope:** Play Protect's behaviour, which voxdroid cannot see or change.

### Components

The merged manifest of both build types declares seven components: `SettingsActivity` (exported, the
launcher); `SectionActivity` and `TryActivity` (not exported); `CaptionReceiveActivity` (exported,
single-audio SEND, guarded by the session/provider/grant checks above); `VoxdroidA11yService` (exported,
guarded by `android.permission.BIND_ACCESSIBILITY_SERVICE`); and two that AndroidX adds,
`androidx.startup.InitializationProvider` (not exported; voxdroid's manifest removes its
`EmojiCompatInitializer` entry) and `androidx.profileinstaller.ProfileInstallReceiver` (exported,
guarded by `android.permission.DUMP`). `ManifestTest` pins the source manifest's permissions and
the initializer removal; [docs/testing.md](docs/testing.md#install) checks the merged manifest on
the APK.

**In scope:** any component exported beyond this list, or exported without its guard. **Out of
scope:** the shell or the system broadcasting to `ProfileInstallReceiver`.

### Supply chain

- What ships is `androidx.preference:preference-ktx` and `com.google.android.material:material`,
  pinned in `app/build.gradle.kts`; JUnit and `org.json` are test-only.
- Every build task fails on a tampered or unlisted artifact, transitive ones and build tooling
  included, checked against a sha256 in the generated `gradle/verification-metadata.xml` (no PGP).
  The Gradle wrapper sets `validateDistributionUrl=true` and `distributionSha256Sum`.
- GitHub release APKs are built by `release.yml` from the tag, signed with a key used only for
  GitHub releases (SHA-256 certificate fingerprint in [docs/sideload.md](docs/sideload.md)), and
  published with `SHA256SUMS` and a build-provenance attestation. The key lives in the
  repository's Actions secrets and on the job's runner only while it signs.
- CI runs the gate (`lint testDebugUnitTest assembleDebug`), the release witness and the
  dependency audit, all with `contents: read`, every action pinned by commit SHA
  (`.github/workflows/`). The audit runs OSV-Scanner over a lockfile of the runtime classpaths and
  blocks on any known vulnerability; build tooling is outside its scope. An exception is an
  `osv-scanner.toml` `[[IgnoredVulns]]` entry with `id`, `reason` and `ignoreUntil`.
- Debug builds are signed with the Android Gradle plugin's debug key. Release artifacts are
  unsigned without `keystore.properties`, as on CI, and CI uploads nothing.

**In scope:** a CI job with write access; a dependency added outside
[CONTRIBUTING.md § Dependencies](CONTRIBUTING.md#dependencies). **Out of scope:** a dependency
already compromised when its checksum was recorded, or one with no published advisory.

### Accepted by design

- The configured endpoint can type arbitrary text into the focused field. Sanitising removes the
  characters nobody speaks, and flattens line breaks so nothing is submitted; it cannot remove the
  sentences you would not want typed (`Commit.kt` — `sanitize`).
- An enabled accessibility service can read the screen; that is what the permission is. voxdroid
  reads the focused field as described above, and Android grants the service the ability to read
  more (`canRetrieveWindowContent`).
- Accepted risk: speech that reads as an instruction to the cleanup model, from you or from anyone
  the microphone hears, is content to clean, not a command. The prompt says so and wraps the
  transcript in `<transcription>` tags (`res/raw/polish_prompt.txt`; `Polish.kt` —
  `PolishClient`). A model that obeys anyway can only change the text typed into the field you
  chose, which you see before you send it: it has no tools and no memory between takes, and
  sanitising removes line breaks and control characters, so nothing is submitted. There is no
  output check.

### Known limitations

- **Play Protect.** Sideloaded builds of accessibility-service apps may be flagged by Play
  Protect, which can disable or remove them; install from Google Play, or turn off Play Protect
  scanning for a sideloaded build at your own risk.
- **The `Log.d` reply text.** The two debug failure lines carry at most 200 characters of the
  server's reply or of the exception that reported it, which, from a server answering in the wrong
  shape, can be the dictation. Release builds keep them.
- **A re-encoded token.** Redaction replaces the token as it was sent; a token the server
  re-encodes before echoing it, such as JSON writing `/` as `\/`, is not recognised.
- **Password fields.** The guard trusts what the app reports. A field that reports neither masking
  nor a password input type, such as a custom-drawn one, is not refused.
- **Reach of the window read.** `keyboardTop` fetches every interactive window's info to find the
  keyboard's; Android offers no narrower call.

## Hardening checklist

- Leave "Allow insecure (http) endpoints" off and point every endpoint — STT, each lane and cleanup
  — at `https://` or at loopback. If a server speaks only plain `http://`, turn the switch on only
  when every such endpoint is reached over Tailscale, WireGuard or a similar encrypted network.
- Turn a lane's "Send the STT token" switch on only for a server you trust with that token.
- Leave the Delivery mode on Refuse if a clipboard manager or clipboard sync is on.
- Turn USB and wireless debugging off when you are not using them: the `Log.d` lines carry reply
  text, and a connected computer can read the log.
- Remember the cleanup endpoint sees every non-blank transcript of at least *Minimum words* words
  while the pass is configured.

## Explicit read aloud

`ReadAloudController` provides a draggable accessibility overlay with contextual 🎤 and optional 🔊.
It replaces the requested system accessibility button; the legacy callback remains compatible.
Read aloud is off by default and enabling it explains screen access and the TTS data flow. On a
speaker press it examines at most 600 nodes from the active foreground tree, skips password
subtrees and editable text, and outlines visible read-only blocks of at least 15 characters. Those
candidate nodes/text stay in memory only until selection or cancellation. Only the block explicitly
tapped, revalidated against its original package/window and refreshed node, is sent to TTS.

The selected block is limited to 20,000 Unicode code points, split into chunks of at most 500.
Dominant Latin, Hebrew or Cyrillic script selects en/he/ru. This is script routing, not semantic
language detection. Hebrew/Russian require their own configured supported voice; no English voice
is substituted. All chunks are prepared before any request. Credentials are TTS-specific; a lane
inherits the default TTS credential only by an explicit switch. STT/cleanup credentials are unused.
`ReadAloud.kt` uses the existing HTTP consent gate, rejects URL credentials/query/fragment, follows
no redirects and never displays server bodies. Replies are bounded to 12 MiB, PCM16 WAV,
mono/stereo, 8–48 kHz and 120 seconds per chunk. Kokoro's streaming RIFF/data length sentinels are
completed only from the already bounded body; arbitrary truncation/oversize is rejected.

One worker and one pending slot bound queued text. While speaking, a content update may continue
only if the originally chosen node refreshes and still has identical text/bounds, the original
package/window, and remains visible, read-only and non-password. No replacement node is searched
for. The chosen-node reference and comparison text are cleared on cancellation/completion. Picker content changes
still invalidate its snapshot hit boxes. Scroll and source window-state changes still stop speech.
One visible glyph window keeps the screen on during synthesis/playback only; idle, picker, error,
completion and cancellation release that flag. Explicit screen-off still stops speech.

Stop, dictation start, selected-view/app change,
password focus, screen-off, audio-focus loss, settings change or destruction invalidates the
request, disconnects transport and stops playback. Late replies cannot play. A request already
received by the server may still finish there. Playback uses a mediaPlayback foreground service
and notification with Stop, not a microphone service; no microphone permission is needed. Text and
generated audio never enter files, caption history, the clipboard or dictation logs. TTS
configuration/tokens are included in explicit encrypted settings export; enable consent is not.
The consent bit lives in backup-excluded `secrets.xml`, so Android cloud/device restore cannot
re-enable screen-text access without a new opt-in.

The opt-in `.readaloudtest` debug package adds metadata-only service diagnostics: own control
bounds, candidate lengths/bounds, active/picker/playback/screen-awake flags, a count of tolerated
content updates and fixed failure classes. It emits
no selected text, endpoint, token or conversation identity. No diagnostic output is enabled in the
normal debug or release package.

**In scope:** another block sent without selection; password/editable content selected; text or
speech retained locally; a token forwarded by a redirect or without lane consent; late playback
after invalidation. **Out of scope:** server retention policies and platform accessibility metadata
that falsely identifies an unmarked password as ordinary text.

### Dictation transport cancellation

Cancelling or superseding a take invalidates its generation. The STT and dictation cleanup
HTTP watchdog checks that generation every 50 ms and disconnects an obsolete connection
on a background thread. Checks around upload/read and existing result fencing suppress
cancelled retries and late insertion. This frees the serial worker on Android; it cannot
recall bytes already received by an endpoint or guarantee that remote processing stops.
Caption STT shares this cancellation path; caption cleanup retains its request timeout.
The fixed cancellation error contains no URL, credentials or response text.
