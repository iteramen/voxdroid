# Store artwork and screenshot status

`icon.png`: 512 x 512 RGBA PNG, existing white microphone on black. Full square;
Play applies its own mask. `feature-graphic.png`: 1024 x 500 RGB PNG, same mark,
app name and descriptive copy. These are artwork, not fabricated app screenshots.

Regenerate with `python tools/render_play_artwork.py` (Pillow; Segoe UI on Windows
or DejaVu Sans on Linux). Different fonts can change the feature graphic;
the checked-in Windows render is the reviewed output. The mark geometry derives
from `app/src/main/res/drawable/ic_launcher_foreground.xml`.

**Screenshots:** three captures under `screenshots/`, taken on 2026-11-18 from the
signed 0.6.4 GitHub release (`effb625`, versionCode 15) installed on a Pixel 10
(Android 17, 1080 x 2424), with `adb exec-out screencap -p`. The display was set
to 1080 x 2160 for the captures (`wm size`) and reset afterwards, because the
native height is beyond Play's 2:1 aspect-ratio limit. The status bar, the top
150 px, is cropped off because it showed the phone's own notification icons; the
files are 1080 x 2010 RGB PNGs and nothing else in them is edited.

- `01-features.png`: the main page scrolled to the feature menu. The status card
  above the menu names the phone's own endpoint and is left out of the frame.
- `02-dictation.png`: the Try page during a dictation, with the keyboard up and
  the recording pill showing its timer, Stop and cancel. The take was cancelled,
  so no audio was sent and there is no transcript.
- `03-triggers.png`: the Triggers page.

No narration or caption interaction is captured yet: each needs a speech server
answering with synthetic content.

Capture at least two real screens from the release build using synthetic text:
the main feature menu and a visible narration/caption interaction. Do not expose
tokens, private endpoints, ordinary chats or personal notifications. Do not
invent transcripts or alter controls to imply functionality. Record build SHA,
version, device dimensions and capture procedure here. If temporarily changing
the device's display size, save and restore its original override in a finally
block. Save lossless RGB PNG files under `screenshots/`; no alpha channel.

Run `python tools/check_play_package.py` for prepared artwork and text, or
`python tools/check_play_package.py --submission` to require screenshots too.
Passing this check is not Google approval or a completed release checklist.

References: [preview assets](https://support.google.com/googleplay/android-developer/answer/9866151)
and [icon specification](https://developer.android.com/distribute/google-play/resources/icon-design-specifications).
