# Third-party notices

This project is a derivative of Tube2U[PnP] / upnpTube (GPL-3.0-only, github.com/deaksa/tube2upnp).

| Component | Licence | Use |
|-----------|---------|-----|
| yt-cast-receiver 2.1.0 | MIT | Lounge receiver logic ported to Kotlin (`lounge`) |
| peer-dial | LGPL-3.0-or-later (Fraunhofer FOKUS) | DIAL server logic ported to Kotlin (`dial`) |
| youtubedl-android 0.18.1 (yausername) | GPL-3.0 | Bundles CPython, yt-dlp, QuickJS and ffmpeg/ffprobe |
| ffmpeg 7.1.1 / ffprobe (Termux build) | GPL-3.0 | Run as a subprocess; source: https://github.com/termux/termux-packages and https://ffmpeg.org |
| yt-dlp | Unlicense | Stream resolution |
| CPython (PSF), QuickJS (MIT) | Permissive | Bundled by youtubedl-android |
| Ktor, OkHttp, kotlinx.*, AndroidX | Apache-2.0 | Runtime libraries |
| jsoup | MIT | XML parsing |

Anyone redistributing a built APK is redistributing GPL ffmpeg binaries and must offer the
corresponding source (links above and the youtubedl-android 0.18.1 tag).
