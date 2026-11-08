# Play Data safety form — voxdroid

Draft for version 0.3.0, reviewed against [Play's current definitions](https://support.google.com/googleplay/android-developer/answer/10787469)
on 2026-11-06. This records implementation behavior; no Console submission has been made. The owner
must reconcile the actual form and all distributed versions before a Play release. The public policy is
[`docs/privacy.md`](../privacy.md).

## Collection and security

| Question | Draft answer | Implementation evidence |
| --- | --- | --- |
| Collect any required data types? | **Yes** | Dictated and explicitly shared received audio goes to configured speech servers; optional cleanup receives text. |
| All user data encrypted in transit? | **No** | HTTPS uses Android verification, but the user can explicitly enable remote HTTP. Loopback HTTP is allowed. |
| Deletion mechanism? | **Local transcript deletion exists; remote deletion is not provided** | Voice messages → Delete all and turning remembering off delete local records. Voxdroid cannot delete data retained by user-configured servers. Do not promise end-to-end deletion in the Console. |
| Independent security review? | **No** | No independent certification is claimed. |

## Data types

| Category | Type | Collected | Shared | Ephemeral | Required/Optional | Purpose |
| --- | --- | --- | --- | --- | --- | --- |
| Audio files | Voice or sound recordings | **Yes** | **No, under the user-initiated transfer exception below** | **No blanket claim** | Required for dictation | App functionality |
| Messages | Other in-app messages | **Yes** | **Same exception** | **No** | Optional | App functionality |
| App activity | Other user-generated content | **Yes** | **Same exception** | **No blanket claim** | Optional | App functionality |

**Audio:** microphone dictation and selected received voice notes, including another person's
speech. Only the selected note is imported; no background inbox collection occurs. Initial imports
are Ogg/Opus, bounded to 32 MiB encoded and five decoded minutes. The encoded import briefly exists
in a private, backup-excluded spool; it is deleted before an upload. Normalized WAV stays in memory
(`CaptionAudio`, `CaptionMediaDecoder`, `CaptionProcessor`). Caption import is optional and does
not need microphone permission; the audio category also covers the app's dictation purpose.

**Messages:** received chat voice-note content is explicitly being transcribed, so this draft
includes Other in-app messages, even though Voxdroid has no messaging account or Send action.
Optional cleanup transmits the received-message transcript. This is our application of Play's
chat-content definition, not a Console determination. Headers, sender/chat identity, source URI
and file names are not sent. Routine accessibility observation reads only bounded foreground
header/selection metadata, held in memory (`CaptionAccessibility`, `CaptionController`).

**Other user-generated content:** the user's glossary and optional dictated-text cleanup input.
It is separate from the received-message category (`SttClient`, `PolishClient`).

**Shared:** the draft relies on the explicit user-initiated transfer exception, not merely on the
developer running no server. The user configures destinations, enables captions with a data-flow
disclosure, selects a note and chooses the native Share target. No developer-chosen third party,
advertising recipient or analytics service receives the data. If this expected-transfer condition
changes, reassess the answer rather than carrying it forward.

**Ephemeral:** do not carry forward the old blanket Yes. Imported encoded audio touches a file;
optional remembered captions retain raw/displayed text for 30 days, and the user's speech/cleanup
servers can retain requests under their own policies. Their behavior is not verified by the app.
The final form must reflect those conditions, not infer server ephemerality from in-memory WAV.

**Local retention/deletion:** remembering is independently off by default. A cache hit avoids
another decode/upload under the same audio fingerprint and processing profile. Records hold raw
and displayed text, cleanup outcome, write time and key digest, with no audio or chat identity.
They are excluded from backup/transfer. Reads never extend the 30-day expiry. Delete all and off
invalidate writers already in flight; expiry is pruned when opened/accessed (`CaptionCache`).
Re-transcribe requires a fresh share; expired source grants are not retained.

## Other data and accessibility

Version 0.3.0 also offers explicit encrypted configuration export, including API tokens. The user
chooses a document provider through Android's picker; it can be cloud-backed. The file contains
configuration only, never caption/audio history. Assess the final form against this user-initiated
flow and the selected provider rather than describing tokens as impossible to export. The
passphrase stays on device and Voxdroid has no backup server (`ConfigBackupActivity`, `ConfigBackup`).

No accounts, sign-in, personal-info fields, analytics, advertising, telemetry or crash-reporting SDKs
are present. No contacts list, background conversations or installed-app inventory is scanned. The
two manifest package queries resolve only Telegram/WhatsApp provider ownership; their names are
not uploaded. Captions never paste or send into a conversation. Explicit Copy puts text on the
system clipboard, marked sensitive; clipboard managers may still retain it (`CaptionPanel`).

The accessibility service also reads dictation's focused-field properties, cursor-adjacent text
for spacing, volume-down events, keyboard bounds/language and audio mode. Those stay on device
except explicit dictation language sent with STT. Its changed event subscriptions and motor/hearing
purposes are in [`accessibility-declaration.md`](accessibility-declaration.md). Google approval is
not implied. Voxdroid is not directed at children.

## Read-aloud change (implementation draft)

Optional read aloud adds explicitly selected foreground text sent to a user-configured TTS server.
That can include message text and other user-generated content: include it in the corresponding
existing data-type rows rather than treating it as local-only screen access. The user configures
the destination, enables the data-flow disclosure, presses 🔊 and chooses a block. Reassess the
user-initiated transfer exception against the actual Console form. No blanket ephemerality claim
is made for the server. Candidate/selected text and generated speech stay in phone memory and
are not logged, cached or exported. Only TTS configuration/tokens join explicit encrypted settings
export; read-aloud consent is excluded. The manifest now has five permissions, adding media
playback foreground service for its Stop notification; this feature does not require microphone
permission. The owner must update the accessibility declaration/video before a Play submission.
