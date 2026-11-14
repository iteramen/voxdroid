# First Google Play release — issue #5

Resumed by the owner on 2026-11-13. Account does not yet exist. Public support
email approved by the owner: **byovox.licensing@gmail.com**. No Console entry,
account registration, submission, price or release rollout has been made.
#2 remains the packaging umbrella; this checklist belongs to #5.

## Prepared in the repository

- [English listing text](listing/en-US/full-description.txt), short description
  and title. Accessibility is the primary purpose; external server setup and
  app/language limitations are prominent. No bundled hosting or universal-app
  compatibility is promised.
- [Data safety draft](data-safety.md), [accessibility declaration](accessibility-declaration.md),
  [foreground-service declaration draft](foreground-service-declaration.md),
  and [review access / demonstration plan](reviewer-access.md).
- [Market snapshot](market-snapshot-2026-11-13.md): primary-source comparisons,
  payment models and implications. This does not select Voxdroid's price.
- Store artwork and screenshot provenance: [assets](assets/README.md).
- `tools/check_play_package.py` checks listing lengths and image dimensions.
  It does not certify policy compliance or pretend an unsigned AAB is uploadable.

## Owner/account steps

1. Register a [Google Play Console developer account](https://support.google.com/googleplay/android-developer/answer/6112435).
   Choose the correct personal/organization identity, accept the agreement,
   pay Google's one-time US$25 fee and complete the required identity/device
   verification. Those personal, payment and legal steps belong to the owner.
2. Decide free versus paid **before offering the app for free on Play**. Google's
   [pricing rules](https://support.google.com/googleplay/android-developer/answer/6334373)
   allow paid-to-free, but not free-to-paid for the same package. This restriction
   concerns the download price; in-app products are a separate design and are
   not implemented here. Do not silently choose a free listing while pricing is open.
3. Create an app (not a game), default language English, application ID
   `io.github.iteramen.voxdroid`. Suggested category: Tools. Confirm target
   audience, countries, content-rating answers, ads = No, and the current form.
   No account creation exists inside Voxdroid. Do not claim a Google-assigned
   content rating or disability certification before review.
4. Choose and record Play App Signing identity and upload-key custody. The
   GitHub release key and Play upload key are distinct. A Google-generated app
   signing key generally means existing GitHub installations cannot update in
   place to Play: compare certificate fingerprints first. Preserve an encrypted
   settings backup and verify restore before any uninstall; never erase a
   configured phone just to test a track.
5. Provide reviewers a working, isolated, internet-reachable HTTPS service
   configuration and any review-only credentials. The developer's private VPN
   endpoint is not a reviewer-access plan. See [reviewer-access.md](reviewer-access.md).

## Build and signing handoff

The current source is 0.4.3, versionCode 9, minSdk 33, targetSdk 36. Build with
the existing Linux/WSL toolchain; do not invent another release version merely
to prepare the listing. Check Console for previously consumed version codes
before an actual upload.

```sh
./gradlew lint testDebugUnitTest assembleDebug bundleRelease
```

With no `keystore.properties`, the AAB is intentionally unsigned and **not ready
to upload**. Its output is `app/build/outputs/bundle/release/app-release.aab`.
Record its SHA256, source commit and signature status with the release evidence.
Do not commit artifacts or signing secrets.

Preparation witness, 2026-11-13: `bundleRelease` passed on source
`a32fa8781d688fd2c2f20f43ade33c6fb4a83773` with documentation/artwork changes
in the worktree, no app code changes. JDK `jarsigner -verify` reported unsigned.
The 5,013,673-byte AAB SHA256 was
`5e8bdacf9276f0e264ce27b80bff7c538487738b1635f587c345d5cb006bbbfe`.
This identifies that local artifact only; a later signed rebuild has a different
checksum and needs fresh verification. No upload key or Play app was created.

After a secure location and backup for the upload key are chosen, generate a
dedicated key with JDK `keytool` (interactive passwords, never shell literals):

```sh
keytool -genkeypair -keystore /secure/path/voxdroid-play-upload.jks \
  -alias upload -keyalg RSA -keysize 3072 -validity 10000
keytool -exportcert -rfc -keystore /secure/path/voxdroid-play-upload.jks \
  -alias upload -file /secure/path/voxdroid-play-upload.pem
```

Use the existing ignored `keystore.properties` mechanism (`storeFile`,
`storePassword`, `keyAlias`, `keyPassword`), protect that file and key with
owner-only filesystem access, and keep a verified encrypted backup. Rebuild,
verify the JAR signature and compare the upload certificate with Console.
Do not extract/reuse GitHub Actions release secrets for this step.

## Submission and testing

- Start on the **internal testing** track. Upload the signed bundle, install
  through Play and perform the manual checklist on that delivered build.
  Local sideload tests and CI do not prove Play delivery or approval.
- File the accessibility and microphone/mediaPlayback FGS declarations with
  demonstration videos; reconcile the Data safety draft against actual behavior.
  Internal-only tracks are exempt from the public Data safety section, but this
  does not make the declaration unnecessary for a later closed/public release.
- If registering a new personal account, current Google guidance requires a
  **closed test with at least 12 testers continuously opted in for 14 days**
  before applying for production access. Internal testing does not satisfy this.
  Confirm the requirements displayed for the actual account; organization
  eligibility must be genuine, not a workaround. [Testing requirements](https://support.google.com/googleplay/android-developer/answer/14151465)
- Recruit real testers, including people with the motor, hearing and reading
  needs the app is built for. Record setup friction, gesture/touch accessibility,
  caption readability, language quality, error recovery and compatibility gaps.
  Do not invent testing feedback, users or completed days.
- Only after review/acceptance, update README's “Google Play: coming soon” with
  the actual listing URL. Keep #5 open until its internal-track acceptance is met.

## Facts still needed

Account identity/type and verification; download pricing; countries and audience;
signing identity/key custody; reviewer service and credentials; demonstration
video URL; actual track installation and required closed-test evidence.
