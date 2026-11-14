# Reviewer access and demonstration plan

Status: **not ready for submission**. Voxdroid has no app login, but its core
features require configured STT/TTS services. A reviewer cannot be expected to
join the developer's private VPN, obtain personal API keys or run a model server.
Google's [app access guidance](https://support.google.com/googleplay/android-developer/answer/9859455)
requires the information needed to review restricted functionality.

## Provision before filing App access

- A dedicated review service reachable over normal public HTTPS, with a valid
  certificate, predictable availability and the actual STT/TTS functionality.
- Review-only, revocable credentials if authentication is used; sufficient quota
  for repeated review without a personal login, payment, expiring OTP or VPN.
- Record endpoint base URLs, model/voice IDs, supported languages and any limits
  in the private Console instructions. Keep credentials out of this public repo,
  screenshots, video and issue comments. Do not publish the owner's live settings.
- Document review-backend retention and deletion behavior. A fake transcript
  response is useful for testing but cannot stand in for actual functionality
  in an app-review demonstration. Hosted model licences need checking for that
  deployment; the app does not distribute model weights.

No service has been exposed or funded as part of the unattended preparation.
The endpoint/access choice remains an owner decision.

## Copy-ready instruction outline (fill private fields in Console)

1. Open voxdroid. Enable its accessibility service in Android settings and grant
   microphone permission for dictation. The feature is designed for motor input
   access, readable voice messages and selected-text narration.
2. In Speech, enter the supplied review base URL, model and token. Use HTTPS.
   Configure optional cleanup only if a review cleanup service is supplied.
3. Tap **Try a dictation**, focus its empty field, start with the floating microphone
   or a long Volume Down press, speak a short sentence, then stop. Verify insertion.
4. Open Read aloud, review/accept its disclosure, enter the review TTS base URL,
   model and voice. In an app exposing read-only text, tap the speaker, then an
   outlined block. Tap the speaker again to stop. Set HE/RU lanes only if the
   supplied service supports them; otherwise explain their configuration requirement.
5. For captions, enable Voice messages through its disclosure. In an English-UI
   WhatsApp/Telegram test chat, select one downloaded voice note, tap the microphone
   and choose **Caption with Voxdroid** in native Share. No real/private conversation
   is needed. Verify the private caption panel; it never sends a chat message.
6. Show optional 30-day remembering and Delete all. Optional configuration backup
   is passphrase-encrypted and can include tokens; it excludes transcript history.

## Declaration video shot list

Use non-sensitive fixtures and a clean device/test profile. Keep app identity,
permission disclosures and real behavior visible. A scripted shot list or store
screenshots are not a completed declaration video.

| Segment | Show | Evidence |
|---|---|---|
| Opening/setup | Launch app, show purpose, enable service through Android | Accessibility grant is explicit |
| Dictation | Start, microphone indicator, Stop, words inserted; Cancel a take | Motor-access function and microphone FGS |
| Captions | Feature disclosure, select prepared note, native Share, private result | Hearing-access function and explicit transfer |
| Read aloud | Disclosure, chosen block, actual speech, floating Stop | Reading-access function and mediaPlayback FGS |
| Privacy controls | Remembering off/on, Delete all, server setup explanation | No automatic chat send or hidden background scan |

Record/host a reviewer-accessible video URL after the clean fixture and review
backend are ready. No personal chats, API tokens or VPN configuration in the film.
