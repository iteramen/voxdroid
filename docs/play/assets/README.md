# Store artwork and screenshot status

`icon.png`: 512 x 512 RGBA PNG, existing white microphone on black. Full square;
Play applies its own mask. `feature-graphic.png`: 1024 x 500 RGB PNG, same mark,
app name and descriptive copy. These are artwork, not fabricated app screenshots.

Regenerate with `python tools/render_play_artwork.py` (Pillow; Segoe UI on Windows
or DejaVu Sans on Linux). Different fonts can change the feature graphic;
the checked-in Windows render is the reviewed output. The mark geometry derives
from `app/src/main/res/drawable/ic_launcher_foreground.xml`.

**Screenshots pending:** the phone was disconnected during store preparation.
The earlier synthetic test-page screenshot is 1080 x 2424, beyond Play's 2:1
aspect-ratio limit, and is not included as a submission screenshot.

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
