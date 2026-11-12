# Changelog

Notable changes per release. Each release is a tag; a signed APK, its `SHA256SUMS` and a
build-provenance attestation are published from it on GitHub Releases.

## 0.4.1 — 2026-11-12

Changed
- Restore a compact translucent floating pill with clear vector microphone and speaker icons.
  Drag either icon to move it; no separate handle is needed. A drag does not activate the button.
- Use a circular control when only one action is available. Keep 48 dp touch targets and
  accessible button labels, and update the Settings/README instructions.

## 0.4.0 — 2026-11-12

Added
- Optional read aloud: tap the floating speaker, choose a visible read-only text block, and hear
  it through your own TTS server. Tap again to stop. English is the first release target;
  Hebrew/Russian configuration is scaffolded and refuses if its voice is not configured.
- Separate TTS endpoint, model, voice and token settings, with explicit enablement consent.
  TTS configuration and tokens join encrypted settings export; consent stays local and excluded
  from backups. Narration does not require microphone permission.

Fixed
- Telegram captions recognize selected voice waveforms exposed as SeekBar nodes in Telegram
  13.2.1, retaining the single-note, voice-marker and conversation safety checks.
- Cleanup responses explicitly marked incomplete fall back to the original transcript instead
  of inserting truncated text.
- Cancelled dictation and narration network requests release the worker promptly for the next
  request. Already transmitted requests cannot be recalled from a server.
- Unrelated page updates no longer interrupt an unchanged chosen narration block. Changes to
  that block, leaving its source, Stop and screen-off still cancel playback.
- Narration prevents automatic screen timeout during synthesis and playback, releasing the
  flag when it finishes or fails. Manual screen-off still cancels.
- The app's Try screen exposes its microphone control, while settings and password fields
  remain excluded and narration is unavailable in the app's own windows.

Security
- STT and cleanup failure diagnostics omit response bodies and raw exception messages at every
  log level. Fixed failure categories and HTTP status remain available. Privacy policy updated
  2026-11-12 to describe this behavior and explicit read aloud.
- TTS endpoints reject user information, query/fragment components and malformed host forms
  before connection. Transport cancellation stays off the accessibility thread.
- Security, privacy, Android accessibility wording and Play declaration drafts cover the
  selected-text-to-TTS flow and media-playback foreground service. No Play approval is claimed.

## 0.3.0 — 2026-11-07

Added
- Password-protected settings export/restore, including API tokens and language lanes, for a
  reinstall or new phone. Audio, transcript history, caption consent and Android permissions are
  excluded. The backup is verified before reporting success; restore verifies the applied settings.
- Private voice-message captions for selected Telegram and WhatsApp Ogg/Opus notes: native Share,
  a floating reading panel, explicit Copy/Close, and Re-transcribe through a fresh share. Separate
  switches enable captions and remember transcripts for 30 days; both are off by default.
- An isolated `-PcaptionTest=true` debug package and a local fixture server for handset validation.

Changed
- Caption regression coverage now includes real chat switches, cache deletion/off-on during
  processing, long/RTL panel scrolling, normal dictation and invalid share refusals.
- Telegram recognizes the native "1 Selected" toolbar format and keeps selected-mode labels out
  of conversation identity, preserving the original chat context through native Share.
- WhatsApp selection uses reported view IDs and the selected-message toolbar's own Share control;
  routine header checks avoid walking the message list. Re-transcribe has its own full-width row
  for readability at large font sizes.
- Privacy, security and accessibility documentation describes the caption integration, its
  temporary audio file, foreground selection metadata and hearing-accessibility purpose. Handset
  acceptance and remaining platform/fixture qualification are tracked in `docs/testing.md`.

## 0.2.1 — 2026-11-05

Added
- Groundwork for private voice-message captions, for people who are deaf or hard of hearing. The
  processing code is in the app and unused: no voice message is received or transcribed yet.

Changed
- A dictation cancelled while its upload got no response no longer sends that upload's one retry.
- The privacy policy (effective 2026-11-05) says so, and describes the planned voice-message
  caption flow.

## 0.2.0 — 2026-11-05

Added
- Tones on start, done and error, matching byovox: on by default at 5 % volume, with their own
  switch and volume on the Triggers page, quiet on silent, vibrate and Do Not Disturb. The start
  tone finishes before the microphone opens.

Changed
- A failure or refusal notice stays up for 4 seconds; `silence` and `copied to the clipboard` stay
  2.5 seconds.
- A connection failure to the STT endpoint, a lane or the cleanup endpoint reads `timed out` or
  `unreachable` instead of `transport`. For a host in 100.64.0.0/10 or under `.ts.net`, the pill
  also asks `Tailscale not connected?`.

Fixed
- The screen no longer times out mid-take and throws the dictation away: it stays on while
  recording and while the transcript and cleanup are pending. Locking the screen still cancels.

## 0.1.1 — 2026-10-13

Fixed
- ChatGPT, Grok and other Jetpack Compose apps no longer refuse with `no text field` while their
  text box is focused and the keyboard is up.

## 0.1.0 — 2026-10-10

Added
- A signed APK on GitHub Releases, and [docs/sideload.md](docs/sideload.md) on installing it.
- Dictation through an accessibility service, with Gboard staying up: hold volume-down or tap the
  accessibility button, and the text lands at the cursor of the focused field.
- Speech-to-text against your own OpenAI-compatible server, with an optional cleanup pass that
  falls back to the raw transcript, a glossary, and a no-speech gate that drops a silent take. A
  take is capped at five minutes.
- Per-language routing: a take follows Gboard's language, with an optional server lane per
  language and auto-detection over the languages you list.
- The pill: elapsed time, Stop, ✕ while recording, Retry, and the failing stage on an error.
- The settings app: a status card that names what is missing and fixes the first problem, and
  pages for Speech, Triggers, Delivery, Cleanup and Languages.
- When the service is off, the status card's button opens Android's Accessibility settings.
- A fresh install's language list is the phone's language plus English.
- Haptic cues at start, done and error; no take during a phone call unless allowed.
- An "Allow insecure (http) endpoints" switch, off by default: plain http is refused except to
  this phone.
- A privacy policy, linked from the main screen once it is published, and a security policy.
- The main screen's title row shows the version and build, for example `0.1.0 (1a2b3c4)`.
- A design document: docs/design.md.

Changed
- A take over 60 seconds asks the server for voice-activity detection, and is resent without it
  if the server refuses.

Fixed
- A field that reports itself as a password field never receives text.
- Control and invisible Unicode characters in a reply are removed before typing; Hebrew and emoji
  joiners are kept.
- With "Capitalise the first word" off, a leading "I" keeps its capital.
- A bearer token is redacted from error and log lines, replies are capped at 10 MiB, and
  redirects are not followed.
- A cancelled take starts no request it had not started yet.
- The pill's notices wrap instead of being cut, and the pill stays centred.
- The ready card stays readable on every wallpaper, and long switch titles wrap.
