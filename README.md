# voxdroid

voxdroid is an Android dictation app, made first for people whose hands hurt or do not obey (RSI,
motor impairment) and for anyone who would rather talk than type. It runs as an accessibility
service, so your own keyboard stays up: hold volume-down or tap the accessibility button, speak,
stop, and the text lands at the cursor. Your speech goes to a speech-to-text server you run, never
to one voxdroid runs. Its desktop sibling is [byovox](https://github.com/iteramen/byovox).

## What you need

- Android 13 or later.
- An OpenAI-compatible speech-to-text server the phone can reach, one that answers
  `POST <base URL>/audio/transcriptions`. whisper.cpp's `whisper-server` is one, started with
  `--inference-path /v1/audio/transcriptions` ([byovox's README](https://github.com/iteramen/byovox#readme)
  walks through it); its base URL is then `https://<host>:<port>/v1`.
- Optional: an OpenAI-compatible chat model (`POST <base URL>/chat/completions`) for the cleanup
  pass, which adds punctuation and removes fillers.
- `https://`, or plain `http://` only over a network that encrypts by itself, such as Tailscale or
  WireGuard (see [Privacy in brief](#privacy-in-brief)).

What voxdroid sends to the speech server: the recording as `file` (16 kHz mono WAV), `model`,
`response_format` (`verbose_json` while the no-speech gate is on, else `json`), `language` or
`language_candidates`, the glossary as `prompt` if you set one, and `vad=true` on a take longer than
60 seconds (sent once more without it if the server answers that with an HTTP error).
`language_candidates` is an optional whisper.cpp server extension, not in upstream whisper.cpp;
servers without it ignore the field. The reply needs `text`; `segments[].no_speech_prob` feeds the
no-speech gate and `language_probabilities` the auto-detect routing below.

## Install

- **Google Play:** coming soon.
- **GitHub:** a signed APK on [Releases](https://github.com/iteramen/voxdroid/releases).
  [docs/sideload.md](docs/sideload.md) walks through Android's hurdles for an app from outside
  Play: allowing the install, Play Protect, and the restricted setting that blocks a sideloaded
  accessibility service.
- **From source:** build as [CONTRIBUTING.md](CONTRIBUTING.md) describes, then
  `adb install -r app/build/outputs/apk/debug/app-debug.apk`.

## Set up

Open voxdroid. The status card at the top lists what is still missing, and its button fixes the
first item:

1. **Turn on the service.** The button opens Android's Accessibility settings; turn voxdroid on
   there. To use the accessibility button, turn on voxdroid's shortcut there as well.
2. **Grant the microphone.**
3. **Speech:** the STT endpoint (the base URL; voxdroid appends `/audio/transcriptions` itself),
   a bearer token if your server needs one, the model name (sent as `model`; whisper.cpp ignores
   it), and an optional glossary of terms to spell your way.
4. **Languages:** *Your languages* starts as the phone's language plus English. Each language has
   its own page:
   - **Use Gboard's language:** on, a take is sent as that language while your keyboard is on it;
     off, voxdroid auto-detects among your languages. On by default for Hebrew only.
   - **Lane URL:** an optional server of its own for that language, such as a fine-tuned model.
     With the no-speech gate on, an auto-detected take heard as that language at 0.5 or more is
     sent on to its lane.
   - **Send the STT token** and **Token for the lane:** a lane on another server than the STT
     endpoint gets no token unless you give it its own or turn the switch on.
5. **Cleanup** (optional): an endpoint and a model. The pass does nothing until both are set, and
   on any failure the raw transcript is typed instead.

When everything is in place the card turns green, reads **Ready to dictate**, and offers **Try a
dictation**. While the screen is open, it checks every five seconds that the speech server answers.

## Dictate

- **Start:** hold volume-down (500 ms by default, at least 300; Triggers page) or tap the
  accessibility button. Recording starts at once and the pill appears above the keyboard with a
  red dot and a timer. A quick volume-down press while idle still lowers the volume.
- **Stop:** press volume-down, tap the accessibility button again, or tap **■ Stop** on the pill.
  There is no silence timeout: pause to think as long as you like. A take stops itself at five
  minutes and is transcribed.
- **Discard:** tap **✕** while recording. Turning the screen off abandons the take at any stage.
- **The text** lands at the cursor of the field that was focused when you started, with a space in
  front when needed. Line breaks become spaces, and one trailing period comes off (Cleanup page).
- **Errors** name the failing stage on the pill (`stt transport`, `stt[he] HTTP 502`); after a failed
  transcription, **↻ Retry** sends the same audio again without re-speaking.
- **Refused:** a password field; a phone call in progress, unless *Dictate during a call* is on; no
  text field, or a field that changed before the text arrived, unless the Delivery page is set to
  *Copy to the clipboard*.
- Drag the pill up or down; it keeps that height above the keyboard. With *Vibrate on start and
  done* on, a click marks a start and a landing, and a double click a failure or a refusal.

## Privacy in brief

voxdroid has no server, account, analytics, advertising or crash reporting. The audio goes only to
the servers you configure, is held in memory and is never written to disk. Your bearer tokens stay
in app-private storage, out of Android's backups. Plain `http://` to anything but the phone itself
is refused until you turn on *Allow insecure (http) endpoints*. The full statement is the
[privacy policy](docs/privacy.md); the threat model and how to report a vulnerability are in
[SECURITY.md](SECURITY.md).

How it works: [docs/design.md](docs/design.md).

## License

[PolyForm Noncommercial 1.0.0](LICENSE). For other licensing, write to
byovox.licensing@gmail.com. Bug reports and ideas are welcome as
[issues](https://github.com/iteramen/voxdroid/issues); code contributions are not accepted
([CONTRIBUTING.md](CONTRIBUTING.md)).
