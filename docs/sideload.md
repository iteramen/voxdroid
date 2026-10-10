# Installing voxdroid from GitHub

Each release on [GitHub Releases](https://github.com/iteramen/voxdroid/releases) carries a signed
APK. Android makes an app from outside Google Play, and especially one that is an accessibility
service, jump a few hurdles. This page walks through them. It applies to Android 13 and later,
which is all voxdroid supports.

## 1. Download, and optionally verify

On the phone, open the latest release and download `voxdroid-v<version>.apk`.

To check the file first, download it and `SHA256SUMS` on a computer:

```sh
sha256sum -c SHA256SUMS
gh attestation verify voxdroid-v<version>.apk --owner iteramen
```

The attestation proves the APK came from this repository's release workflow at that tag. Every
GitHub release is signed with the same key, whose SHA-256 certificate fingerprint is:

```
E3:5D:32:02:9F:C5:E3:6E:61:EF:18:27:22:46:84:F2:7A:6F:08:C8:20:72:C5:EB:1C:72:2C:34:35:70:02:CD
```

`apksigner verify --print-certs voxdroid-v<version>.apk` (Android SDK build-tools) prints it.

## 2. Allow the install

Open the downloaded file. Android asks whether the app you opened it from, your browser or Files,
may install apps. Tap **Settings**, turn on **Allow from this source**, go back and tap
**Install**. You can turn the permission off again afterwards.

## 3. Get past Play Protect

Play Protect checks apps that don't come from Google Play. For voxdroid it may say it doesn't
recognise the app, or that the app is unsafe. Tap **More details**, then **Install anyway**. If it
offers to send the app for a scan, either answer installs it.

Play Protect may also flag an installed accessibility-service app later and turn it off or
remove it. If that happens, you can turn off **Play Store → your profile → Play Protect → ⚙ →
Scan apps with Play Protect**, at your own risk, since it stops scanning every app, and install
again. The Google Play version doesn't have this problem.

## 4. Allow restricted settings, then turn the service on

Android blocks turning on an accessibility service for a sideloaded app until you allow it once:

1. Open voxdroid. The status card says the service is off; tap **Open Accessibility settings**.
2. Tap **voxdroid**. Android shows **Restricted setting** and won't let you turn it on yet. Tap
   **OK**.
3. Open **Settings → Apps → voxdroid**, tap **⋮** in the top-right corner, then **Allow
   restricted settings**, and confirm with your PIN or fingerprint. The menu item only appears
   after step 2.
4. Back in **Accessibility → voxdroid**, turn it on and confirm.

The first dictation asks for the microphone permission. After that, the rest of the setup is in
the [README](../README.md#set-up).

## Updates

voxdroid doesn't update itself. Install a newer APK from GitHub over the old one the same way;
your settings stay, because every GitHub release has the same signature.

The Google Play version is signed by Google, so it and the GitHub version can't update each
other. Switching between them means uninstalling first, which deletes your settings and tokens.

## Coming: developer verification

Google is adding developer verification for apps on certified Android devices. It starts on 30
September 2026 in Brazil, Indonesia, Singapore and Thailand and goes worldwide in 2027. voxdroid
will be registered before it applies to you. Until then nothing changes.
