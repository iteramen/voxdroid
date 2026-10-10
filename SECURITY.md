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
- The only event type subscribed to is `typeWindowsChanged`, and `onAccessibilityEvent` acts on
  nothing else. While a take runs, a window change re-places the pill from the keyboard window's
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
- The service stores one thing: how high above the keyboard the pill was last dropped
  (`Settings.kt` — `pillSaved`).
- `res/xml/a11y_entry.xml` declares `canRetrieveWindowContent`, `canRequestFilterKeyEvents`, the
  flags `flagRequestAccessibilityButton`, `flagRequestFilterKeyEvents`, `flagInputMethodEditor`
  and `flagRetrieveInteractiveWindows`, and `isAccessibilityTool="true"`, on Play's policy that
  services "designed to help people with disabilities" may declare it
  ([Play policy](https://support.google.com/googleplay/android-developer/answer/10964491)). The
  reasoning is in [docs/play/accessibility-declaration.md](docs/play/accessibility-declaration.md).

**In scope:** text read from another app reaching a log, a file or the network; any key but
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
  `audio`).

**In scope:** the microphone capturing outside a take; audio written to disk; a recording kept
after delivery or cancel. **Out of scope:** what the platform's microphone arbitration does to a
take.

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

Clipboard mode is off by default (`Settings.kt` — `noFieldMode`). The clip is not marked sensitive,
so the system's preview shows it, and nothing clears it (`FieldAccess.kt` — `copy`). A take whose
focused field is a password field is never copied (`Delivery.kt` — `delivery`).

**In scope:** text reaching the clipboard in Refuse mode, or for a password field. **Out of
scope:** clipboard managers and clipboard sync capturing the text once you chose clipboard mode.

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

**In scope:** a token in a log, an error string, a backup or a device transfer. **Out of scope:**
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

The merged manifest of both build types declares six components: `SettingsActivity` (exported, the
launcher); `SectionActivity` and `TryActivity` (not exported); `VoxdroidA11yService` (exported,
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
