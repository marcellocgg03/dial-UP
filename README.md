# DialUP for Android

A native Kotlin Android app (application ID `dev.dialup`) that makes the phone the whole cast bridge. It finds UPnP/DLNA
speakers on the Wi-Fi network, shows each one as a cast target in the YouTube and YouTube Music apps (DIAL), resolves
audio with yt-dlp, remuxes or transcodes with ffmpeg, and serves the stream to the speaker. No always-on server is needed.

DialUP is based on [Tube2U[PnP]](https://github.com/deaksa/tube2upnp), a Node.js project (itself a fork of upnpTube),
and is released under the same licence.

**Status:** beta. The current build is the pre-release listed on the Releases page of this repository, and it was
built for one phone and one set of speakers, so expect rough edges. The YouTube lounge/DIAL protocol is undocumented
and can change without notice; when it does, casting can stop working until the app is updated. What each release
contains, and what is still unverified, is listed in its release notes.

## Requirements

- An Android 8.0 (API 26) or newer phone with an arm64 processor. Other processor types are not built.
- The phone and the speakers on the same Wi-Fi network.
- UPnP/DLNA speakers (network speakers and soundbars that accept a stream from a control app).
- The YouTube or the YouTube Music app on the same phone.

## Install

1. Open the Releases page of this repository and download `DialUP-<version>.apk` and `SHA256SUMS` from the latest release.
2. Check the download. In the folder that holds both files run `sha256sum -c SHA256SUMS`; it must print `OK` for the APK.
3. Check the signature. `apksigner verify --print-certs DialUP-<version>.apk` (`apksigner` is part of the Android SDK
   build-tools) prints the certificate digest; it must equal the certificate fingerprint printed in the release notes.
   Every later release is signed with the same key and shows the same digest.
   The release APKs are signed with a certificate whose SHA-256 fingerprint is `76:F2:03:3A:67:FD:4A:40:04:01:73:6D:3A:E8:11:93:2C:46:E4:94:CB:9B:05:05:3F:73:11:0A:2F:59:EE:AA`
   (`76f2033a67fd4a400401736d3ae811932c46e494cb9b05053f73110a2f59eeaa` without colons); the digest `apksigner` prints must match it.
4. Install the APK. Android asks you to allow installing apps from the source you opened it with (browser or file manager);
   the app is not on Google Play, it is sideloaded.
5. Open DialUP. Two banners can appear, each with a Grant button:
   - notifications (Android 13 and newer), so the status notification and its Stop button are visible;
   - unrestricted battery use. The bridge does not start without it.

## Using it

1. Switch **Bridge enable** on (it is on after a fresh install). The line under it tells you whether the bridge runs. It
   keeps running in the background with a status notification, and it starts again after a phone restart unless you
   switched it off.
2. Open the **Renderers** tab. Each speaker found on the Wi-Fi gets a card; "Looking for renderers on the Wi-Fi" is shown
   until the first one answers.
3. Open the YouTube or the YouTube Music app, tap the cast icon and pick the renderer. In the cast menu it is listed
   with a speaker emoji in front of its name and the text `(app)` after it. Playlists, pause, next and previous work from
   the YouTube app; the card also has play/pause, previous, next, stop and clear buttons.
4. **Add renderer** adds a speaker by its IPv4 address when discovery cannot see it (private Wi-Fi addresses only, for
   example `192.168.1.40`). The speaker must answer before it gets a card, and it is saved under that address, so add it
   again if the speaker gets a new address.
5. The card menu has **Rename** and **Hide from cast menus**; renderers added by address also have **Delete**. A hidden
   renderer is listed under **Hidden** with a Restore button.

Settings tab, every change is saved as soon as you make it:

- **LAN access**: off by default, which means only the phone's own apps can reach the bridge. Switch it on to let any
  device on the LAN access the bridge and cast to the speakers.
- **Clear the renderer when a cast ends**: off by default; when on, the renderer's queue is emptied when a cast ends.
- **Gapless track changes**: on by default, for renderers that support it.
- **Cache size** (default 256 MB) and **Clear cache**: audio files kept on the phone for the next play.
- **Forced audio format**: Automatic by default; Direct, MP4, AAC stream (adts), FLAC and MP3 can be forced. A renderer
  that cannot play a forced format shows an error on its card.
- **yt-dlp**: shows the installed version and **Update now**; the channel is Stable by default, Nightly is selectable.
  The app also updates yt-dlp by itself in the background.
- **Theme**: System, Light or Dark.
- **Write the event log**: on by default, with **Share log** and **Clear log**.

## Limits

- The phone and the speakers must be on the same Wi-Fi. The phone has to stay connected to it for casting to work.
- Turn off a VPN while casting. Casting with a VPN on has not been tested.
- The bridge runs as a foreground service with a status notification (its Stop button switches the bridge off). Keep the
  battery exemption granted, or the bridge does not start.
- yt-dlp can break when YouTube changes something, until it is updated (Settings, Update now).
- Not on Google Play: apps built on yt-dlp are unlikely to pass its review. It is sideloaded only.
- Tested by the author on one phone and a few UPnP/DLNA speakers only. Several features have not been checked on real
  speakers yet and are untested: hiding a renderer and renaming it as seen in the YouTube cast menus, the ask-first
  dialogs when you switch the bridge off during a cast, the card buttons on every speaker model, and how a speaker
  that is unplugged is shown. The release notes list what is still unverified.

## How it works

- The YouTube cast targets are emulated entirely on the phone (DIAL and the YouTube lounge protocol). There is no server
  of its own, no account and no analytics.
- No YouTube sign-in is needed: yt-dlp resolves the audio without logging in.
- The phone talks to YouTube and Google (to receive casts and fetch the audio), to GitHub (yt-dlp updates) and to the
  speakers on your network.
- The event log stays on the phone until you press **Share log**, and you choose where it goes. It can contain LAN
  addresses, speaker names and track titles, so look at it before you send it to anyone.

## Screenshots

| Renderers | Settings | Cast menu |
|-----------|----------|-----------|
| ![The Renderers tab: one card per speaker, each with transport buttons](docs/screenshots/renderers.png) | ![The Settings tab: LAN access, queue clearing, gapless, cache, forced format, yt-dlp, theme](docs/screenshots/settings.png) | ![The YouTube Music cast menu listing the speakers as cast targets](docs/screenshots/cast-menu.png) |

## Build from source

Needs JDK 21, the Android SDK (platform 37, build-tools 36.0.0) and Gradle via the wrapper.
`tools/bootstrap-toolchain.sh` installs the toolchain in user space.

```
./gradlew test                 # JVM unit tests
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease # unsigned unless signing material is provided (see keystore.properties.example)
```

Only arm64 devices are targeted. The APK is sideloaded; yt-dlp based apps are unlikely to pass Play review.

| Module | Job |
|--------|-----|
| `upnp` | SSDP, SOAP control, device descriptions |
| `dial` | DIAL server and SSDP responder, one per speaker |
| `lounge` | YouTube lounge receiver and InnerTube client |
| `media` | Local media server (Range, pipe, file cache) |
| `renderer` | Per-speaker playback logic, queue, gapless hand-off |
| `app` | Android service, config, yt-dlp / ffmpeg wiring |

`tools/` holds helper scripts for on-device testing (adb, fake speaker, log reports).

## Contributing and security

Issues and pull requests are welcome. Run `./gradlew test` before opening a pull request, and keep test fixtures
synthetic (no real addresses, video ids or tokens). Report security problems privately through the repository's
"Report a vulnerability" button on the Security tab, not in a public issue.

## Licence

GPL-3.0-only, see `LICENSE`. Third-party components are listed in `THIRD_PARTY_NOTICES.md`.

DialUP is based on Tube2U[PnP] / upnpTube (GPL-3.0-only), so it is released under the same licence.
The APK also bundles GPL-covered binaries (FFmpeg and ffprobe from the Termux build, and the youtubedl-android
modules) together with CPython, QuickJS and yt-dlp.

### Source offer

- The source of DialUP is this repository; every release is tagged, and the tag matches the APK.
- The source of the bundled binaries is linked, pinned to the exact versions, in `THIRD_PARTY_NOTICES.md`
  (youtubedl-android tag `0.18.1`, FFmpeg 7.1.1, the Termux ffmpeg build recipe at commit `c7585599a9`).
- If a link is unavailable, open an issue and the complete corresponding source will be provided.
