# Changelog

Notable changes per release. Each release is a tag; a signed APK, its `SHA256SUMS` and a
build-provenance attestation are published from it on GitHub Releases.

## Unreleased

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
