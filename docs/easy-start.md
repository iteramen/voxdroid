# Easy start with Groq

Use Voxdroid without running a speech server. The optional **Easy start with Groq** preset
sets up hosted speech and cleanup; you supply your own Groq API key. Installing or upgrading
Voxdroid never applies the preset automatically.

## Set up

1. Install Voxdroid from [GitHub Releases](https://github.com/iteramen/voxdroid/releases).
   If Android blocks accessibility, follow the [sideload guide](sideload).
2. In **Speech → Easy start with Groq**, review the destination and changes, then tap **Use Groq**.
   Cancel leaves your setup unchanged. Export a password-protected backup first if you want to
   preserve an existing setup for switching back later.
3. Open [Groq Console](https://console.groq.com/), sign in or create an account, then open
   [API Keys](https://console.groq.com/keys) and create a key. Account access, terms and usage
   limits are controlled by Groq.
4. Paste the key into **Speech → STT token**, then into **Cleanup → Cleanup token**.
   Both fields are needed. Voxdroid never reads the clipboard automatically or creates a key for you.
   Keep the key private; do not put it in a screenshot, issue or chat.
5. Return to the main screen and follow the status card: enable Voxdroid in Android Accessibility
   settings and grant microphone permission for dictation. **Languages → Your languages** can
   remain configured; there is no need to delete the list for Groq.
6. Open **Try a dictation** or an empty text field. Hold Volume Down until Recording appears,
   say a short non-sensitive phrase, then press Volume Down again to stop. Text should land in the field.

## What changes

| Setting | Value |
| --- | --- |
| Speech endpoint | `https://api.groq.com/openai/v1` |
| Speech model | `whisper-large-v3-turbo` |
| Cleanup endpoint | `https://api.groq.com/openai/v1` |
| Cleanup model | `openai/gpt-oss-20b` |
| Cleanup | Enabled |
| Speech and cleanup tokens | Cleared; paste your new key into both fields |

These are base URLs: do not append `/audio/transcriptions` or `/chat/completions`.
The endpoint/models are documented by Groq ([speech](https://console.groq.com/docs/speech-to-text),
[cleanup model](https://console.groq.com/docs/model/openai/gpt-oss-20b)); checked 2026-11-14.
Availability, limits and pricing can change. Voxdroid does not provide a quota or promise free service.

Custom language servers remain configured and can still receive audio. Their dedicated tokens remain,
but **Send the STT token** is turned off so a new Groq key cannot be sent to an old server. To use
Groq for those languages too, clear their lane URLs in **Languages**. Read aloud, glossary,
language choices, cleanup formatting, triggers and other settings are preserved. The preset
does not configure narration or enable voice-message captions.

## Where your words go

The confirmation chooses Groq as a third-party recipient. Recordings and the glossary go there
for recognition; transcripts also go there when cleanup runs. This includes received voice-note
audio if you separately enable captions and choose a note. Retained language servers can also
receive audio. The main screen makes a token-free reachability request to the speech endpoint;
applying the preset does not itself send audio or transcripts.

Groq controls retention and processing at its service. Review
[Groq's data documentation](https://console.groq.com/docs/your-data) and linked policies.
Voxdroid operates no server, stores tokens privately and excludes them from Android backup.
Your optional encrypted settings export includes tokens. Read the [privacy policy](privacy).

## If it fails

- **HTTP 401/403:** check both token fields and your Groq account/model access.
- **HTTP 429:** check the account's current rate and usage limits; retry later.
- **HTTP 400:** check the exact model/base URL. Current Voxdroid retries a recognized rejection
  of optional language candidates using standard fields; do not erase your language list.
- **No voice recorded:** check microphone permission and speak after Recording appears.
- **Cleanup failed:** the raw transcript is delivered with a failure notice.
- **Unexpected language server:** inspect its custom lane in Languages; a lane overrides the base server.

The readiness indicator checks reachability, not whether your key, model or quota can complete
a request. The first short dictation is the account-specific check.
