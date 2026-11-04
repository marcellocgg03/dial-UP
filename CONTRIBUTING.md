# Contributing to DialUP

Thank you for looking at DialUP. This file explains how to report a problem, how to set up a build, and how a change
gets into `main`. By taking part you agree to the [Code of Conduct](CODE_OF_CONDUCT.md). Security problems are reported
privately, see [SECURITY.md](SECURITY.md).

## Reporting a bug

Use the bug report issue form of the repository. The more of this you can give, the faster it can be reproduced:

- the app version (shown on the release you installed) and where you installed it from;
- the phone model class and the Android version;
- the speaker model and whether it was found by itself or added by address;
- what you did in the YouTube or YouTube Music app, and what you expected.

Attach the log from **Settings, Share log** if it helps. Read it first: it can contain LAN addresses, speaker names, track
titles and video ids (see the Privacy section of the [README](README.md)). Remove what you do not want to publish.

## Proposing a change

Open an issue first for anything larger than a small fix, so the direction can be agreed before you spend time on it.
Small fixes, documentation and tests can go straight to a pull request.

## Build setup

You need JDK 21, the Android SDK packages `platforms;android-37.0` and `build-tools;36.0.0`, and the Gradle wrapper.
On Linux or WSL, `tools/bootstrap-toolchain.sh` installs a pinned, checksum-verified toolchain in your home directory
without root; `. tools/env.sh` then sets `JAVA_HOME` and `ANDROID_HOME` for the shell.

```
. tools/env.sh
./gradlew test                  # JVM unit tests of every module
./gradlew :app:assembleDebug    # debug APK
./gradlew :app:assembleRelease  # release APK, unsigned unless signing material is provided
./gradlew :app:lintDebug        # Android lint
```

Run one Gradle build at a time. The Gradle and Kotlin daemons together need about 4 GB of memory, so two builds, or a
build next to an emulator, can run out of memory on a small machine.

Signing material is never part of the repository. `keystore.properties.example` shows the four values a maintainer
provides, either in an ignored `keystore.properties` file or in the `DIALUP_KEYSTORE_*` and `DIALUP_KEY_*` environment
variables. Without them the release build is simply left unsigned, which is fine for development.

## Where things live

The module table in the [README](README.md) lists the modules. The protocol code (`upnp`, `dial`, `media`, `lounge`,
`renderer`) is plain Kotlin on the JVM with no `android.*` imports, so its tests run in seconds with `./gradlew test` and
need no phone. Only `app` depends on Android. `tools/` holds helper scripts; some of them need a phone over adb or a fake
speaker (`tools/fake-renderer.sh`).

## Tests

A change needs a test. Put it next to the code, in the module's `src/test` directory.

- Fixtures use synthetic values only: no real IP addresses of your network, no video ids from your history, no account,
  device or token values, no real speaker names or serial numbers.
- A new capture of the YouTube lounge protocol goes through `tools/lounge-fixtures.py`, which replaces every credential
  with a placeholder, before it is committed. Run `python3 -I tools/lounge-fixtures.py check <dir>` on the result.
- The documents are checked with `python3 -I tools/check-docs.py`: every relative link must resolve.

## Code rules

- Start every new Kotlin file with a short header comment that says where the code comes from (a port of which upstream
  file, or original) and that it is GPL-3.0-only, like the existing files.
- Do not add a dependency without discussing it in an issue first. The licence is GPL-3.0-only, so a dependency must be
  licence-compatible, and it adds to an APK that is already large.
- Keep the YouTube lounge code in its own module (`lounge`). The protocol is undocumented and can change without
  notice, so the code that depends on it stays in one place that is easy to find and to log.
- Keep protocol modules free of Android types and of the UI toolkit.

## Pull request flow

1. Fork the repository and branch from `main`.
2. One topic per pull request, with a clear description of what changed and why.
3. The three CI checks must pass: `build-test` (unit tests, the debug and release builds and the document and script checks), `lint` (Android lint)
   and `gitleaks` (secret scan).
4. `main` takes squash or rebase merges only and keeps a linear history. Direct pushes to `main` are blocked, so every
   change goes through a pull request.

Please do not include generated files, build outputs, keystores, local settings or logs in a pull request.

## Releases

Releases are made by the maintainer only.

- `versionName` follows semantic versioning with a pre-release suffix while the project is in beta (for example
  `0.9.0-beta.1`). `versionCode` is a plain integer that goes up by one for every published build.
- The release is tagged `v<versionName>` and built and checked with `tools/release.sh`. The APK is published together
  with a `SHA256SUMS` file.
- Signing material stays on the maintainer's machine and never enters the repository.

## Licence

DialUP is GPL-3.0-only, see `LICENSE`. By contributing you agree that your contribution is licensed under
GPL-3.0-only as well.
