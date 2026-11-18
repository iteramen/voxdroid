# Manual checklist

## The 0.6.0 rows that were never run (#89) — 2026-11-18

Pixel 10, Android 17 (SDK 37), gesture navigation, Wi-Fi adb. Isolated builds from `effb625`
(0.6.4): `.captiontest` for the STT, dictation-log and configuration rows, then `.readaloudtest`
for the read-aloud and floating-control rows, each beside production. Production's settings,
tokens and service were never touched. A loopback stub over `adb reverse` gave the STT reply
shapes, a silent TTS reply and one fixture page (an English paragraph, a Hebrew paragraph, a plain
field, a password field) opened in the phone's browser; synthetic text and tokens only. Driven by
screenshots and taps, with the test service's own diagnostics; no UIAutomator. Font scale, display
density, the backup transport and the accessibility settings were recorded first and compared
equal afterwards, and both test packages were uninstalled.

- **STT reply of 1,000,000 `[`:** the pill read `stt JSON` with `↻ Retry`; the service stayed on.
- **STT reply of empty objects, 1,048,575 bytes:** the pill read `silence`; the service stayed on.
- **STT reply of 500,000 letters, no field focused, Copy to the clipboard:** the pill read `copied
  to the clipboard` and the service stayed on. The clip's length was measured by pasting it into
  the empty Try field: the cursor stood at 100,000 (`dumpsys input_method`).
- **The same reply into a focused field:** the empty Try field's cursor went from 0 to 100,000. A
  second take after that text moved it to 200,001. Both the service and the app stayed up, with
  nothing in the crash buffer.
- **Report while the entry goes away:** two windows of the test app in split screen. With a Report
  preview open in one, *Delete the dictation log* confirmed in the other: the preview read "This
  entry was deleted or has expired." at once. Report on another entry, planted with its own text,
  showed that text; switching *Keep a dictation log* off in the other window turned it to the same
  message, and `files/dictation_log` was empty.
- **Report preview at the largest sizes:** `font_scale` 2.0 and `wm density 540`. The notice, the
  report and *Continue to GitHub*, *Copy* and *Cancel* were readable and reachable by scrolling,
  with nothing clipped. Production's floating control lay over the page title, which a single
  install does not show on its own pages.
- **Copy, then paste:** Android's copy confirmation showed dots and no text. Pasting into the Try
  field gave 407 characters; the Delivered text, Expected and Details sections and `Events: none`
  were on screen, the Raw transcript section above them was not captured. The test package held no
  token then, so this says nothing about tokens.
- **Consent off a backup:** captions and read aloud switched on through their disclosures. The
  `grep -l` for `voice_captions` and for `tts_enabled` each named `shared_prefs/secrets.xml` only
  (control: `stt_base_url` named `voxdroid.xml`).
- **Backup and restore:** the test package only, through Android's local test transport (`bmgr
  backupnow`, data cleared, `bmgr restore`). `voxdroid.xml` came back and `secrets.xml` did not:
  neither consent nor the token returned, and both switches showed off. The same with the
  transport's `is_device_transfer=true`. The phone's own transport was selected again afterwards.
- **Token box and autofill:** Google's autofill service was the phone's enabled one. A synthetic
  token typed into *STT token* stayed masked, with no suggestion and no offer to save on OK or on
  leaving the page.
- **Read-aloud language token without its own URL:** a Hebrew voice and token, no Hebrew base URL,
  the token switch off. Choosing the Hebrew paragraph spoke nothing and the stub received no TTS
  request. **No message appeared**, which differs from the row. `adb logcat` showed `Suppressing
  toast from package … by user request` for each of the service's notices, the picker's hint
  included: the test package may not post notifications (the manifest declares no
  `POST_NOTIFICATIONS`), and Android then drops a toast from a package that is not in front. Filed as #106.
- **Service off after a notification Stop:** not run as written. Opening the notification shade
  ended the narration and removed its notification before Stop could be tapped (twice; the service
  was in the foreground with the read-aloud notification while it spoke). Run without that step,
  with the service switched off by taking it out of `enabled_accessibility_services` over adb while
  a dictation recorded: the pill and the control went at once, the microphone indicator within a
  second, and `dumpsys activity services` listed nothing for the package. Switched on again there
  was one control; a narration and a dictation each started with its foreground notification, and
  the dictated text landed in the page's field. Filed as #107.
- **Floating control context:** with the tap control on *Nothing on screen* and read aloud off, no
  control over the browser or the launcher; with *Floating 🎤 control* chosen again, 🎤 showed on
  returning to the browser. With the page's password field focused the control was gone
  (production's too) and came back on the plain field. On the test app's own pages it was hidden;
  on the Try page with its field focused only 🎤 showed, read aloud being on. On the lock screen
  the diagnostics read `locked=true glyph=false`. With both on, 🎤 and 🔊 showed together in the
  browser, 🔊 read the English paragraph (one stub request) and 🎤 dictated.

**Not checked:** Report on a phone with no browser (the owner's browsers were not disabled); a
real transfer to a second handset; the notification's Stop itself; the control coming back after
the lock screen, since the phone cannot be unlocked over adb. The app's version label read
`(unknown)` instead of the commit in these worktree builds.

## Android's button on a three-button navigation bar — 2026-11-18

Pixel 10, Android 17 (SDK 37), switched to three-button navigation with the accessibility button in
the bar. Same method as the section below: the isolated `.captiontest` build beside production,
the loopback stub, the Try field; settings recorded first and compared equal afterwards.

- **voxdroid button alone behind Android's button:** a ♿ tap started a take and a second stopped it,
  with the keyboard up and with it down; the text landed. It kept working after the app was
  updated in place while both services were on.
- **Android's button assigned to voxdroid itself** (a shortcut left from 0.3.0): each ♿ tap switched
  the dictation service off or on. Nothing dictated, and the card went from "Ready" to "service
  off" and back.
- **Assigned to both:** the first tap opened Android's "Choose a feature" list with the two entries.
  Choosing voxdroid made every later tap switch the service off or on; choosing voxdroid button
  dictated.
- **The pill covered the bar** with no keyboard up: the recording pill lay over Back, Home and
  Recents, and the wider "no text field" notice also over ♿. Fixed here: it rests above the bar
  (above the handle in gesture navigation), checked in both modes.
- **The card's warning,** added here: with Android's button assigned to voxdroid itself the card
  was red and named the shortcut; its button opened Accessibility settings; with that shortcut
  turned off there the card was green again and ♿ dictated.

**Not checked:** any Samsung phone. Whether One UI lets an app read which service its button is
assigned to is unknown; if it refuses, the card shows no warning.

## Tap control and voxdroid button (#98) — 2026-11-18

Pixel 10, Android 17, gesture navigation, Wi-Fi adb. The isolated `.captiontest` build ran beside
production; production's settings, token and service were never touched. Synthetic loopback
STT/cleanup (`tools/caption_test_server.py` over `adb reverse`), the app's own Try field. The
secure settings were recorded first, compared equal afterwards, and the test package was
uninstalled.

- **Upgrade:** 0.6.2 code, service enabled with no shortcut, then this build over it. The service
  stayed on, `accessibility_button_targets` stayed empty, no button appeared, and the "… button"
  service was listed Off.
- **Old switch:** a stored `trigger_button` true showed **Floating 🎤 control** chosen (card:
  "Hold volume-down · Floating 🎤 control"); false showed **Nothing on screen**.
- **Turning on voxdroid button in Settings:** its page had only the shortcut toggle. After the
  consent dialog the button appeared, bound with `capabilities=0` and `requestA11yBtn=true`.
- **Accessibility button chosen:** the 🎤 was gone and the card named the button. A ♿ tap started a
  take (pill, mic indicator), a second tap stopped it, and the text landed. The stub saw one valid
  WAV and one cleanup call.
- **Floating chosen:** a ♿ tap started nothing (no stub call). The 🎤 dictated the same way.
- **Nothing on screen:** neither control started anything (no stub call, no 🎤 window).
- **Main service off, voxdroid button on:** a ♿ tap did nothing, nothing crashed, and the card said
  "service off". It said "Ready" before the fix in this PR: the check matched any service from the
  package.
- **Try hint:** names the chosen control.

**Not checked:** volume-down on the test service. Production also filters keys, so it gets them
first, and an injected hold started nothing. This change does not touch the key path. Also not
checked: any Samsung phone, and the three-button navigation bar.

## Easy start qualification — 2026-11-14

`EasyStartTest` checks endpoint/model defaults, removal of old default tokens, disabled language
lane token inheritance, and preservation of unrelated settings/dedicated tokens. Existing
`ConfigBackupTest` covers the reused credential-first write transaction and rollback.

Native test used the separately identified `io.github.iteramen.voxdroid.readaloudtest` APK, with
synthetic preferences/tokens only and no production setting access. Passed at the existing large
font setting: Speech-page preset visible; destination/data-flow dialog present; Cancel preserves
the complete preference/secret snapshot; Apply writes the expected models/URLs and enables cleanup;
old default tokens absent; custom lane URL/dedicated token retained with inheritance off; narration
settings/token/consent and unrelated speech/language/trigger settings unchanged; Speech page refreshes.
No account signup, real API key, clipboard read or real Groq transcription was performed.

Remaining manual account-specific check: paste a Groq key into both token fields and dictate a
short non-sensitive phrase. The guide explains authentication/quota errors and custom-lane overrides.
The installation/upgrade path never invokes the preset.
## Telegram missing-download delivery check — 2026-11-14

Reconciled PR #45 with current main, including narration and the #36/#52 speech fixes.
All JVM tests, lint and the isolated debug build pass. Prepared ecosystem-bot fixtures only;
no production credentials copied, microphone permission not granted, synthetic loopback STT/cleanup.

- Downloaded five-second note: native Share chooser remained eligible beyond four seconds;
  choosing the isolated target reached `Shown`, with one normalized-WAV STT request and one cleanup
  request. No clipboard or composer action. This checks handoff/processing, not real-server quality.
- Undownloaded received note: visible native download arrow; Share opened no chooser. After four
  seconds the session was revoked, dictation stayed idle, and STT/cleanup request counts did not change.
- **Found and fixed during handset testing:** Android suppressed the original background Toast
  because notifications were disabled. Replaced it with a separate accessibility-overlay pill.
  Repeated with notifications still disabled: full download/retry text was visibly readable at the
  phone's existing large font setting. No notification or microphone grant was needed.
- Leaving Telegram before the deadline cancelled the pending guidance and made no request.
  JVM cases additionally fence stale callbacks, replacement sessions, started receivers and chooser waits.

UIAutomator reconnects the accessibility service on this phone and invalidates active sessions.
Only idle fixture discovery used it; active handoffs used screenshots and the isolated service's
metadata-only diagnostics. The same-note download-and-retry case is separate from the successful
downloaded fixture above and is not claimed here.

## Strict STT provider compatibility (#52)

JVM loopback tests reproduce Groq's structured unknown-candidates HTTP 400 and verify a
same-endpoint/token standard-fields retry, intact language list, long-take VAD interaction,
cancellation, bounded repeated rejection, explicit language and unrelated-error behavior.
This is protocol regression evidence, not a live Groq account qualification.

| Check | Expected |
| --- | --- |
| Strict provider, Auto with several configured languages | Retry succeeds without deleting the list; provider detects language without candidate constraints. |
| Explicit language | Standard `language` field retained; configured language selection still works. |
| Compatible self-hosted server | Candidates and long-take VAD still sent; no extra compatibility request. |
| Cancel before resend | No further audio upload or late delivery. |

## Dictation log (#20)

Handset checkpoint, 2026-11-14, isolated `captiontest` build from merged `684ca604`:
opt-in created an empty log; startup removed a planted 31-day-old synthetic entry while
keeping three current entries; Cancel preserved those entries; confirmed Delete removed
them and stayed enabled; off removed entries and state. The owner then started/stopped
one short Try-screen take: the loopback fixture received valid WAV, STT and cleanup ran
once each, and one `Cleaned` record contained matching synthetic raw/delivered text and
both stage durations. Delivered text correctly reflected the default terminal-period
removal. Microphone permission was verified before the take, revoked afterward, and
the original accessibility settings restored. No production configuration, credentials,
history or audio files were copied. Other rows below remain pending unless separately recorded.

Handset checkpoint, 2026-11-18, isolated `captiontest` build from `effb625`: *Report while the
entry goes away* gave its expected result for Delete and for switching the log off, each from a
second window in split screen; the Report preview stayed readable and unclipped at the largest
font and display size; the copy confirmation showed no text and a paste gave the report. *Report
on a phone with no browser* was not run: the owner's browsers were not disabled. Details in "The
0.6.0 rows that were never run (#89)" at the top.

- [ ] During Recording, Delete all or switch logging off/on before stopping: that take must
  not create a record. Enabling logging after recording has started applies only to later takes.
- [ ] After a failed take, Delete all and then Retry: no deleted record returns. A subsequent
  newly started take records normally while logging is on.

`DictationLogTest` covers the record format, capture outcomes, Retry, stale takes, expiry,
deletion, backup rules and redaction on the JVM; `DictationLogViewTest` covers the viewer's
newest-first order, empty states and the display and Copy text of cleaned, silent, failed and
retried entries, credentials excluded. `LogViewSessionTest` covers expiry and stale-load/deletion
generations. The checkpoint above covers only its named handset cases; other rows remain unrun. Check
records in *Delivery → Show the dictation log*, or with `adb shell run-as <debug package> ls
files/dictation_log` and `cat` of one entry on a debug build, then delete transient copies.

| Check | Expected |
| --- | --- |
| Fresh install, Delivery page | *Keep a dictation log* is off; a take creates no `files/dictation_log` entry. |
| Switch on, one cleaned take | One record: raw and delivered text, `Cleaned`, language sent, answering host, STT and cleanup milliseconds, app version. |
| Silent take, and a take with the STT server down | A `Silence` record with empty text; a `SttFailed` record with a fixed detail and no text. Both listed in the viewer, each text shown as *(no text)*. |
| STT server down, then up, Retry | One record, rewritten to the final outcome, still listing the `SttFailed` event and a `Retry` event. |
| Connection dropped mid-upload (server killed after accept) | A `Transport` event on that take's record; no other take's record changes. |
| Switch off while a take is in STT | Every record gone; the late answer still lands in the field but writes no record, even after switching on again before it arrives. |
| Delete the dictation log, confirm | Confirmation dialog first; every record gone, switch stays on, an in-flight take writes nothing. Cancel deletes nothing. |
| Retention | A record dated over 30 days earlier (clock moved forward, or a planted entry) is gone after the service restarts or the settings open. |
| Backup | `adb shell bmgr backupnow` with the log on: no `dictation_log` file in the backup set; device transfer excluded by the packaged rules. |
| Viewer list | *Show the dictation log* with the log off says it is off; on and empty, says no takes in 30 days. After three takes: newest at the top, each row its time, outcome and text on at most two lines. |
| Viewer detail | Tap a cleaned take: raw and delivered text, outcome, details (no token, URL path or query), events or *None*. A retried take lists its `SttFailed` and `Retry` events in order. |
| Copy | In an entry, Copy: Android's copy confirmation shows no transcript preview; pasting gives the report (metadata, events, raw and delivered text) with no token. |
| Large text and long transcripts | Font size and display size at maximum: list rows and the entry stay readable, the entry scrolls to Copy, nothing clipped. A take of several minutes scrolls whole in the entry. |
| Rotation | Portrait: raw above delivered. Landscape at default font: side by side. Rotating keeps the list or the open entry. |
| Delete all while viewer is open | The Delete row sits under the viewer in the same task, so this needs a second voxdroid window (split screen, where the launcher offers one); mark it unreachable on a device without. Open an entry, Delete the dictation log in the other window: the entry turns to *deleted or expired* at once; back shows the empty list. Without a second window: Delete all, then reopen the viewer: empty. |
| Switch off while viewer is open | Same with *Keep a dictation log* off: the open list or entry empties at once; reopening the viewer says the log is off. No `files/dictation_log` entry remains. |
| Report preview | In an entry, Report: an editable preview opens with the notice that Continue sends the text to GitHub inside the link; no browser opens. For a short entry its text is what the entry's Copy gives. Cancel or back returns to the entry and nothing was sent. |
| Report: Copy | Edit the preview (remove a word), Copy: pasting gives the edited text exactly. |
| Report: Continue | With a GitHub app installed too: Continue to GitHub opens a browser, not that app, on a new voxdroid issue with the title and the edited body in editable fields, Hebrew and Cyrillic intact; nothing is submitted. Close the tab without submitting: never file test content. |
| Report: long entry | A take of several minutes: the preview says how many characters of each text are shown and that events were omitted, Expected and Details are intact, and Continue opens the issue form. Paste a page of extra text and Continue: refused with the link's size, no browser. |
| Report: rotation and return | Edit the preview, rotate, switch to another app and back: the edit is still there. |
| Report: process killed | With this build's accessibility service off (Android does not kill a process that holds an enabled one; otherwise use Developer options → *Don't keep activities*): edit the preview, Home, `adb shell am kill <package>`, return to the app: the entry is shown, not a preview with the edit undone. Report again starts a fresh draft. |
| Recents | With the list, an entry or the preview open, open Recents: voxdroid's card shows no transcript text. |
| Report while the entry goes away | With the preview open, Delete the dictation log or switch the log off in a second window, or let a synthetic entry expire: the preview turns to *deleted or expired*; reopening Report on another entry starts from that entry's own text. |
| Expiry while viewer is open | Use a synthetic entry close to its 30-day boundary. Leave its detail visible: expiry clears it without leaving/returning; Copy cannot export expired text. A clock change also refreshes it. |

## Encrypted configuration migration (0.3.0)

JVM coverage (`ConfigBackupTest`) checks a typed Unicode/token/lane round-trip, per-export salt and
nonce, wrong passphrase, header/ciphertext/tag tampering, truncation and size bounds, allowlisted
keys/types/ranges, consent exclusion, password bounds, endpoint/credential write order, rollback,
retry and validation before mutation. Synthetic tokens only. Export also decrypts its generated
payload in memory and reads back the actual encrypted document before reporting success;
restore compares the applied configuration with its decrypted snapshot.

Handset checklist (record actual results below; these rows alone are not passes):

| Check | Expected |
| --- | --- |
| Debug migration update | Same application ID and signer; version increases; existing settings/tokens retained. |
| Export to Downloads | Password/confirmation entered on phone, saved file survives uninstall, saved-and-verified status. No values or password in workstation logs. |
| Incorrect passphrase/corrupt file | Error, no preference mutation and no crash; a valid retry works. |
| Restore | Endpoints, tokens, cleanup, language lanes, triggers, cues and delivery settings match; confirmation follows snapshot comparison. |
| Busy restore | Refused while a dictation or caption is active; active configuration untouched. |
| Cancel/recreate picker | Cancelling/rotating/leaving does not write plaintext or change settings; retry available. |
| Signed reinstall | Public APK hash/certificate checked; encrypted backup recovered before old signer removed; restore succeeds. Android grants/caption consent separately enabled. |
| Consent off a backup | Turn on *Enable voice-message captions* and read aloud, then on a debug build `adb shell "run-as <package> sh -c 'grep -l voice_captions shared_prefs/*.xml'"` and the same for `tts_enabled`: each names `shared_prefs/secrets.xml` only. |
| Upgrade over a build that stored caption consent in `voxdroid.xml` | Captions are off after the upgrade; once the service has connected, the command above no longer names `voxdroid.xml`; turning the switch on shows the disclosure again and captions then work. |
| Read-aloud language token without its own URL | Hebrew with a voice and a token, no Hebrew base URL, the token switch off: reading Hebrew is refused with a message naming the missing URL, and the English server receives no request. |
| Read-aloud notices with notifications not allowed | Notifications not allowed for voxdroid, in another app: 🔊 while a dictation runs, 🔊 over a page with no text, 🔊 over a page with text (the *pick the text* hint), and a refused or failed read each show their message in full on a pill over that app, which ✕ closes and which goes on its own after a few seconds. |
| Read-aloud pick hint goes with the picker | In another app, 🔊 over a page with text, then close the picker each way: pick a paragraph, tap 🔊 again, tap outside every choice, switch to another app. Each time the *pick the text* hint goes at once. A read that fails after the pick (server off) still shows its failure message for its full few seconds. |
| Token box and autofill | Enter a token on the Speech page with an autofill service enabled: no offer to save it. |

Recorded 2026-11-18 on `effb625` (details in "The 0.6.0 rows that were never run (#89)" at the top):

- *Consent off a backup*: as expected, with read aloud switched on as well. A backup and restore of
  the isolated package through Android's local test transport, in its backup mode and in its
  device-transfer mode, brought back neither consent. A transfer to a second handset was not run.
- *Read-aloud language token without its own URL*: differs. Hebrew was not read and the English
  server received no request, but no message appeared: Android suppressed the service's toast. Filed as #106.
- *Token box and autofill*: as expected, with Google's autofill service enabled.

Recorded 2026-11-19 on `69bbee3`, Pixel 10 (Android 17), isolated `readaloudtest` build beside
production, a loopback stub and one synthetic page in the phone's browser. The test package could
not post notifications (`cmd appops get` read `POST_NOTIFICATION: ignore`):

- *Read-aloud language token without its own URL*: as expected. Choosing the Hebrew paragraph
  showed "Read aloud: the he token needs a he base URL; to use the English endpoint, switch on its
  token instead" in full on a pill over the browser, nothing was read, and the stub received no
  speech request (five runs).
- *Read-aloud notices with notifications not allowed*: as expected. Each message showed in full on
  a pill over the browser: 🔊 while a dictation recorded in the page's field (*Finish the current
  dictation, voice caption or call first.*), 🔊 over a page with no text (*No visible read-only
  text block found…*), 🔊 over the page with text (the *pick the text* hint), the refused Hebrew
  read above, and a failed read with the stub answering 500 (*Read aloud: TTS returned HTTP 500*).
  Each was gone in a screenshot about five seconds later. ✕ closed the refusal: gone 1.6 s after
  the paragraph was chosen, where the same run without the tap still showed it. During the dictation the notice lay
  over the recording pill, covering its Stop and ✕ until the notice went.

Recorded 2026-11-19 on `cb2408d`, Pixel 10 (Android 17), isolated `readaloudtest` build beside
production, over Android's Display settings page, a loopback stub as the speech server:

- *Read-aloud pick hint goes with the picker*: as expected. With the picker left open, the *pick
  the text* hint was still up in a screenshot finished 4.4 s after the 🔊 tap. Closing the picker
  about 1.3 s after that tap took the hint with it each way: no pill in a screenshot taken within
  0.8 s of picking a paragraph (the narration started), within 0.5 s of Home bringing the launcher
  in front, and within 1.2 s of a tap outside every choice and of a second tap on 🔊. With the
  speech server unreachable (the port mapping removed), the pick showed *Read aloud failed. Check
  the TTS server, network and Tailscale.* instead: still up in a screenshot taken 3.1 to 3.9 s
  after the pick and gone in one taken 4.2 to 5.1 s after it. That held when the pick came 3.4 s
  after 🔊, with the hint's own time nearly up.

Everything here needs a handset; the JVM tests (`./gradlew testDebugUnitTest`) cover the logic
that runs without one. Run this against the debug APK (`./gradlew assembleDebug`) before a PR that
touches the service. The boxes stay unticked: this is the template for each run, not a record of
one.

The rows use placeholder servers: an STT endpoint at `https://whisper.example.ts.net/v1`, the same
server over plain http at `http://whisper.example.ts.net:8770/v1`, a Hebrew lane at
`https://whisper.example.ts.net/he/v1`, and a cleanup endpoint on a chat server of your own.
Replace them with yours. A row that uses an `http://` endpoint and does not name **Allow insecure
(http) endpoints** assumes it is on.

## Install

- [ ] sideload the debug APK: `adb install -r app/build/outputs/apk/debug/app-debug.apk`
- [ ] in WSL, `~/Android/Sdk/build-tools/36.0.0/aapt2 dump permissions app/build/outputs/apk/debug/app-debug.apk` lists
      `android.permission.RECORD_AUDIO`, `android.permission.INTERNET`,
      `android.permission.FOREGROUND_SERVICE`, `android.permission.FOREGROUND_SERVICE_MICROPHONE` and
      `android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK` as `uses-permission` lines, and one more pair of lines for
      `io.github.iteramen.voxdroid.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` (a `permission:` and a
      `uses-permission:`), AndroidX's own signature-level permission. The debug APK also lists
      `android.permission.WRITE_SECURE_SETTINGS` (`SelfEnable`, debug only); the release build's
      merged manifest does not
- [ ] in WSL, `~/Android/Sdk/build-tools/36.0.0/aapt2 dump xmltree --file AndroidManifest.xml
      app/build/outputs/apk/debug/app-debug.apk` lists exactly these components, with these
      `android:exported` and `android:permission` values: activities `SettingsActivity`
      (exported, the launcher), `SectionActivity`, `TryActivity`, `ConfigBackupActivity` and
      `DictationLogActivity` (not exported) and `CaptionReceiveActivity` (exported); service
      `VoxdroidA11yService` (exported, `android.permission.BIND_ACCESSIBILITY_SERVICE`); provider
      `androidx.startup.InitializationProvider` (not exported); receiver
      `androidx.profileinstaller.ProfileInstallReceiver` (exported, `android.permission.DUMP`). A
      component beyond these, or an exported one without its permission, fails the row; `SECURITY.md`'s
      Components section is the same list
- [ ] optional, and not before the Settings rows for the microphone below, which need it ungranted:
      `adb shell pm grant io.github.iteramen.voxdroid android.permission.RECORD_AUDIO` — the
      status card's **Grant the microphone** is what asks for it otherwise
- [ ] the icon is a white microphone with a stem and a base on black, on each surface that draws
      it: the launcher, the app drawer, Settings, Apps, Android's accessibility button when it is
      the chosen tap control and the shortcut is on (its icon is the app icon, as the service
      declares none), and Settings, Accessibility's entry for the
      service if Android draws one there (note whether it does). Under the launcher's mask nothing
      is clipped; with themed icons on (Settings, Wallpaper and style) the same microphone shows
      tinted to the wallpaper
- [ ] open **voxdroid** from the launcher and open **Speech**: the STT endpoint, and the token if
      the endpoint wants one. Nothing is cached across dictations, so no settings edit below
      needs a force-stop after it — an edit lands on the next dictation

## Settings

The main screen: a status card and five section rows (Speech, Triggers, Delivery, Cleanup,
Languages), then a **Privacy policy** row.

- [ ] the main screen fits one screen without scrolling, in light and dark; with everything in
      place its card is green, harmonized with the wallpaper, whatever the wallpaper's colour, and
      the card's text and dot are readable in light and dark; with a problem the card is red and
      its text is readable likewise
- [ ] the main screen's title row shows the build at its right end, small, as `<versionName> (<short commit>)`
      (`0.1.0 (` and seven hex digits for a build made where git answers, `0.1.0 (unknown)` where
      it cannot, such as a worktree built from WSL), in a debug build and in a release build,
      baseline-aligned with the title; the screen still fits without scrolling, and at the largest
      Display size (not the font size) the title and the version each stay on one line
- [ ] at the largest font size, each status-card label stays apart from its value (no
      `KeyboardEnglish`), and the main screen still fits without scrolling
- [ ] the **Privacy policy** row shows below the five section rows; tapping it opens the browser at
      `https://iteramen.github.io/voxdroid/privacy`, and the page loads. With no browser on the
      phone, a message names the URL and the app does not crash. With `privacy_policy_url` blanked
      in `strings.xml` for the run, the row is hidden
- [ ] each of the five section rows opens its page with its title in the toolbar, and Back returns
      to the main screen
- [ ] everything in place (service on, microphone granted, endpoint reachable): the card reads
      **Ready to dictate**; the endpoint line says `reachable` with the latency, then the host,
      and `plain http` after it for an `http://` host that is not loopback (Allow insecure (http)
      endpoints on); the triggers line names the triggers that are on; the button reads **Try a
      dictation** and opens a field — dictate into it with the volume-down hold or the
      chosen tap control, and the text lands; the instruction above the field is whole and at
      body text size, the field focused
- [ ] release build: turn the service off in Settings → Accessibility (or over adb, as in
      [Dictation over Gboard](#dictation-over-gboard), removing the voxdroid entry) and revoke the
      microphone. The card turns red and reads **Not ready**, a line names each problem in order
      (`service off — turn on voxdroid in Accessibility settings`, then `microphone not granted`),
      and its button **Open Accessibility settings** opens Android's Accessibility settings; turn
      voxdroid on there and, within a moment of coming back, the card names only the microphone
- [ ] debug build: with the service off and no `WRITE_SECURE_SETTINGS`, the button reads **Open
      Accessibility settings** as above. Grant it with
      `adb shell pm grant io.github.iteramen.voxdroid android.permission.WRITE_SECURE_SETTINGS`:
      the button reads **Turn on**, and a tap turns the service on, brings Android's
      accessibility button back if the voxdroid shortcut was on, leaves every other enabled accessibility service on, and leaves
      the card naming only what is left
- [ ] revoke the microphone in Android's app settings: the card is red and its button reads
      **Grant the microphone**; it shows Android's dialog, denying it once asks again on the next
      press, and denying it twice turns the button into **Open app settings**, which opens
      voxdroid's page in Android's settings. Granting it there turns the card ready again. With the
      microphone revoked, a dictation shows `grant the microphone in voxdroid` on the pill and
      records nothing
- [ ] empty the STT endpoint: the card is red, the endpoint line reads `not set`, and its button
      **Set the endpoint** opens Speech
- [ ] make the endpoint unreachable with it set (stop the server, or turn off the network it is
      reached over): within about five seconds the endpoint line reads `unreachable — check the URL
      and the network`, the card is red, and the button opens Speech; restore it and the card is
      ready again
- [ ] with *Your languages* `he,ru,en`, open **Try a dictation**, switch Gboard between Hebrew and
      English with the globe key, and go back: the card's keyboard line shows the language you
      left Gboard on, in its own script, and where a take would go (`Hebrew lane` once a lane URL
      is set for it, `sent as he` without one; with Gboard on English (its switch off),
      `auto ru / en` while `he`'s switch is on and it has no lane, and `auto he / ru / en` once a
      Hebrew lane URL is set or `he`'s switch is off)
- [ ] change a field in each section — the STT model, the language list, `Minimum words` — and
      dictate without force-stopping anything: the next dictation uses all three
- [ ] with both tokens set, `adb logcat` at the default level over one dictation contains neither
      of them; neither does the summary under either token field, which reads `Set` and never the
      value
- [ ] paste a token with a line break inside it into the STT token box (if the box keeps the
      break), then dictate: the pill shows the STT failure, the service stays up, and `adb logcat`
      at every level contains no part of the token
- [ ] open each token's edit box (STT, Cleanup, and a listed language's own token): the value is
      dots, a show toggle at the end of the box reveals it and a second tap hides it again, the
      hint above the box still reads, and OK and Cancel still save and discard; the summary under
      the row reads `Set` whichever way the toggle was left; leave it revealed, Cancel, reopen:
      dots again
- [ ] on a fresh install (clear the app's storage), **Allow insecure (http) endpoints** sits
      directly under the STT endpoint on the Speech page and is off
- [ ] turn **Allow insecure (http) endpoints** on: a dialog asks first and names the audio, the
      glossary, the transcript and the bearer tokens, and Tailscale or WireGuard; **Cancel** leaves
      the switch off, and so does Back or a tap outside the dialog; **Turn on** turns it on.
      Turning it off asks nothing
- [ ] with the switch off, type `http://whisper.example.ts.net:8770/v1` into the STT endpoint: a
      toast says plain http:// is off, to use https:// or turn on Allow insecure (http) endpoints,
      and the old value is still there; `http://127.0.0.1:8770/v1` and
      `https://whisper.example.ts.net/v1` are accepted
- [ ] with the switch on, set the STT endpoint to `http://whisper.example.ts.net:8770/v1`: it is
      accepted, and the summary under it is the URL, then the warning line ("Plain http: your
      audio, transcript and token cross the network in clear"); a take lands. The warning goes for
      `http://127.0.0.1:8770/v1` and for an `https://` URL, where the summary is the URL alone
- [ ] with that URL stored, turn the switch off: the URL stays, the summary under it turns to the
      URL, then `Blocked: plain http is off…` at once, the main screen's card is red with
      `plain http blocked` after the host, its problem line reads `plain http is off: use https://
      or turn on Allow insecure (http) endpoints` in place of `endpoint unreachable — check the URL
      and the network`, the server's log shows no request from the probe, and a take fails with
      `stt: insecure endpoint blocked` on the pill and nothing in the server's log. With a Cleanup
      endpoint on an `http://` non-loopback host as well, a take on an `https://` or loopback STT
      endpoint lands the raw text with `polish blocked (plain http), raw text`, while a cleanup
      endpoint that is down still shows `polish failed, raw text`; the Cleanup row and a lane's
      `http://` row read blocked when their pages open
- [ ] at the default font size, **Allow insecure (http) endpoints** on the Speech page and a
      language page's `Send the STT token to the <code> lane` show whole, wrapped if needed, with
      no ellipsis
- [ ] debug build, switch off: with `adb shell run-as`, write each of `http://<lan host>:8770/v1|`,
      `url:http://<lan host>:8770/v1` and `http://127.0.0.1?@<lan host>:8770/v1` in turn as
      `stt_base_url` in `shared_prefs/voxdroid.xml`, then force-stop and reopen the app and make a
      take: each fails without reaching the LAN host (its server log shows nothing). The JVM tests
      read these forms with the JDK's `java.net.URL`; this row is what shows Android's does the same
- [ ] on a fresh install the language list reads the phone's language then `en` (`he,en` on a
      Hebrew phone, `en` on an English one) and is stored only once edited; set it to `he,ru,en`:
      the Languages page shows three language rows, `עברית (he)`, `русский (ru)` and
      `English (en)`, reading `Sent as he · Not set`, `Auto-detect · Not set` and
      `Auto-detect · Not set` with no lane set; each opens a page under the same toolbar title
      with four rows: "Use Gboard's language" (on for `he`, off for the others),
      `Lane URL for <code>`, `Send the STT token to the <code> lane` and
      `Token for the <code> lane`. Set `Lane URL for ru`, then edit the list to `he,en`: the `ru`
      row goes, and putting `ru` back brings its row back, its page holding what it held
- [ ] on `עברית (he)`'s page, turn "Use Gboard's language" off, set `Lane URL for he` to
      `https://whisper.example.ts.net/he/v1` and press Back: the row reads
      `Auto-detect · whisper.example.ts.net`; turn the switch back on and leave by the toolbar's
      arrow: `Sent as he · whisper.example.ts.net`
- [ ] type `whisper:8770/v1` into the STT endpoint: a toast says it is not an http(s) URL and the
      old value is still there. Same for a URL ending in `/audio/transcriptions`, whose toast says
      voxdroid appends that itself
- [ ] type `1.5` into the no-speech threshold: it is refused with the range it takes and keeps the
      value it had (the whole-number boxes take digits only, but an emptied box, or a number too
      long to hold, is refused the same way)
- [ ] on the Triggers page, type `299` into **Hold for (ms)**: a toast says `At least 300 ms.` and
      the old value is still there; the same for `0`; `300` is accepted
- [ ] the Delivery page shows **Refuse** and **Copy to the clipboard** as two radio buttons under
      the title `No text field, or the field changed`, Refuse checked on a fresh install (clear the
      app's data first); tapping Copy to the clipboard checks it and unchecks Refuse with no popup,
      and a dictation with no field focused then puts the text on the clipboard; tapping the
      checked row leaves it checked; leave the page and come back, and the choice is still there

## STT

Against a working STT endpoint:

- [ ] dictate a sentence in English: the text lands at the cursor, one line, no stray line breaks
- [ ] turn airplane mode on and dictate: the pill reads `stt unreachable` or `stt timed out`, never
      `Tailscale not connected?` for an endpoint outside the tailnet; turn the radio back on and
      tap `↻ Retry` — the same dictation lands, from the audio still held in memory
- [ ] point `STT endpoint` at a port nothing listens on and dictate: the pill reads `stt unreachable`
- [ ] point `STT endpoint` at a stub that answers 200 with 1,000,000 `[` characters and dictate:
      the pill reads `stt JSON` with `↻ Retry`, and the service is still on afterwards (the JVM
      tests cannot show this: only Android's `org.json` overflows, and a build without `parseJson`
      dies on the same stub)
- [ ] the same stub answering `{"text":"","segments":[{},{},…]}` padded to just under 1 MiB: the
      pill reads `silence`, and the service is still on
- [ ] the same stub answering `{"text": "…"}` with 500,000 letters, Cleanup off, Delivery set to
      *Copy to the clipboard* and no field focused: the pill reads `copied to the clipboard`, the
      clip holds 100,000 characters, and the service is still on
- [ ] the same reply into a focused plain text field: the field gains 100,000 characters (one more
      with the leading space), and both voxdroid and the app holding the field stay up

The four stub rows above were run on 2026-11-18 on `effb625` and each gave its expected result:
see "The 0.6.0 rows that were never run (#89)" at the top.
- [ ] point `STT endpoint` at a tailnet host (a `100.x` address or a `*.ts.net` name) and disconnect
      Tailscale, then dictate: the pill reads `stt timed out — Tailscale not connected?` or
      `stt unreachable — Tailscale not connected?`, with no token, URL or server text on it, and it
      stays with `↻ Retry` well past 4 seconds; reconnect Tailscale and tap `↻ Retry`: the same
      dictation lands
- [ ] the same tailnet endpoint, connected, answering 401 or 500: the pill reads `stt HTTP 401` or
      `stt HTTP 500` and never asks about Tailscale
- [ ] trigger in silence and stop: nothing is typed and the pill shows `silence`
- [ ] dictate for over 30 seconds, pause briefly, then continue: the speech before and
      after the pause remains when the overall score is below the configured threshold.
      Also check a mostly silent take remains filtered. JVM synthetic responses cover
      weighted scores, absent/invalid timing fallback and true-silence rejection (#36).
- [ ] point `STT endpoint` at a whisper-server started without `--vad-model` and dictate for over
      60 seconds: the text lands, and `adb logcat -s voxdroid` shows
      `stt: the server refused vad (HTTP 500); resent the take without it` once
- [ ] point `STT endpoint` at a stub that answers `POST .../audio/transcriptions` with a 302 to a
      second logging stub on another port, and dictate: the pill reads `stt HTTP 302`, and the
      second stub logs nothing

## Polish

The cleanup pass between the transcript and the commit, set on the **Cleanup** page:
`Cleanup pass`, `Cleanup endpoint`, `Cleanup model`, `Cleanup token`, `Minimum words`,
`Cleanup timeout, seconds` and `Capitalise the first word`. Every row but the last assumes
`Minimum words` at its default of 0: a raised minimum skips the pass for a short dictation, and
those rows would go green with no request sent.

- [ ] point `Cleanup endpoint` at a working chat server and dictate a filler-heavy sentence: the
      pill reads `stt…`, then `polish…`, then the cleaned text lands — punctuation added,
      `um`/`короче`/`אה` gone, and no words the speaker did not say
- [ ] `Capitalise the first word` off: the same dictation comes back with its first word
      lower-cased, and `I`, a proper noun and an acronym still capitalised; a dictation that
      starts with "I" lands with `I` even when the model lowers it
- [ ] with the `Glossary` on the Speech page set to `Grafana, Redis, Tailscale`, dictate those
      terms in a Hebrew or Russian sentence: they come back in Latin, spelled as the glossary lists
      them
- [ ] point `Cleanup endpoint` at a port nothing listens on and dictate: the pill shows `polish…`,
      **the raw transcript still lands at the cursor**, then `polish unreachable, raw text` for
      about 4 seconds — never an error state, never a lost dictation
- [ ] point `Cleanup endpoint` at a tailnet host (a `100.x` address or a `*.ts.net` name) and
      disconnect Tailscale, then dictate: the raw transcript lands, and the pill reads
      `polish timed out, raw text — Tailscale not connected?` (or `unreachable`) for about 4
      seconds; reconnect Tailscale and dictate again: the cleaned text lands with no notice
- [ ] the same tailnet cleanup endpoint, connected, answering 500: the raw transcript lands with
      `polish failed, raw text`, with no Tailscale question
- [ ] same with the cleanup endpoint answering 500, and with it answering slower than
      `Cleanup timeout, seconds`: the raw transcript lands each time
- [ ] `Cleanup pass` on with `Cleanup endpoint` (or `Cleanup model`) empty: the transcript lands
      raw, the pill never shows `polish…` and never shows an error; `adb logcat -s voxdroid`
      carries one `polish: enabled with no base url or model, stage skipped` line for the process,
      not one per dictation
- [ ] `Minimum words` 3 and a two-word dictation: the text lands as whisper wrote it and the
      cleanup endpoint's log shows no request

### The prompt is byovox's, measured not eyeballed

`app/src/main/res/raw/polish_prompt.txt` is byovox's `BUILT_IN_PROMPT` (`src/polish.rs` in
[byovox](https://github.com/iteramen/byovox)) byte for byte. A difference means the copy drifted,
and byovox's own bench is what says so.

- [ ] from a byovox checkout, against your cleanup model:
      `python bench/polish_bench.py --prompt-file <voxdroid>/app/src/main/res/raw/polish_prompt.txt`
      scores every stratum as byovox's own default run does

## Commit

The two transforms between the cleanup pass and `commitText`: sanitise, then trim and the one
terminal period. `Drop the terminal period` is the last switch on the **Cleanup** page.

- [ ] dictate two sentences with a pause between them: one line lands, with no line break in it
      and no trailing period
- [ ] dictate a question, then a sentence trailing off in an ellipsis: the `?` survives, and so do
      all three periods
- [ ] `Drop the terminal period` off and the same two sentences: the terminal period stays
- [ ] with `Cleanup endpoint` pointed at a port nothing listens on, dictate the same two sentences:
      the raw transcript lands with the same single line and the same period rule
- [ ] with Cleanup off, against a stub STT endpoint that answers with a tab inside the text,
      dictate: the text lands with the tab gone, and `adb logcat -s voxdroid` carries
      `commit: removed 1 control or format characters` and never the text; an ordinary dictation
      logs no such line

## Language

Gboard's language, each language's "Use Gboard's language" switch, and the lanes. `Your languages`
is on the **Languages** page, and a language's switch, lane URL, token switch and lane token are
on that language's own page, opened from its row there (`עברית (he)` for `he`). The rows assume
`Your languages` is `he,ru,en`.

- [ ] Gboard on עברית with the `he` switch on and a Hebrew lane URL set, dictate a Hebrew sentence
      the default endpoint mishears (for example `אני עוד רבע שעה אצלו`): the lane's version lands
- [ ] Gboard on עברית, one word (`תודה`): it still lands; if the lane returned nothing,
      `adb logcat -s voxdroid` shows `stt[he] lane returned nothing; retrying the default endpoint`
- [ ] Gboard on English with the `en` switch off, speak English: English lands and the Hebrew lane
      was not called (nothing in its access log for the request)
- [ ] Gboard on English with the `en` switch off, a Hebrew lane URL set and the no-speech gate on
      (a threshold above 0): speak Hebrew. The take lands from the Hebrew lane: the lane's access
      log shows the re-sent request, after the default endpoint's own request for the same
      recording. Then speak English and the Hebrew lane's log shows nothing new
- [ ] same setup with the no-speech threshold at 0: speak Hebrew; the default's reply lands and
      the Hebrew lane's log shows nothing (nothing is detected with the gate off)
- [ ] Gboard on русский with the `ru` switch on, speak Russian: it lands, and the request log on
      the default endpoint carries `language=ru`
- [ ] on `עברית (he)`'s page, point `Lane URL for he` at a port nothing listens on, Gboard on
      עברית, dictate: the pill reads `stt[he] unreachable` and the default endpoint was not called
- [ ] with an STT token set and the STT endpoint on a different host than the lane, leave the
      "Send the STT token to the he lane" switch on `עברית (he)`'s page alone: it reads off, "No
      token goes to this lane.". Gboard on עברית, dictate: the lane server's log shows no
      `Authorization` header, and when the lane needs one the pill reads
      `stt[he] HTTP 401: no token sent`
- [ ] same setup, turn the switch on and dictate: the lane server's log now shows the STT token's
      `Authorization` header and the pill's 401 is gone. A switch that has been tapped keeps its
      choice, so for the untouched case take a language whose switch was never tapped, with the STT
      token set, and point its lane at the STT endpoint's own host, port and scheme on its page:
      the switch reads on
- [ ] on `עברית (he)`'s page, type a token into `Token for the he lane`: the row's summary reads
      "Set", never the token; the switch greys out and reads "This lane's own token goes to it
      instead."; dictate and the lane's log shows that token and not the STT token. Clear it and
      the switch is live again
- [ ] on a language's page, edit the lane URL while the switch has never been tapped: the switch
      re-reads its computed state at once (on for the STT endpoint's host, off for another)
- [ ] change the language list to `he,en` and back: the `ru` row goes and returns, and on its
      page a switch tapped before keeps its state

## Dictation over Gboard

Turn the service on in Settings → Accessibility. Where Android does not offer a sideloaded build
there, enable it over adb: `adb shell settings get secure enabled_accessibility_services`, then
`put` back what it printed with
`:io.github.iteramen.voxdroid/io.github.iteramen.voxdroid.VoxdroidA11yService` appended (the
component alone when it printed `null`) — a bare `put` switches every other accessibility service
off. Leave `accessibility_button_targets` alone: Android's button assigned to this service switches
it off. A debug build granted `WRITE_SECURE_SETTINGS` does the same from the status card's
**Turn on**.

- [ ] in WhatsApp with text typed and Gboard up, hold volume-down: the pill appears above Gboard
      with a red dot counting from `0:00`, and Gboard stays
- [ ] speak and press volume-down once: `stt…`, then the text lands at the cursor with exactly one
      space before it, the existing text intact, and the pill gone
- [ ] a quick volume-down press while idle lowers the volume and starts nothing; holding it with
      media playing starts dictation instead of lowering the volume continuously
- [ ] tap the accessibility button, speak, tap it again: same as above
- [ ] Triggers page, tap control: an install upgraded from 0.6.2 with *Floating 🎤 control* on
      shows **Floating 🎤 control** chosen, and one with it off shows **Nothing on screen**
- [ ] upgrade from 0.6.2 with voxdroid on and no voxdroid shortcut: voxdroid stays on, Android's
      button does not appear, `accessibility_button_targets` stays as it was, and **voxdroid
      button** is listed Off in Android's Accessibility settings
- [ ] turn on **voxdroid button** there: its page has only the shortcut toggle; after the consent
      dialog Android shows its button (navigation bar or floating, per the phone)
- [ ] choose **Accessibility button**: a tap starts and a second tap stops a take; the floating 🎤
      is gone (🔊 stays while read aloud is on); the status card's triggers line names the
      accessibility button
- [ ] choose **Floating 🎤 control** with voxdroid button still on: a tap on Android's button
      starts nothing; the 🎤 dictates
- [ ] choose **Nothing on screen**: neither starts a take; volume-down still does
- [ ] turn voxdroid itself off with voxdroid button on: a tap on Android's button does nothing, and
      the status card says the service is off
- [ ] give voxdroid itself (not voxdroid button) the accessibility-button shortcut: the status card
      turns red and names that shortcut; turn the shortcut off and the card is green again
- [ ] three-button navigation, no keyboard up: the recording pill and a notice sit above the
      navigation bar; Back, Home, Recents and Android's accessibility button all take their taps
- [ ] the Try screen's hint names the chosen tap control
- [ ] the same in Keep and in one Compose field (Google Messages)
- [ ] force-stop ChatGPT, open it and tap its text box, then trigger at once: it records, no
      `no text field`
- [ ] `■ Stop` stops; `✕` discards, and nothing is typed; a vertical drag that starts on either
      button moves the pill up or down; a slightly wobbly tap still taps. Once Stop is pressed the
      pill shows no `✕` while it reads `stt…` or `polish…`; an error pill shows `✕` again
- [ ] drop the pill higher or lower, then dictate in an app with a different keyboard height: the
      pill is the same distance above Gboard's top
- [ ] the pill is centred across the screen while recording and while a notice shows (the timer's
      narrow pill and `password field — not recorded`), and stays centred when it replaces the
      timer
- [ ] a sideways drag does not move the pill sideways; a vertical drag moves it, and the next take
      is at the height where it was dropped
- [ ] a two-line notice is whole: dictate into a password field and read `password field — not
      recorded` in full, then trigger a joined pair of notes (`polish failed, raw text · field
      changed — not inserted`) and read both, with no ellipsis; at the largest font size
      `password field — not recorded` is still whole on its two lines, and the pill grows upward
      without overlapping Gboard
- [ ] time the notices with a stopwatch: `password field — not recorded`, `no text field — not
      recorded` and `field changed — not inserted` each stay about 4 seconds; `silence` and
      `copied to the clipboard` about 2.5 seconds; a joined `polish failed, raw text · copied to
      the clipboard` about 4 seconds. Start a new take while a 4-second notice shows: the timer
      replaces it and the new take's pill is not hidden when the old notice would have ended
- [ ] an error pill with `↻ Retry` (airplane mode) stays up with no timer: wait 10 seconds and it
      is still there, until `✕`, `↻ Retry` or a new take
- [ ] start in one chat, switch to another before the text arrives: `field changed — not
      inserted`, and nothing lands in either chat
- [ ] focus a View password field (`textPassword`), its text masked, and trigger: `password field —
      not recorded`, no recording; the same with the field's show-password toggle on
- [ ] the same in a Compose password field (`KeyboardType.Password`), its visibility toggle on and
      off: `password field — not recorded`, no recording
- [ ] mode Clipboard: start a take in a chat, move to a password field before the text arrives:
      `password field — not inserted`; nothing lands in either field, and the clipboard is
      unchanged
- [ ] no field focused, mode Refuse: `no text field — not recorded`; mode Clipboard: the take
      records and `copied to the clipboard`
- [ ] mode Clipboard, no field focused: after `copied to the clipboard`, Gboard's paste chip and
      the share sheet's preview show the dictated text, not `****`
- [ ] mode Clipboard, no field focused: start a take, turn airplane mode on, and stop it. At the
      error pill, set Delivery to Refuse in voxdroid, turn airplane mode off, and tap `↻ Retry`:
      `no text field — not inserted`, nothing is typed, and the clipboard is unchanged
- [ ] Gboard on עברית: the take goes to the Hebrew lane (its access log); on English: auto between
      he, ru and en (the candidates are Your languages less any whose switch is on and that has no
      lane, and with the Hebrew lane URL set the status card's keyboard line reads
      `English → auto he / ru / en`; without a lane URL, `English → auto ru / en`)
- [ ] remove `en` from `Your languages`: the status card's auto line loses `en` on its next
      refresh (`English → auto ru`, or `auto he / ru` with the Hebrew lane URL set), with no
      separate candidates setting to edit
- [ ] an endpoint that returns empty text (or only spaces), with the cleanup pass on: `silence`
      flashes, nothing is inserted, and the next take shows no `polish failed` note
- [ ] with an error pill and `↻ Retry` showing and no text field focused (mode Refuse), hold
      volume-down (or tap the chosen tap control): the pill and its Retry stay as they were; tap
      into the original field again and tap Retry, and the same take lands
- [ ] start a take, then lock the screen: nothing is typed after unlocking, and the pill is gone
- [ ] screen timeout at 30 s: dictate for over 40 s without touching the phone and stop with
      volume-down: the screen stays on throughout and the text lands
- [ ] screen timeout at 30 s, STT and cleanup timeouts at 60 s: point the STT base URL at a stub
      that holds its reply for about 40 s, dictate, stop, and leave the phone alone: the screen
      stays on through `stt…` and the text lands; the same with a cleanup stub held about 40 s
      through `polish…`
- [ ] screen timeout at 30 s: after a take lands, and after an error pill shows (airplane mode),
      leave the phone alone: the screen times out about 30 s later as usual
- [ ] while recording, tap into the field and type on Gboard: the pill still takes no focus and
      the keys land in the field
- [ ] with the cleanup endpoint configured and `Minimum words` at its default, stop a take and
      turn the screen off while the pill reads `stt…`: nothing is typed, and the cleanup server's
      access log shows no request for that dictation — the transcript of an abandoned take never
      leaves the device. The default is what makes the empty log a witness: a raised minimum skips
      the cleanup call for a short take on any build
- [ ] point the STT base URL at a stub that logs each POST and holds its reply for about 25 s,
      under the STT timeout: dictate, stop, then turn the screen off and on; start and stop a
      short second take and turn the screen off again while the first is still held: the stub
      logs exactly one POST, logcat shows `stt not sent: the take was cancelled`, and nothing is
      typed
- [ ] point the cleanup base URL at a stub that logs each POST and holds its reply for about 10 s,
      under the cleanup timeout, `Minimum words` at its default: dictate, then turn the screen off
      while the pill reads `polish…`; unlock, dictate again and let it land: the stub logs one
      POST per take, the cancelled take's text is never typed, and the second take's lands
- [ ] rotate the device mid-recording: the pill keeps counting from where it was, one dictation,
      and the stop after the rotation lands exactly one transcript
- [ ] wait out the 5-minute cap: it stops and the text lands
- [ ] let a take run to the 5-minute cap with one phrase spoken at the start, then silence: the
      text holds the phrase once, not repeated, and the server's log shows the request
- [ ] while recording, the system's microphone indicator shows and goes at the stop; no
      notification permission is requested, so no recording notification shows in the shade
      (Android's active-apps list may show the foreground service)
- [ ] during a WhatsApp call, and during a cellular call, a volume-down hold and an accessibility
      button tap each flash `call in progress — not recorded` and record nothing: no red dot, no
      timer
- [ ] turn **Dictate during a call** on (Triggers page): during the same two calls, a take
      records and its text lands
- [ ] with **Dictate during a call** off, start a take, then place or answer a call: the take
      keeps recording, and its stop lands the text
- [ ] dictate while another app records: note what happens — silence, an error on the pill, or
      audio — and file anything other than a named error
- [ ] with themed or large fonts, the pill sits just above Gboard's top edge, not under the status
      bar and not over the keyboard

## Haptic cues

A click (`CONFIRM`) when a take starts and when its text lands, a double click (`REJECT`) when it
fails, is refused or is dropped, or lands raw after a failed cleanup, played on the pill and only
while Android's **Touch feedback** is on. **Vibrate on start and done** is on the Triggers page, on
by default.

- [ ] hold volume-down in a chat: one click as the pill appears; tap the chosen tap control
      instead: the same click
- [ ] stop the take: one click as the text lands; mode Clipboard with no field focused: one click
      with `copied to the clipboard`
- [ ] turn airplane mode on and dictate: a double click with the `stt unreachable` error pill; revoke
      the microphone and trigger: a double click with `grant the microphone in voxdroid`
- [ ] trigger in a password field: a double click with `password field — not recorded`; during a
      call: a double click with `call in progress — not recorded`
- [ ] at an error pill with no text field focused (mode Refuse), trigger: a double click, and the
      pill and its Retry stay as they were
- [ ] start in one chat and switch to another before the text arrives: a double click with
      `field changed — not inserted`
- [ ] point `Cleanup endpoint` at a port nothing listens on and dictate: the raw text lands with a
      double click, not a click, and `polish unreachable, raw text` shows
- [ ] `✕` while recording, and the screen turning off while the pill reads `stt…`: nothing;
      trigger in silence and stop: nothing with `silence`
- [ ] turn **Vibrate on start and done** off: none of the rows above vibrates
- [ ] turn Android's **Touch feedback** setting off: none of the rows above vibrates
- [ ] unknown, record what happens: the done and error cues fire after the recording's foreground
      service has stopped. Run `adb shell dumpsys vibrator_manager` after a take lands and after an
      `stt unreachable` error, and note whether the latest vibrations from
      `io.github.iteramen.voxdroid` read `IGNORED_BACKGROUND`
- [ ] unknown, record what happens: the same start, done and error cues with battery saver on

## Cue tones

A high beep (880 Hz) before recording starts, a lower one (660 Hz) when text lands or is copied,
a falling pair (440 Hz, then 220 Hz) wherever the double click plays. **Tones on start and done**
and **Tone volume (%)** (default on, 5) are on the Triggers page. Run with the ringer on and Do
Not Disturb off unless a row says otherwise, and the notification volume up.

- [ ] hold volume-down in a chat: one high beep, then the pill appears and the click plays; tap the
      chosen tap control instead: the same
- [ ] stop the take: one lower beep as the text lands; mode Clipboard with no field focused: the
      same beep with `copied to the clipboard`
- [ ] airplane mode on and dictate: the falling pair with the `stt transport` error pill; point
      `Cleanup endpoint` at a dead port: the raw text lands with the falling pair, not the lower
      beep
- [ ] trigger in a password field, and with no text field focused (mode Refuse): the falling pair
      and no high beep; start in one chat and switch chats before the text arrives: the falling
      pair with `field changed — not inserted`
- [ ] trigger in silence and stop: no tone with `silence`; `✕` while recording, the screen turning
      off while the pill reads `stt…`, and Stop alone: no tone
- [ ] trigger and tap `✕` within the beep, or turn the screen off within it: no pill, no microphone
      indicator, and no tone afterwards
- [ ] turn **Tones on start and done** off: none of the rows above beeps, and the clicks still play
- [ ] **Tone volume (%)** at 0: no tone; at 5 against 50: the second is clearly louder; the setting
      survives a force stop
- [ ] tones on, **Vibrate on start and done** off: beeps and no clicks; both on: both, the click
      at the start coming once the beep has finished
- [ ] ringer on vibrate, ringer silent, and Do Not Disturb on (in each case try Priority only
      too): no tone, the clicks unchanged, and the system volumes and DND state unchanged afterwards
- [ ] with music playing: the music ducks for each beep and comes back
- [ ] the beeps come from the phone speaker with nothing connected, from a wired headset when
      plugged in, and from a Bluetooth headset when connected; a take starts and lands each time
- [ ] dictate one word on the speaker at volume 100 % against a server that logs or keeps the
      upload: the start beep is not in the uploaded audio

## Voice-message processing

The feature branch now wires `CaptionProcessor` through the private Share receiver/controller.
The acquisition probes in [design.md](design.md#voice-message-processing) prove codec/grant behavior,
not full caption acceptance. Rows below remain unrun unless explicitly marked with evidence.
Use only owner-prepared non-sensitive fixtures. In an isolated feature build substitute
`io.github.iteramen.voxdroid.captiontest` for the main package in diagnostic commands. Never export
audio, transcript records, raw provider URIs or conversation headers to test reports.

Platform decoder and normalizer (`CaptionMediaDecoder`, `CaptionNormalizer`):

- [ ] unrun: a WhatsApp and a Telegram Ogg/Opus fixture each come back `Captioned`; the WAV sent to
      a server that keeps uploads is 16 kHz mono, and its length matches the decoded frames
      divided by three, not the container's metadata duration
- [ ] unrun: a stereo Ogg/Opus fixture is averaged to mono and still transcribes
- [ ] unrun: an MP3, an AAC `.m4a` and an Ogg Vorbis file are `Unsupported`; a truncated Ogg/Opus
      file is `Malformed`, or `TimedOut` at the budget, and never runs past the budget; record which
- [ ] unrun: a fixture just over five minutes of audio is `TooLong`, one just under is `Captioned`
- [ ] unknown, record what happens: decode time for a five-minute fixture, and whether any
      `setDataSource` or `configure` call blocks for longer than a second

Cancellation, deadline and spool:

- [ ] unrun: `cancel()` during a five-minute decode returns `Cancelled` within a second, and the
      spool directory is empty afterwards
- [ ] unrun: a budget shorter than the decode returns `TimedOut`, nothing reaches the STT server's
      log, and the spool directory is empty
- [ ] unrun: a budget that ends while STT is running drops the reply, sends no lane resend and no
      cleanup request (check both servers' logs), and returns `TimedOut`
- [ ] unrun: force-stop the app mid-decode (`adb shell am force-stop io.github.iteramen.voxdroid`):
      a spool file is left; the next open of the processor deletes it
- [ ] unrun: `caption_spool` is excluded from device transfer: with a spool file planted through
      `run-as`, transfer to a second phone, and the directory is absent there

The first enabled integration (each row is that pull request's to run):

- [ ] unrun: sharing the same voice message twice yields the same `audioSha256`; two different
      messages differ
- [ ] unrun: the stream is read inside the receiving call; a reopen after returning to the
      messaging app is not attempted, and a revoked grant ends `ReadFailed`, not a hang
- [ ] unrun: a message that is not downloaded yet, and a message from a chat no longer on screen,
      each end in a refusal, never a caption of another message
- [ ] unrun: the in-app accessibility description, the listing text, `docs/privacy.md` and
      `docs/play/data-safety.md` match the shipped audio, panel and cache flow

## Private caption integration

**Supported flows and core regressions verified.** WhatsApp and Telegram evidence is recorded
below; additional fixture/platform qualifications are named explicitly. The installed main app
0.2.1 does not include this feature. The isolated
feature package is `io.github.iteramen.voxdroid.captiontest`, labelled **Voxdroid Caption Test**,
built with `./gradlew lint testDebugUnitTest assembleDebug -PcaptionTest=true`. Check the APK's
application ID before every install; a normal build overwrites the same Gradle output path.
No release tag/version bump is part of these tests. Keep the main app and its data unchanged.

Do not use the stock `uiautomator dump` between selection and caption delivery: its UiAutomation
connection suppresses other accessibility services by default, invalidating the service being
tested. Use fixture-only visual captures and request counters for the live flow. An instrumentation
inspector must explicitly preserve services with
[`FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES`](https://developer.android.com/reference/android/app/UiAutomation#FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).
Delete transient captures afterwards; never commit chat screenshots or raw hierarchy dumps.
Recheck that the prepared fixture conversation is foreground and the keyboard is hidden before
each gesture. Pause if the owner changes screens or disconnects USB. The floating accessibility
menu can move: read its current OS touch region rather than reusing coordinates. An Android
chooser hierarchy can include off-screen targets; visually verify the intended target.

`tools/caption_test_server.py` binds only loopback and never logs payloads/tokens. An owner-approved
`adb reverse tcp:18881 tcp:18881` routes the isolated package to it; it counts STT/cleanup requests
and validates 16 kHz mono PCM16 WAV. Its static test text proves transport/UI behavior, not real
speech recognition. Delay/failure controls exercise stale callbacks and cleanup fallback.
`tools/caption_test_device.py` restricts configuration/service changes to the isolated package;
copy-settings requires separate explicit approval for its RAM-only projection of speech, cleanup,
language, glossary and lane settings plus the associated tokens. The owner approved that exact
transfer for this session on 2026-11-06. Tokens remain in the test app's private storage until
cleared or uninstalled; no values are logged or saved on the workstation. Builds and installs
never copy production data automatically. Stop the server and remove the reverse
when finished. Never grant microphone permission merely to get captions working.

For both owner-prepared Telegram and WhatsApp fixture conversations (a combined row
remains unchecked until all its cases have evidence for both apps):

- [ ] Caption enable disclosure is clear; Enable and Remember default off independently.
- [ ] One received note selected → chosen tap control → native Share chooser → Caption with
      Voxdroid → private readable panel over the original chat. No composer change or Send.
- [ ] First share reaches STT then configured cleanup; server validates normalized WAV. Real
      configured STT also produces a readable result, separately from test-server static text.
- [ ] Remember on: same note twice reuses saved text/time with no extra STT or cleanup; a distinct
      note misses. Reading does not refresh the timestamp. Remember off continues fresh processing.
- [ ] Re-transcribe prompts a fresh original share, then bypasses cache once; sharing a different
      note does not label its result as a refresh of the original.
- [ ] Copy is explicit and sensitive; Close clears the panel; long/RTL text scrolls and reads.
- [ ] Delayed STT/cleanup + chat switch, app switch, scroll, Close or lock never resurrects a panel
      or saves a result after cancellation. Returning later to the original chat does not revive it.
- [ ] Same-header conversation/navigation mutations dismiss results rather than using header
      equality as durable chat identity.
- [ ] Multiple selection, selected non-voice content, unknown selection count, an undownloaded
      note and unsolicited shares refuse locally; no accidental microphone/dictation start.
- [ ] Undownloaded Telegram note: after selecting it and tapping Voxdroid, a native Share action
      that opens neither chooser nor receiver shows download/retry guidance within four seconds.
      Download manually, select again and retry: normal captioning works. An already-open chooser,
      started receiver, chat/app switch, lock or replacement request produces no stale notice.
- [ ] Cleanup failure shows the raw result with fallback notice; silence/unsupported media/errors
      remain private and never enter the composer.
- [ ] Delete all and off/on during a delayed request leave no late cache record; captions/spool
      remain excluded from backup/transfer, and normal acquisition/cancel leaves no spool.
- [ ] Volume-down dictation and stopping an active dictation with the button still work normally.
- [ ] Privacy/security/accessibility/Data safety docs and in-app description match the proven flow.

Build evidence: lint, 454 unit tests, normal debug and unsigned release assembly passed with the
handset fixes. Isolated debug assembly also passed.
The owner-invalidation and cleared-selection regressions failed before their fixes and passed
with the session guards. The missing view-ID flag regression also failed before its fix and passed
afterwards. Normal CI must confirm the final source head.
Three synthetic device-helper tests preserve unrelated services and
limit settings projection; a synthetic WAV exercised the loopback server without handset data.
Platform edge fixtures above and device transfer require separate evidence; they are not implied
by a green unit suite or the earlier acquisition probes.

### WhatsApp handset checkpoint: 2026-11-06

Pixel 10, Android 17/API 37, WhatsApp 2.26.42.74; owner-prepared self-chat voice-note fixtures.
The main app remains 0.2.1. All handset installs used the isolated package, without granting
microphone permission. Findings corrected the missing view-ID service flag, selected-toolbar
scope and clickable Share ancestor, excessive accessibility-tree scans, and wrapped panel actions.

The following checks passed across the isolated handset candidates. The final installed candidate
was v12, APK SHA-256
`4eb3fbbe49fb4520508ec2c45f84dbcef8fbfb2736a08b6834fad226985b4ce6`.
This is an APK checksum, not an audio fingerprint. The real-server confirmation and guarded
multiple-selection check ran on v12; earlier rows were not all repeated on that exact binary.

| Check | Observed result |
| --- | --- |
| One selected note → native Share → isolated target → private panel | Passed; composer unchanged visually, no outgoing message, microphone denied. Fixture server accepted 16 kHz mono PCM16 WAV; one STT and one cleanup request. |
| Remember on, repeat same note | One stored entry; repeat made no additional inference requests and did not change the saved timestamp. |
| Re-transcribe, then a distinct note | Fresh reacquisition bypassed cache; the distinct note missed and produced a separate entry. |
| Delete all, then Remember off | UI erased both entries; remembering was left off. |
| Cleanup failure | Raw transcript displayed with an explicit fallback notice. |
| Close, app switch, or source scroll during delayed STT | Late reply discarded, no cleanup request, no resurrected panel; spool empty. |
| Close during delayed cleanup | Already-started cleanup completed, but no late panel appeared; spool empty. |
| Multiple selection | Local refusal, zero STT/cleanup requests; fixture and keyboard guards checked before input. |
| Real configured speech server | Readable private caption for the selected four-second note; owner confirmed "Matches the note". Remembering remained off. |

The mock server was stopped and its ADB reverse removed before the real-server run. The real
caption and configured isolated app were left available for the owner. USB disconnected before
the final read-only file/permission inventory; that inventory is not claimed as fresh evidence.
No transcript, audio, provider URI or conversation identity is included in these reports.

Still unverified at this WhatsApp checkpoint: Telegram; lock/unlock; long/RTL panel content;
active dictation/volume-key coexistence; undownloaded or unsupported source fixtures; a same-title
different-chat switch; Delete all/off during an in-flight request; and actual backup/device
transfer. The pure tests cover several associated guards, but do not establish those phone rows.
This checkpoint is not full acceptance, a release, or authority to merge/version/deploy.

### Telegram handset checkpoint: 2026-11-06

The owner supplied two distinct new native voice-note fixtures in the prepared bot conversation.
The isolated v15 APK (SHA-256
`05f2dd6671084022f7acb36480b9a86dd8bf0264a3847b15b69972000002fa74`) contains the source
subsequently committed as `374bceea1cfeee7c3da97b2c1dc7651dfc00c0e1`. Documentation/changelog
completion did not change that runtime source. All four PR checks passed for that commit.

Phone testing exposed two bugs: the native toolbar says "1 Selected", and selected-mode text
could replace the remembered conversation title. Separate regressions failed before the parser
and header guards were corrected, then passed. The exact-origin comparison remains strict.

| Check | Observed result |
| --- | --- |
| Real configured speech server, both new notes | Two distinct private captions; owner confirmed the second matches its note. Composer remained empty, no message sent, remembering off. |
| Remembered first note, then repeat | One STT/cleanup request each, then no additional inference; saved timestamp unchanged. |
| Re-transcribe, then distinct note | Fresh reacquisition bypassed cache, then the other note missed: request totals advanced to two and three respectively; two stored records. |
| Remember off | Both temporary records deleted; state disabled. |
| Delayed STT + Close, app switch, source scroll or screen lock | Each discarded the late result, made no cleanup request, left no spool or transcript record, and did not revive a panel. After wake/unlock, no active caption remained. |
| Multiple selection | Local refusal, zero inference, dictation idle. |
| Cleanup unavailable | Raw transcript and explicit fallback notice were readable. The cleanup-delay setup did not reach a running cleanup request, so Telegram Close-during-cleanup is not claimed. |

Some earlier test-helper attempts did not complete the intended handoff or lost fixture eligibility;
they are not passes. The helpers now stop when fixture or expected panel-state verification fails.
An unrelated Telegram source-content update also conservatively dismissed a shown panel, as the
current source-mutation guard requires; no weaker title-only identity check was substituted.

After testing, the approved real-server configuration was restored through RAM, remembering was
off with zero records, microphone permission remained denied, and the loopback reverse was removed.
The main app remained 0.2.1. Transient captures are deleted after inspection and never published.

Remaining at that checkpoint (follow-up results below): long/RTL text; active dictation/volume-key coexistence; unsupported and
undownloaded source fixtures; same-title different-chat switch; off/Delete all during an in-flight
request; actual backup/device transfer; and Telegram Close during already-running cleanup.
WhatsApp lock/unlock was not repeated separately. These are not implied by the successful checks.

### Caption regression follow-up: 2026-11-06

The owner requested the remaining dictation, chat and cache safety checks after PR #40 merged.
The new control inspector was tested in isolated candidate v16, APK SHA-256
`40edb03cae9a136ca3ee38ca13213b8ccc37d3b07675f36038eef5b4e29940a2`.
It adds no production feature behavior, permissions or components. The release implementation
does nothing. Runtime results span v15/v16 as recorded, rather than implying every earlier case
was repeated on v16.

`adb shell dumpsys activity service
io.github.iteramen.voxdroid.captiontest/io.github.iteramen.voxdroid.VoxdroidA11yService controls`
reports only names/bounds of known own-app controls. Wait for settled, on-screen bounds before
clicking. Do not use `uiautomator` during a live caption: it can suppress/reconnect the service
and mask a lifecycle defect. Fixture setup inspection is done before the request; control dumps
do not reconnect the service. Refresh the current verified Telegram task rather than reusing an
old task ID. Verify voice rows are wholly below the toolbar and above the composer; Telegram
can expose off-screen virtual rows with misleading bounds.

The fixture server now has fixed `short`, `long` and `rtl` synthetic text cases, plus separate
request-start/completion counters. It accepts no arbitrary transcript as a text-case control.
Completion counters distinguish a real in-flight race from an operation done after the reply.
Six synthetic Python helper tests pass locally and now run in the CI gates job. Counter resets
also invalidate older delayed completions, so one attempt cannot contaminate the next. Normal and
isolated lint/454 JVM tests/debug assembly, plus normal unsigned release assembly, passed.

| Check | Evidence |
| --- | --- |
| Accessibility-button dictation with captions enabled | A short actual microphone take produced valid 16 kHz mono PCM16 WAV and landed the fixture server's synthetic result in Voxdroid's own Try field. No chat field was used. This verifies recording/delivery, not recognition quality. |
| Physical Volume Down | Owner manually confirmed hold/start and tap/stop work as intended. Injected-key attempts and a timed-out watch window are not substitutes for that manual confirmation. |
| Actual chat switch and return during STT | Switched between the two approved bot conversations through verified native links held only in RAM. Late reply made no panel, cleanup request, transcript record or leftover audio; returning to the first chat did not resurrect it. |
| Delete all during STT | Remembering was on, Delete all changed its epoch before the server completed STT, and remained on for the late reply. No late record, cleanup request or spool remained. |
| Remember off then on during STT | Re-enabled with a new epoch before the reply. The old request did not repopulate the cache. Remembering was switched off afterwards. |
| Long and RTL panels | Synthetic 80-paragraph Latin and 40-paragraph Hebrew results wrapped and scrolled inside the panel. The panel stayed shown, and Copy/Close/Re-transcribe remained accessible at the owner's large font size. Transient captures were inspected and deleted. |
| Close during running cleanup | Server counters confirmed cleanup had started and not completed at Close. The finished reply produced no panel, transcript record or spool. |
| Selected non-voice message | Count one/voice false refused locally, with no STT/cleanup or microphone start even though microphone permission was granted. |
| Unsolicited invalid shares | Missing stream, file URI, missing read grant and SEND_MULTIPLE intents all refused; zero inference and dictation remained idle. |
| Built release backup policy | The actual release manifest's `dataExtractionRules` reference resolved to packaged XML excluding `secrets.xml`, `captions` and `caption_spool` from both cloud backup and device transfer. Optimized resource paths were resolved with `aapt2 dump resources`, then inspected with `dump xmltree`; the source filename is not assumed to survive optimization. |

Opening settings for Delete all/off-on also cancels the source-bound caption, as designed. The
phone checks prove that real user flow and epoch changes before an outstanding server reply;
the independent JVM cache-ticket tests prove that an old ticket itself cannot write after an
off/delete generation change. No stronger isolation claim is made for the phone test.

An invalid-share cleanup step returned to a different foreground task; its post-check was not
counted as a pass until the prepared chat was restored and its empty composer verified. Other
failed setup/coordinate attempts likewise remain excluded from the passing evidence.

After the session: real-server settings/tokens were restored using the already approved RAM-only
projection, remembering was off with zero records, the prepared composer was empty, the fixture
server stopped and its reverse removed. Temporary microphone grants were revoked by the test
helpers; the owner later granted microphone permission manually and confirmed Volume Down.
That final user-set permission was preserved. The main 0.2.1 app and its data were unchanged.

These checks close the previously named dictation, actual chat-switch, cache-race, long/RTL and
running-cleanup regression gaps. Additional fixture/platform qualification remains unrun: two
different native chats with identical display names; an undownloaded voice note; stereo/near-limit
and unsupported media on the platform decoder; actual transfer to a second handset; and a separate
WhatsApp lock rerun. Equal-header mutation, format/size/duration and backup-policy guards have JVM
coverage; actual source scrolling with an unchanged chat header and the shared lock handler have
handset evidence. Those are stated separately from the unrun exact fixture cases.

### Telegram undownloaded-note feedback: issue #44 (2026-11-08)

On the installed 0.3.0 release, scrolled the owner's explicitly prepared Telegram test conversation
to older voice notes. Selected one wholly visible voice row and inspected only voice/control
metadata. The selected toolbar exposed an enabled, clickable Share action. Tapping that native
Share control left Telegram foreground with no Android share chooser. No Play, Download,
recipient or transcription target was selected. The source's accessibility labels did not expose
an explicit download-state marker, so the fix treats this as failed sharing and advises checking
download availability instead of claiming it measured file state.

The stalled-share session regression failed first (12 focused tests, one failure), then passed
after the deadline/invalidation implementation. Added cases cover time actually spent in an open
chooser, an already-started receiver, cancellation/supersession, the deadline starting at Share
rather than selection, and stale chooser events. This is source/logic validation; the new notice
has not yet been checked on a signed handset build. The installed app remains 0.3.0. A signed
candidate still needs the checklist row above, including the manually downloaded retry.
## Read-aloud qualification (2026-11-08)

English first; Hebrew/Russian routing/settings are scaffolded, with missing-voice refusals covered
by unit tests. No HE/RU backend or audible qualification is claimed. `ReadAloudTest` covers script
routing, no implicit voice fallback, Unicode/size limits, credential projection, portable backup
with enable consent excluded, real loopback HTTP payload/status behavior, cancellation and bounded
PCM16 decoding, including Kokoro streaming WAV length sentinels.

Pixel 10 / Android 17, isolated `.readaloudtest` debug APK, synthetic English paragraph in a local
static page and the owner's actual TTS endpoint: selection → real generated PCM → `AudioTrack`
passed; the second speaker tap stopped with no late restart; another read completed; switching
to Home stopped active playback. A separate live-read lock test also passed: screen-off stopped
playback and removed the glyph with no late restart. Microphone permission remained ungranted throughout. No production
configuration or credentials were copied. The temporary package was removed and original
accessibility/USB wake settings restored. The signed production app and backup stayed unchanged.

Failed fixture setup attempts are excluded: a locked device correctly hid controls; initial debug
control coordinates were window-relative and were corrected to screen coordinates. The passing
run used screen coordinates. No generic conversation or document was inspected.

The consent bit was subsequently moved to the backup-excluded private preferences file; portable
export tests pin its exclusion from both preference namespaces. Audio/controller behavior was unchanged.

Remaining manual coverage: actual audio-focus interruption, password and
editable-block refusal in external controls, long/mixed-script real speech, new glyph contextual
voice-caption/dictation regression, notification Stop, and additional Android/device versions.
These are checklist items, not implied by unit or build success.

## Dictation transport cancellation regression (2026-11-10)

Issue #50. JVM TransportCancellationTest first timed out waiting for the obsolete STT
request to release the serial worker. After the fix, STT and cleanup stalled-header cases
must release it within 3 seconds despite a 30-second configured timeout; the next request
must complete and the old one must not retry. Pre-cancelled transport must never connect.

Android uses a different HTTP implementation. tools/TransportCancellationProbe.java loads
boundedPost from the built APK via app_process, sends synthetic bytes only to phone loopback,
and checks both stalled headers and stalled bodies. It needs no installation, microphone,
settings, credentials or ordinary app data. Compile the probe with javac --release 8, dex it
with SDK d8 --lib android.jar, and push the probe jar plus the debug APK to named temporary
paths under /data/local/tmp. Set CLASSPATH to jar:apk and run app_process /system/bin
TransportCancellationProbe. Remove those two files afterwards. Both cases must report PASS;
the installed production app is unaffected. Initial Pixel run: headers 52 ms, body 53 ms
for cancellation plus the next request. Recheck the final committed artifact before handoff.

This proves transport recovery, not the full handset lock/record/insert interaction. That
end-to-end behavior remains a separate owner smoke test after a feature build is installed.

## Narration source and screen lifecycle regression (#55)

`ReadAloudLifetimeTest` reproduces unconditional content-event cancellation in cf28502 and
checks unchanged selection continuity, changed/missing selection, package/window loss, scroll,
window-state changes and conservative picker invalidation. These are policy tests; Android node
refresh, event delivery and window power behavior require the isolated handset checks below.

Use only a synthetic page and the `.readaloudtest` package, with microphone ungranted and no
production credentials/config copied. Restore accessibility, screen timeout and USB-wake settings
and remove the test package afterwards. Record the exact committed APK used for each result.

| Check | Required result | Status |
| --- | --- | --- |
| Update unrelated page status while speaking | `contentKept` increases; playback continues and completes | Passed a9eac33, real TTS |
| Replace/remove the chosen paragraph while speaking | Active/playback stop; no late restart or replacement text request | Passed a9eac33 |
| Update content while choosing | Picker closes; stale coordinates cannot start speech | Passed a9eac33 |
| Scroll, same-app navigation, app switch | Speech stops; no late restart | Passed a9eac33 |
| Speaker Stop, new dictation | Stop narration and release screen flag | Speaker passed a9eac33; new-dictation in final regression. The notification's Stop action was removed on 2026-11-19: Android never showed the notification |
| Automatic timeout with USB wake disabled | During synthesis/playback, display stays awake beyond its configured timeout | Passed a9eac33, 18 s observation against 15 s timeout, USB wake off |
| Completion, cancellation and failed TTS | `keepScreenOn=false`; idle display can time out | Flag release passed on completion/cancel/503; idle timeout in final regression |
| Manual power-button lock during speech | Speech stops, glyph disappears and no late restart | Injected screen-off passed a9eac33; physical button not separately tested |
| Service off while a dictation records | After a narration has played and stopped, start a dictation and switch the service off in Accessibility settings while it records: the microphone indicator goes out at once, and `adb shell dumpsys activity services <package>` lists no `VoxdroidA11yService` record. Switching the service back on gives one pill and one floating control; a second narration and a dictation then both start. | Passed 2026-11-18 on `effb625`, with the service switched off over adb, not through the Settings screen. The row used to begin with the notification's Stop, which could not be reached (#107, closed) and is now removed |
| A block of several chunks (over 1,500 characters of ordinary prose) | The first speech starts as before; later chunks follow with no silence longer than a sentence pause, and a chunk starts at a sentence start wherever the text has sentence ends. Stop during the second chunk: silence at once and no late speech. | Chunks passed on the 0.6.1 release, Pixel 10 / Android 17: a three-paragraph message went as four requests, each made while the previous chunk played. Stop not run |
| A notification sound during speech | Narration plays on through it. `adb logcat -d` shows `onAudioFocusChange(-3)` dispatched to `ReadAloudController` and no `abandonAudioFocus()` from voxdroid after it. An incoming call or another app's playback still stops it. | Not run |

Debug diagnostics expose counts/flags only, never selected text or other app identity. A green
JVM/build gate does not establish these handset outcomes.

## Try-screen tap control (#58)

`ReadControlContextTest` first fails on regression 15cbb5a, then checks microphone-only own Try
context, hidden own settings and locked/password contexts, and normal external context.
Native checklist: own Try field shows 🎤 but no 🔊; own settings/credential views show neither;
returning to the external synthetic page restores configured controls; disabling the floating
microphone hides it on Try. A tap uses the existing permission/field-checked dictation path.

## TTS request policy (#60)

`TtsPolicyTest` first fails in a301cbd: invalid endpoint structure is accepted, direct transport
skips validation, registration-time cancellation reaches loopback, and a cancelled header wait
retains the worker. Green cases require fixed refusals before registration/upload and prompt
release of a real stalled-header request. Existing HTTP policy precedence is retained.

`tools/TtsCancellationProbe.java` loads the committed APK through Android app_process and uses
synthetic phone-loopback requests only. Both stalled-header and partial-body cases must cancel
and allow the next serial request to return valid PCM within three seconds, against a 30-second
timeout. The body case waits 150 ms after the partial reply before cancellation. Compile/dex and
run like TransportCancellationProbe, using named temporary jar/APK files under /data/local/tmp;
remove them afterwards. This needs no app installation, permission, credentials or external TTS.


## Failure diagnostic privacy (#62)

Automated loopback regressions cover HTTP errors and malformed successful responses containing
synthetic transcript and unrelated credential markers. Neither STT error details nor cleanup
fallback details may include those markers, response prefixes, or raw exception messages.
Cleanup must still return the original transcript separately. Tests also cover token fragments,
missing configured tokens, oversized bodies, malformed URLs, and unknown failures through the
transcription, lane and cleanup adapters. Review the two service debug-log call sites against
these tested outcomes; never collect production transcripts or credentials as test evidence.


## Telegram selected voice waveform compatibility (#65)

Telegram 13.2.1 on the acceptance handset exposes the selected voice waveform as an
`android.widget.SeekBar` with `selected=true` and the `Voice message` metadata marker. The old
ViewGroup/LinearLayout-only classifier refused it. Regression e5f1eed reproduces that refusal;
the repair adds only SeekBar while retaining the one-selection toolbar check, voice marker,
unique voice node, trusted Share action and original conversation lifetime checks.

Automated checks cover the new role, existing roles, generic seek bars, and lazy metadata access:
unselected nodes and unknown roles must not have their message metadata read. Native handoff
and private-result acceptance passed on 8e87a31; #65 is closed. Use content-free service dumps
or screenshots during an active operation: UIAutomator dump recreates the accessibility service
on this handset and invalidates recording/caption/narration tests.


### Live accessibility tests must not use UIAutomator inspection

The final Try-screen check exposed a harness problem: `adb exec-out uiautomator dump /dev/tty`
recreated the isolated accessibility service on this handset, including while the app process
stayed alive. During recording, that teardown cancels the take. A before/after service-record
identity check reproduced this at idle. Such an interrupted run is not evidence of a product
recording failure or a passing regression.

Use the isolated package's content-free service diagnostics while recording, processing captions
or speaking. Use the handset's actual Stop control for manual dictation checks. UIAutomator may
inspect isolated settings or a delivered test field only while no operation is active; allow the
service to reconnect before starting the next operation. Never inspect ordinary chat content.


### Final read-aloud release candidate: handset checkpoint

Code 18a3fb1 has the same tree as merged feature 6887a31. The isolated app used synthetic text
and local fixed STT/cleanup responses; no production settings or credentials were copied.

| Check | Observed result |
| --- | --- |
| English narration at speed 1.0 through beast TTS | Started and completed normally; screen-awake flag cleared. Microphone permission remained ungranted. |
| Consent and control contexts | Cancel stayed off; Enable wrote backup-excluded consent. Own settings hid controls; external text showed the shared controls. |
| Try microphone with narration and captions enabled | Owner tapped mic, spoke briefly and tapped the Recording pill Stop. Valid WAV reached the local fixture; one STT and one cleanup request; expected synthetic text landed and owner confirmed. |
| Temporary microphone permission | Verified granted before Try and revoked after delivery. |
| Telegram contextual caption after #65 repair, on 8e87a31 | Selected SeekBar voice note -> native Share -> isolated receiver -> private panel. Valid WAV, one STT and one cleanup request; expected synthetic text displayed; composer empty, mic ungranted, spool empty. |

This fixed-response test proves capture and delivery, not real-server STT accuracy. Earlier
source/Stop/lock/automatic-timeout, caption cache/chat-switch and Android transport evidence above
remains applicable. Additional devices and Hebrew/Russian voices remain follow-up coverage.

All temporary test packages were removed and original accessibility/USB-wake settings were
restored and verified after acceptance. Production configuration was not copied or changed.


### Translucent floating controls (#67 / #68)

Native UI code 79aa160, following owner approval of design 84b369c. The release preparation
changes only version metadata/documentation after this code. The isolated app used synthetic
browser text; production configuration/tokens were not copied.

| Check | Evidence |
| --- | --- |
| Paired layout | Screenshot inspection on light and dark synthetic pages: rounded translucent capsule, clear vector icons, faint divider, no handle. Each action measured 126 px at this handset's density (48 dp). |
| Drag from either action | Both buttons moved the capsule without opening the narration picker or starting playback. Moving beyond the top/left edge stayed on-screen. |
| Saved position | Leaving for own settings hid the capsule; returning restored the saved coordinates. Wait for final layout before comparing screen bounds. |
| Speaker tap and cancel | Opened and cancelled the text picker; selected synthetic English text played through the real TTS endpoint at speed 1.0; tapping the speaker stopped playback and released screen-awake. |
| Microphone-only Try | Opened through Settings, focused the isolated Try field, verified a circular microphone with no speaker, and dragged it. No recording was requested. |
| Accessibility | ImageButtons keep the existing action descriptions and 48 dp targets, checked in source/resources. Live TalkBack navigation was not tested. |
| Action routing | The existing microphone and narration callbacks are unchanged. Prior 0.4.0 capture/delivery and Telegram caption acceptance remain applicable; these were not represented as fresh end-to-end tests. |
| Cleanup | Test app removed; original accessibility and USB-wake values restored. Production package data and grants untouched. |

Local lint/build, 505 JVM tests (504 passed, one existing live-server skip) and six handset-helper
tests passed. All four PR checks passed on 79aa160 before the version/documentation follow-up.
Final landing must use green checks on that follow-up too.

Harness limits: a locked-phone attempt was discarded. UIAutomator recreates the service and
omits transient overlay nodes, so screenshots and content-free diagnostics validated active
controls. A direct shell launch correctly refused the unexported Try activity; the passing layout
check opened it through Settings. The Try readiness prerequisite used a temporary microphone
runtime grant without requesting recording; the grant ended with test-app removal. A final
appops text-format assertion did not pass and is not claimed as audio-policy evidence.


### Corner-preserving controls and Settings rows (#69 / #70)

| Regression/check | Result |
| --- | --- |
| Raw coordinate rotation regression | Red 7a69da3: five failures among six placement cases. The repair keeps all four corners/margins, dp scaling, one/two-button width changes and clamped round trips. |
| Encrypted backup regression | Red 4622b70 dropped the new placement fields. 061f532 preserves all four typed fields, rejects malformed/incomplete anchors and accepts legacy x/y backups for migration. |
| Portrait -> both landscape orientations -> portrait | Native e4a4829: top-right and bottom-left retained their physical corner; coordinates stayed inside the screen and clear of the cutout/system bars. Saved anchor values did not change on rotation. |
| Migration and restart | Legacy x/y migrated once; the same anchor survived service disconnect/reconnect and reproduced the same position. |
| Narration while rotating | Native 061f532: synthetic English TTS started; rotation cancelled playback and cleared the screen-awake flag. |
| Settings rows | Screenshot inspection confirmed matching icon backgrounds, aligned titles and chevrons for Backup and restore / Read aloud; both rows opened their intended screens. |
| TTS configuration | Live owner-server inventory and synthetic request verified the supported English endpoint/model/voice. Production Settings already held those values and speed 1.0; they were verified and re-saved without changing tokens or dictation configuration. No Hebrew/Russian TTS backend was found, so STT lanes were not substituted. |

Local 061f532 checks: lint/debug build, 513 JVM tests (512 pass, one existing live-server skip),
six helper tests. Placement/controller/menu code is unchanged from the e4a4829 native rotation
checks; the follow-up adds backup support and version metadata. Test apps were removed and
original rotation, accessibility and USB-wake settings restored and checked. Narration tests
never granted the microphone or copied production credentials.

An empty-setting ADB argument interrupted the first helper after its completed rotation checks;
restart was checked separately. An earlier narration attempt cancelled before playback and is
not used as passing evidence. The final pass used one dedicated Chrome tab, navigating that
same tab instead of repeatedly launching VIEW intents. Keep reusing that tab for future tests;
do not create one tab per test run. UIAutomator remains unsuitable during active media operations.


### Settings row subtitles (0.4.3)

- Scroll the main Settings screen to Backup and restore, Read aloud and Privacy policy.
  Each row must use the same title/secondary-text hierarchy as Speech and Languages, with
  its existing icon aligned around the two-line text block (internal pages also have a chevron). Check at the owner's
  large font size that subtitles wrap naturally without overlapping the arrow.
- Backup explains encrypted settings save/restore; Read aloud names voices, speed and speech
  server settings; Privacy policy explicitly says it opens a web page.
- Navigation listeners and destination activities/URL are unchanged. This resource-only change
  does not require new narration, microphone, rotation or caption tests.

### Settings setup order and navigation

- [ ] Main rows follow Speech → Cleanup → Languages → Triggers → Delivery → Voice messages →
  Read aloud → Backup and restore → Privacy policy, including accessibility traversal order.
- [ ] Backup and restore uses the same toolbar/title/back arrow as the other sub-pages;
  the arrow is labelled "Back to settings" for accessibility and returns to Settings.
  Android Back also returns; scrolling backup content leaves the toolbar visible.
- [ ] Privacy policy retains its icon and explanatory subtitle, has no right chevron,
  and still opens the policy URL in the browser. Internal sub-pages retain their chevrons.
- [ ] Large fonts and landscape do not clip the backup actions or overlap the toolbar.
  Backup screenshot protection remains enabled; no export/restore is needed for these checks.

### Floating control context check

The control decides where it may show from every accessibility event (`ReadAloudController.observe`).

| Check | Expected |
| --- | --- |
| *Floating 🎤 control* and read aloud both off | No floating control over any app. Turning *Floating 🎤 control* back on shows it on returning to another app. |
| *Floating 🎤 control* on: a field marked as a password field focused, the lock screen, voxdroid's own pages | The control is hidden; it returns on leaving. On the Try page with its field focused, only 🎤 shows. |
| *Floating 🎤 control* and read aloud on, in another app | 🎤 and 🔊 show together and both act. |

Recorded 2026-11-18 on `effb625`, isolated `readaloudtest` build beside production: the three rows
gave their expected results over the phone's browser, the launcher and the test app's own pages.
On the lock screen the control was hidden; its return after unlocking was not checked. Details in
"The 0.6.0 rows that were never run (#89)" at the top.
