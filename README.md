# DialUP for Android

DialUP is the Android port of Tube2U[PnP].

A native Kotlin Android app (application ID `dev.dialup`) that makes the phone the whole cast bridge. It finds UPnP/DLNA speakers
(Sonos Amp, Arc, SYMFONISK) on the Wi-Fi network, shows each one as a cast target in the YouTube and
YouTube Music apps (DIAL), resolves audio with yt-dlp, remuxes or transcodes with ffmpeg, and serves
the stream to the speaker. No always-on server is needed.

It is a port of [Tube2U[PnP]](https://github.com/deaksa/tube2upnp) (a Node.js project, fork of upnpTube).
`PARITY.md` records which upstream commit has been ported and how each Node file maps to Kotlin.

**Status:** work in progress, built for one phone and one set of speakers. Expect rough edges.
The YouTube lounge/DIAL protocol is undocumented and can change without notice.

## Modules

| Module | Job |
|--------|-----|
| `upnp` | SSDP, SOAP control, device descriptions |
| `dial` | DIAL server and SSDP responder, one per speaker |
| `lounge` | YouTube lounge receiver and InnerTube client |
| `media` | Local media server (Range, pipe, file cache) |
| `renderer` | Per-speaker playback logic, queue, gapless hand-off |
| `app` | Android service, config, yt-dlp / ffmpeg wiring |

`tools/` holds helper scripts for on-device testing (adb, fake speaker, log reports).

## Build

Needs JDK 21, the Android SDK (platform 37, build-tools 36.0.0) and Gradle via the wrapper.
`tools/bootstrap-toolchain.sh` installs the toolchain in user space.

```
./gradlew test        # JVM unit tests
./gradlew :app:assembleDebug
```

Only arm64 devices are targeted. The APK is sideloaded; yt-dlp based apps are unlikely to pass Play review.

## Licence

GPL-3.0-only, see `LICENSE`. Third-party components are listed in `THIRD_PARTY_NOTICES.md`.

DialUP is a derivative of Tube2U[PnP] / upnpTube (GPL-3.0-only), so it is released under the same licence.
The APK also bundles GPL-covered binaries (FFmpeg and ffprobe from the Termux build, and the youtubedl-android
modules) together with CPython, QuickJS and yt-dlp.

### Source offer

- The source of DialUP is this repository; every release is tagged, and the tag matches the APK.
- The source of the bundled binaries is linked, pinned to the exact versions, in `THIRD_PARTY_NOTICES.md`
  (youtubedl-android tag `0.18.1`, FFmpeg 7.1.1, the Termux ffmpeg build recipe at commit `c7585599a9`).
- If a link is unavailable, open an issue and the complete corresponding source will be provided.
