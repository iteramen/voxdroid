# Foreground service declaration draft

Prepared for 0.4.3 on 2026-11-13. These are draft answers, not a filed declaration.
Source: `AndroidManifest.xml` and `VoxdroidA11yService.kt`. Google's
[FGS declaration guidance](https://support.google.com/googleplay/android-developer/answer/13392821)
requires a description, user impact and a video for each used service type.

## Microphone

**Use:** user-initiated dictation into a focused field while the user's chosen
keyboard/app remains visible. Holding Volume Down or tapping the floating
microphone starts recording; a second press/tap or the pill's Stop ends it.

**Why immediate/continuous:** deferring recording loses the words the user
expects to dictate; interrupting capture loses part of the take. The recording
is bounded to five minutes. Cancel or screen-off discards it. It does not start
from a background schedule, continuously monitor ambient sound, or record calls
automatically. Microphone permission is requested for this function.

**Video:** show the user's start action, recording pill and Android microphone
indicator, spoken synthetic sentence, Stop and delivery into the original field.
Also demonstrate Cancel. Select the current Console use case matching voice
recording/dictation, or describe it if none fits exactly.

## Media playback

**Use:** synthesize and play an explicitly selected visible text block. The user
taps the floating speaker, then an outlined block. The speaker stops playback;
leaving the source app, locking the phone or starting dictation also cancels it.

**Why immediate/continuous:** deferral disconnects speech from the chosen text;
interruption prevents the user hearing it through. This is an accessibility
read-aloud action, not a background batch download. It requires no microphone.
The service creates a media-playback notification with a Stop action. Notification
visibility depends on Android's settings/permissions; do not promise that every
device shows that action in its notification drawer. Demonstrate the floating Stop.

**Video:** enable Read aloud through its disclosure, choose one synthetic block,
show/hear playback and stop it. Show that the microphone indicator stays off.

No full-screen intent permission or full-screen intent declaration is needed by
the current manifest. Final Console answers must match the submitted bundle.
