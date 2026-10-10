# Play Data safety form — voxdroid

The answers for the Play Console's Data safety form, matched to what the app does. The public
policy the form links to is [`docs/privacy.md`](../privacy.md); where the two differ, the policy
and the code are right and this sheet is wrong. The Console's wording changes between revisions:
it is the source of truth for the labels, this sheet for the substance.

## Data collection and security

| Question | Answer | Rests on |
| --- | --- | --- |
| Does your app collect or share any of the required user data types? | **Yes** | The recording is transmitted off the device for transcription (`SttClient.kt`). |
| Is all user data encrypted in transit? | **No** | Plain `http://` to any host but the phone itself is refused while "Allow insecure (http) endpoints" is off, the default, but the user can turn it on (`Endpoints.kt` — `insecureBlocked`; `AndroidManifest.xml` — `usesCleartextTraffic="true"`). Encryption in transit depends on that switch and on the address the user sets. |
| Do you provide a way for users to request that their data be deleted? | **No** (or "not applicable", where the Console offers it) | voxdroid and its developer hold nothing to delete: the audio is never stored (`Recorder.kt`, `Take.kt`). The only user data on the device is the settings in app-private storage, removed when the app is uninstalled (`Settings.kt`). |
| Has your app undergone an independent security review? | **No** | No such review exists. |

## Data types

Every category not listed here is **Not collected**.

| Category | Data type | Collected | Shared | Ephemeral | Required/Optional | Purpose |
| --- | --- | --- | --- | --- | --- | --- |
| Audio files | Voice or sound recordings | **Yes** | **No** | **Yes** | Required | App functionality |
| App activity | Other user-generated content | **Yes** | **No** | **Yes** | Optional | App functionality |

- **Voice recordings — collected.** The recording is transmitted to the speech server the user set,
  and in the cases [`docs/privacy.md` § Where the audio goes](../privacy.md#where-the-audio-goes)
  lists, again to that server, to a language lane or to the default server (`SttClient.kt`,
  `Routed.kt`).
- **Shared: No.** "Shared" means the developer transferring data to a third party. voxdroid's
  developer runs no server and picks no third party: every destination is an address the user
  configured.
- **Ephemeral: Yes.** On the device the audio is used only to produce a transcript, held in memory
  and dropped once the text is delivered, on cancel or when a new dictation starts (`Take.kt`;
  `Recorder.kt`). The user's own server keeps or deletes what it receives under its own policy,
  which voxdroid neither sets nor sees.
- **Required:** dictation is the app's whole purpose.
- **Other user-generated content: the transcript and the glossary.** With the optional cleanup
  pass configured, the transcript text goes to the cleanup server the user set; the glossary the
  user typed goes with every transcription request and every cleanup request (`Polish.kt` —
  `PolishClient`; `SttClient.kt` — `send`). Not shared and ephemeral, for the same reasons;
  **Optional**, because the pass does nothing until its address and model are set and the glossary
  is empty by default.
- A dictated transcript is not an in-app message: voxdroid has no messaging of its own, so
  **Messages** is Not collected. voxdroid has no accounts, analytics, crash reporting or
  third-party SDK that could collect any other category (`app/build.gradle.kts` —
  `dependencies`).

## Personal info and accounts

**Not collected; no account.** voxdroid has no sign-in, no name or email field, and no profile.

## Data handled by the accessibility service

**Nothing beyond the data types above.** The service reads the focused field's editability,
password state and identity, the character before the cursor and any selection, the volume-down
key, the keyboard window's position and language, and the audio mode, as
[`docs/privacy.md` § The accessibility service](../privacy.md#the-accessibility-service) lists.
None of it leaves the device, except that the keyboard's language code goes to the speech server as
the `language` field when the take's language is explicit (`TakeLanguage.kt`; `SttClient.kt`). See
also [`accessibility-declaration.md`](accessibility-declaration.md).

## Target audience

voxdroid is not directed at children ([`docs/privacy.md` § Children](../privacy.md#children)).
