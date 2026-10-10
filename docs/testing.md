# Manual checklist

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
      `android.permission.FOREGROUND_SERVICE` and `android.permission.FOREGROUND_SERVICE_MICROPHONE`
      as `uses-permission` lines, and one more pair of lines for
      `io.github.iteramen.voxdroid.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` (a `permission:` and a
      `uses-permission:`), AndroidX's own signature-level permission. The debug APK also lists
      `android.permission.WRITE_SECURE_SETTINGS` (`SelfEnable`, debug only); the release build's
      merged manifest does not
- [ ] in WSL, `~/Android/Sdk/build-tools/36.0.0/aapt2 dump xmltree --file AndroidManifest.xml
      app/build/outputs/apk/debug/app-debug.apk` lists exactly six components, with these
      `android:exported` and `android:permission` values: activities `SettingsActivity`
      (exported, the launcher), `SectionActivity` and `TryActivity` (not exported); service
      `VoxdroidA11yService` (exported, `android.permission.BIND_ACCESSIBILITY_SERVICE`); provider
      `androidx.startup.InitializationProvider` (not exported); receiver
      `androidx.profileinstaller.ProfileInstallReceiver` (exported, `android.permission.DUMP`). A
      seventh component, or an exported one without its permission, fails the row; `SECURITY.md`'s
      Components section is the same list
- [ ] optional, and not before the Settings rows for the microphone below, which need it ungranted:
      `adb shell pm grant io.github.iteramen.voxdroid android.permission.RECORD_AUDIO` — the
      status card's **Grant the microphone** is what asks for it otherwise
- [ ] the icon is a white microphone with a stem and a base on black, on each surface that draws
      it: the launcher, the app drawer, Settings, Apps, the floating accessibility button (its icon
      is the app icon, as the service declares none), and Settings, Accessibility's entry for the
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
      accessibility button, and the text lands; the instruction above the field is whole and at
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
      the button reads **Turn on**, and a tap turns the service on, brings the floating
      accessibility button back, leaves every other enabled accessibility service on, and leaves
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
- [ ] turn airplane mode on and dictate: the pill reads `stt transport`; turn the radio back on and
      tap `↻ Retry` — the same dictation lands, from the audio still held in memory
- [ ] point `STT endpoint` at a port nothing listens on and dictate: the pill reads `stt transport`
- [ ] trigger in silence and stop: nothing is typed and the pill shows `silence`
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
      **the raw transcript still lands at the cursor**, then `polish failed, raw text` — never an
      error state, never a lost dictation
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
      עברית, dictate: the pill reads `stt[he] transport` and the default endpoint was not called
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
off. The same for `accessibility_button_targets`. A debug build granted `WRITE_SECURE_SETTINGS`
does both from the status card's **Turn on**.

- [ ] in WhatsApp with text typed and Gboard up, hold volume-down: the pill appears above Gboard
      with a red dot counting from `0:00`, and Gboard stays
- [ ] speak and press volume-down once: `stt…`, then the text lands at the cursor with exactly one
      space before it, the existing text intact, and the pill gone
- [ ] a quick volume-down press while idle lowers the volume and starts nothing; holding it with
      media playing starts dictation instead of lowering the volume continuously
- [ ] tap the accessibility button, speak, tap it again: same as above
- [ ] the same in Keep and in one Compose field (Google Messages)
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
      volume-down (or tap the accessibility button): the pill and its Retry stay as they were; tap
      into the original field again and tap Retry, and the same take lands
- [ ] start a take, then lock the screen: nothing is typed after unlocking, and the pill is gone
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

- [ ] hold volume-down in a chat: one click as the pill appears; tap the accessibility button
      instead: the same click
- [ ] stop the take: one click as the text lands; mode Clipboard with no field focused: one click
      with `copied to the clipboard`
- [ ] turn airplane mode on and dictate: a double click with the `stt transport` error pill; revoke
      the microphone and trigger: a double click with `grant the microphone in voxdroid`
- [ ] trigger in a password field: a double click with `password field — not recorded`; during a
      call: a double click with `call in progress — not recorded`
- [ ] at an error pill with no text field focused (mode Refuse), trigger: a double click, and the
      pill and its Retry stay as they were
- [ ] start in one chat and switch to another before the text arrives: a double click with
      `field changed — not inserted`
- [ ] point `Cleanup endpoint` at a port nothing listens on and dictate: the raw text lands with a
      double click, not a click, and `polish failed, raw text` shows
- [ ] `✕` while recording, and the screen turning off while the pill reads `stt…`: nothing;
      trigger in silence and stop: nothing with `silence`
- [ ] turn **Vibrate on start and done** off: none of the rows above vibrates
- [ ] turn Android's **Touch feedback** setting off: none of the rows above vibrates
- [ ] unknown, record what happens: the done and error cues fire after the recording's foreground
      service has stopped. Run `adb shell dumpsys vibrator_manager` after a take lands and after an
      `stt transport` error, and note whether the latest vibrations from
      `io.github.iteramen.voxdroid` read `IGNORED_BACKGROUND`
- [ ] unknown, record what happens: the same start, done and error cues with battery saver on
