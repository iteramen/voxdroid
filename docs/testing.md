# Manual checklist

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
- [ ] turn airplane mode on and dictate: the pill reads `stt unreachable` or `stt timed out`, never
      `Tailscale not connected?` for an endpoint outside the tailnet; turn the radio back on and
      tap `↻ Retry` — the same dictation lands, from the audio still held in memory
- [ ] point `STT endpoint` at a port nothing listens on and dictate: the pill reads `stt unreachable`
- [ ] point `STT endpoint` at a tailnet host (a `100.x` address or a `*.ts.net` name) and disconnect
      Tailscale, then dictate: the pill reads `stt timed out — Tailscale not connected?` or
      `stt unreachable — Tailscale not connected?`, with no token, URL or server text on it, and it
      stays with `↻ Retry` well past 4 seconds; reconnect Tailscale and tap `↻ Retry`: the same
      dictation lands
- [ ] the same tailnet endpoint, connected, answering 401 or 500: the pill reads `stt HTTP 401` or
      `stt HTTP 500` and never asks about Tailscale
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
      volume-down (or tap the accessibility button): the pill and its Retry stay as they were; tap
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

- [ ] hold volume-down in a chat: one click as the pill appears; tap the accessibility button
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
      accessibility button instead: the same
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
- [ ] One received note selected → accessibility button → native Share chooser → Caption with
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
| Speaker Stop, notification Stop, new dictation | Stop narration and release screen flag | Speaker passed a9eac33; notification/new-dictation in final regression |
| Automatic timeout with USB wake disabled | During synthesis/playback, display stays awake beyond its configured timeout | Passed a9eac33, 18 s observation against 15 s timeout, USB wake off |
| Completion, cancellation and failed TTS | `keepScreenOn=false`; idle display can time out | Flag release passed on completion/cancel/503; idle timeout in final regression |
| Manual power-button lock during speech | Speech stops, glyph disappears and no late restart | Injected screen-off passed a9eac33; physical button not separately tested |

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
