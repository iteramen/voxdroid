# Android market snapshot — 2026-11-13

Quick primary-source comparison, not an exhaustive market or revenue study.
Prices below are publisher-listed USD, not a quote for an Israeli Play account.

| Product | Relevant use | Monetization observed | Implication for Voxdroid |
| --- | --- | --- | --- |
| [Google Voice Access](https://play.google.com/store/apps/details?id=com.google.android.apps.accessibility.voiceaccess&hl=en) | Voice navigation, text editing and dictation for people with motor impairments | Free install | Accessibility is an established purpose, not an empty market. Voxdroid should explain its narrower workflow and setup clearly. |
| [FUTO Keyboard](https://docs.keyboard.futo.tech/about?highlight=purchase) / [Voice Input](https://voiceinput.futo.tech/) | Private, on-device voice input | Keyboard publisher lists $6.99 through FUTO Pay or $11.99 through Play; pay-if-you-like-it model | Privacy alone is not unique. FUTO also offers offline inference; Voxdroid requires configured servers. |
| [Transcriber for WhatsApp](https://play.google.com/store/apps/details/?hl=en-US&id=it.mirko.transcriber) | Reading received voice notes | Ads and in-app purchases; exact purchase prices not verified | Captioning has direct competition. Private in-context display and Telegram support should be demonstrated, not merely asserted. |
| [@Voice Aloud Reader](https://hyperionics.com/atvoice/index.asp) | Shared text, web pages and documents read aloud | [Publisher premium license](https://hyperionics.com/atVoice/pricing.asp): permanent purchase, removes ads and adds features; page displays conflicting $15/$20 figures, so exact price is unconfirmed | A one-time client purchase is an existing model. Local Play pricing can differ. |
| [Speechify](https://speechify.com/pricing/) | Text reading, scanning and richer voice features | Free tier; Premium $29/month or $139 billed yearly on the publisher page | Subscriptions exist for richer services; this does not establish willingness to subscribe to a bring-your-own-server client. |

## Positioning worth testing

Voxdroid combines motor-access dictation with the existing keyboard, private
voice-message captions for hearing access, and selected-block narration for
reading access. The user chooses the speech/cleanup/TTS servers. English,
Hebrew and Russian configuration slots are useful, but voice/language quality
depends on those servers. Do not claim universal app compatibility, offline
operation, unique accessibility, or disability certification.

The largest adoption obstacle is likely server setup. That is an inference
from Voxdroid's current requirements, not measured user research. Test setup
with people who use assistive technology and helpers who configure devices.
Simple onboarding, readable controls and reliable recovery deserve priority.

A one-time client license is worth considering for a BYO-server product.
A hosted subscription would be a different service with operating costs,
privacy obligations and model-license review. Neither pricing nor hosted
service development is approved by this snapshot. The personal-use backend
models tested on the owner's rig are not a commercial hosting license.

For Play, disability assistance must be the app's actual primary purpose to
use `isAccessibilityTool=true`; adding accessibility marketing to a general
assistant is insufficient. The listing, in-app disclosures and demonstration
must agree. [Google's AccessibilityService policy](https://support.google.com/googleplay/android-developer/answer/10964491?hl=en).
