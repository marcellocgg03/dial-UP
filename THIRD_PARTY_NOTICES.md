# Third-party notices

DialUP is free software, released under the GNU General Public License version 3 only
(GPL-3.0-only, see `LICENSE`). It is a derivative work of Tube2U[PnP], itself a fork of upnpTube
(GPL-3.0-only; https://github.com/deaksa/tube2upnp; original upnpTube by Mark Smith).

This file lists what the app is built from or ships with, who holds the copyright, the licence,
and where the source is.

## Code ported to Kotlin (in this repository)

| Component | Copyright | Licence | Where it lives | Source |
|-----------|-----------|---------|----------------|--------|
| Tube2U[PnP] / upnpTube | Mark Smith and the Tube2U[PnP] contributors | GPL-3.0-only | whole app (ported from the Node.js project) | https://github.com/deaksa/tube2upnp (snapshot: upstream main 1714a9d plus two fix branches) |
| yt-cast-receiver 2.1.0 | Copyright (c) Patrick Kan | MIT | `lounge` module (lounge receiver logic) | https://github.com/patrickkfkan/yt-cast-receiver |
| peer-dial (@patrickkfkan/peer-dial 0.1.3) | Copyright (c) 2015 Louay Bassbouss, Fraunhofer FOKUS; fork by Patrick Kan | LGPL-3.0-or-later | `dial` module (DIAL server logic) | https://github.com/patrickkfkan/peer-dial |

The MIT licence of yt-cast-receiver requires this notice to travel with copies of the ported code:

> Permission is hereby granted, free of charge, to any person obtaining a copy of this software and
> associated documentation files (the "Software"), to deal in the Software without restriction,
> including without limitation the rights to use, copy, modify, merge, publish, distribute,
> sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is
> furnished to do so, subject to the following conditions: The above copyright notice and this
> permission notice shall be included in all copies or substantial portions of the Software.
> THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT
> NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
> NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
> DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT
> OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

peer-dial is licensed under the GNU Lesser General Public License version 3 or later. It may be
conveyed under the GPL-3.0 as part of this combined work. Licence text: https://www.gnu.org/licenses/lgpl-3.0.txt

## Binaries shipped inside the APK (youtubedl-android 0.18.1)

The APK bundles the `library` and `ffmpeg` modules of youtubedl-android, which carry prebuilt
arm64-v8a payloads (`libpython.zip.so`, `libffmpeg.zip.so`, `libqjs.so`).

| Component | Version | Copyright / holder | Licence | Source |
|-----------|---------|--------------------|---------|--------|
| youtubedl-android (`library`, `common`, `ffmpeg`) | 0.18.1 | yausername and contributors | GPL-3.0 | tag `0.18.1` (commit 864791ae478e5e32194722337286790829c0a689): https://github.com/yausername/youtubedl-android/tree/0.18.1 ; build notes `BUILD_FFMPEG.md`, `BUILD_PYTHON.md` in the same tag |
| FFmpeg and ffprobe (Termux build) | 7.1.1 | the FFmpeg developers, plus the libraries linked into the Termux build (x264, x265 and others) | GPL-3.0 (the Termux build is configured `--enable-gpl --enable-version3`) | FFmpeg: https://ffmpeg.org/releases/ffmpeg-7.1.1.tar.xz ; Termux build recipe: https://github.com/termux/termux-packages/tree/c7585599a9/packages/ffmpeg (the commit that bumped ffmpeg to 7.1.1; dependency sources are listed in each package folder of that repository) |
| yt-dlp | the release downloaded by the app (Settings, Update now) or the one bundled in the AAR | yt-dlp contributors | Unlicense (public domain) | https://github.com/yt-dlp/yt-dlp |
| yt-dlp-ejs scripts (bundled in the yt-dlp zipapp) | as bundled | yt-dlp contributors | ISC / MIT | https://github.com/yt-dlp/ejs |
| CPython | 3.12.11 | Copyright (c) Python Software Foundation | PSF License Agreement | https://www.python.org/downloads/release/python-31211/ ; licence: https://docs.python.org/3.12/license.html |
| QuickJS (`libqjs.so`) | as bundled by youtubedl-android | Fabrice Bellard and Charlie Gordon | MIT | https://bellard.org/quickjs/ |

### Written offer for the GPL-covered binaries

The FFmpeg and ffprobe binaries and the youtubedl-android code in the APK are covered by the GPL.
The links above point at the exact corresponding source. If a link is ever unavailable, the author
will provide the complete corresponding source for these components on request for at least three
years after the release that contains them: open an issue at the project's GitHub repository
(the one this APK was downloaded from) and ask for it. The source of DialUP itself is that same repository,
at the tag matching the release.

The Android system and the Android SDK are not redistributed.

## Libraries built into the app

Compiled into the APK through Gradle. Source code and licence texts are available at the
upstream locations.

| Library | Copyright | Licence | Licence text |
|---------|-----------|---------|--------------|
| Ktor (server-core, server-cio) | Copyright JetBrains s.r.o. and Ktor project contributors | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0 |
| OkHttp (with Okio) | Copyright Square, Inc. | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0 |
| kotlinx.coroutines, kotlinx.serialization, Kotlin standard library | Copyright JetBrains s.r.o. and contributors | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0 |
| AndroidX (core, activity, lifecycle, Compose, Material 3, Material icons) | Copyright The Android Open Source Project | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0 |
| SLF4J (slf4j-api, slf4j-jdk14) | Copyright QOS.ch Sarl | MIT | https://www.slf4j.org/license.html |
| jsoup | Copyright Jonathan Hedley and contributors | MIT | https://jsoup.org/license |

The Apache-2.0 libraries are used unmodified. Where a NOTICE file ships inside a library, it is
carried into the APK with that library.

## Data and other notes

- yt-dlp downloads extractor code and EJS scripts from GitHub at the user's request (Settings, Update now).
  That code is not part of this repository.
- This project is not affiliated with or endorsed by YouTube, Google, Sonos, or any speaker maker. The names are used only to say what the app works with.
