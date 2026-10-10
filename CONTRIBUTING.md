# Contributing to voxdroid

voxdroid does not accept code contributions; pull requests are closed unmerged. Bug reports and
ideas are welcome as [issues](https://github.com/iteramen/voxdroid/issues). Security problems go
through [SECURITY.md](SECURITY.md), not an issue. The rest of this file is how voxdroid is built.
How voxdroid works is in [docs/design.md](docs/design.md).

## Build

JDK 17 and the Android SDK (platform 36, build-tools 36). On Linux:

```sh
./gradlew lint testDebugUnitTest assembleDebug
```

That is the gate every pull request must pass, and what CI runs. Two more CI jobs:
`release-witness.yml` (the release bundle builds and warns that it is unsigned) and
`dependency-audit.yml` (OSV-Scanner over the shipped dependencies; blocks).

On Windows, build in WSL Ubuntu against the Windows checkout (`/mnt/c/...`):

```sh
sudo apt-get update && sudo apt-get install -y openjdk-17-jdk-headless unzip
mkdir -p ~/Android/Sdk/cmdline-tools && cd ~/Android/Sdk/cmdline-tools
curl -LO https://dl.google.com/android/repository/commandlinetools-linux-15859902_latest.zip
unzip -qo commandlinetools-linux-15859902_latest.zip && rm -rf latest && mv cmdline-tools latest
echo 'export ANDROID_HOME=$HOME/Android/Sdk' >> ~/.profile
echo 'export PATH=$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH' >> ~/.profile
. ~/.profile
yes | sdkmanager --licenses >/dev/null; sdkmanager "platform-tools" "platforms;android-36" "build-tools;36.0.0"
```

- `which sdkmanager` must print the `~/Android/Sdk` path: Ubuntu's apt `sdkmanager` is a
  different tool.
- `/mnt/c` has no inotify (hence `org.gradle.vfs.watch=false`) and slow I/O; builds there are
  slower than CI.
- Shell scripts carry `eol=lf` in `.gitattributes`. A script committed from Windows lands without
  its executable bit: `git update-index --chmod=+x <file>`.

## Dependency verification

Every artifact is checked against `gradle/verification-metadata.xml` (sha256), and the Gradle
distribution against `distributionSha256Sum`. The XML is generated, never hand-edited. On any
dependency, AGP or Gradle bump, delete it and regenerate from a clean Gradle home over every task
CI runs, then set the new distribution sum from the `.sha256` file Gradle publishes:

```sh
export GRADLE_USER_HOME=$(mktemp -d)
rm gradle/verification-metadata.xml
./gradlew --write-verification-metadata sha256 lint testDebugUnitTest assembleDebug bundleRelease :app:dependencies
```

The file records `aapt2` for Linux only, so verification fails on a Windows or macOS host build.

## Dependencies

What ships in the APK is limited to `androidx.preference` and `com.google.android.material`
plus what the build injects; a new runtime library needs a discussion in an issue first. Views
and XML, `HttpURLConnection` with a hand-written multipart writer, `org.json` from the platform,
Kotlin only: no Compose, OkHttp, Retrofit or extra coroutine libraries. Tests use JUnit 4, with
`org.json:json` on the test classpath because android.jar's copy is a stub there.

## Secrets and logging

- Tokens live in their own `secrets` SharedPreferences file, which `dataExtractionRules`
  excludes from cloud backup and device transfer. They never reach a log, an error string or the
  repository.
- Transcript text is never logged at the default level.
- Release signing reads `keystore.properties` (gitignored, repo root; a relative `storeFile`
  resolves against the repo root). A file with a missing key fails the build.

## Changes

Branch from `main`, one change per PR, squash-merged. A bug fix starts with a failing test. Behaviour only a handset can show goes in `docs/testing.md` as a checklist row.

voxdroid follows its desktop sibling, [byovox](https://github.com/iteramen/byovox), for every
dictation behaviour that isn't phone-specific.
