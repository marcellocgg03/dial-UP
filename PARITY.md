# PARITY.md

Where the Kotlin port stands against the Node project it is ported from. This file is created by plan 02-03 and completed by plan 02-14 (one plan writes it at a time).

## (a) Ported Node commit

| Item | Value |
|------|-------|
| Node project | tube2upnp (fork of upnpTube, github.com/deaksa/tube2upnp) |
| Ported commit | `5bd3025cee730d3f5464ae585917af3d90e1dfc6` |
| Branch | `deaksa-fixes` (upstream main `1714a9d` plus `fix/stable-receiver-ports` and `fix/gapless-adopt`) |
| Snapshot taken | 2026-10-19 |
| Local copy | `reference/` (gitignored, read-only; `reference/SOURCE.txt` records the commit). It is not part of the repository, so this file is the tracked record. |
| Libraries | yt-cast-receiver 2.1.0 (MIT) and peer-dial (LGPL-3.0-or-later), copied from the npm packages the Node project depends on |

## (b) File map

Status values: `planned` (this phase, plan 02-14 sets the final status), `not ported`, `out of scope`.

| Node file | Kotlin file(s) | Status |
|-----------|----------------|--------|
| `src/config.js` | `renderer/Config.kt` (Phase 2 defaults; `config.json` is Phase 3 SVC-07) | planned |
| `src/dataStore.js` | `renderer/DataStore.kt` | planned |
| `src/formats.js` | `media/Formats.kt`, plus `MIME_ALIASES` in `upnp/Upnp.kt` (`:upnp` does not depend on `:media`, and `sinkAccepts` needs the aliases) | planned |
| `src/history.js` | none | not ported (v2 UI-02) |
| `src/index.js` | `renderer/RendererRegistry.kt`, `renderer/Bridge.kt`, `app/CastFeature.kt` | planned |
| `src/logger.js` | `upnp/EventSink.kt`, `app/EvidenceLog.kt` | planned |
| `src/mediaServer.js` | `media/MediaServer.kt`, `media/RangeParser.kt`, `media/PipeLauncher.kt` | planned |
| `src/net.js` | `renderer/Net.kt` | planned |
| `src/ports.js` | `renderer/Ports.kt` | planned |
| `src/renderer.js` | `renderer/Renderer.kt` (Phase 2 slice) | planned |
| `src/resolver.js` | `media/Resolver.kt` | planned |
| `src/settings.js` | none | not ported (status-page settings, v2 UI-01) |
| `src/soap.js` | `upnp/Soap.kt` | ported in plan 02-03 |
| `src/sponsorblock.js` | none | not ported (v2 EXT-01) |
| `src/ssdp.js` | `upnp/Ssdp.kt`, `upnp/SsdpMessage.kt` | planned |
| `src/stats.js` | none | not ported (v2 UI-03) |
| `src/statusServer.js`, `src/ui/` | none | out of scope (no status web page) |
| `src/upnp.js` | `upnp/Upnp.kt` | ported in plan 02-03 |
| `test/fakeRenderer.js` | `upnp/src/testFixtures/.../FakeRenderer.kt` | ported in plan 02-03 |

Libraries:

| Library file | Kotlin file(s) | Status |
|--------------|----------------|--------|
| yt-cast-receiver 2.1.0 (MIT): `lib/app/*.js`, `lib/Player.js`, `lib/YouTubeCastReceiver.js`, `lib/Constants.js` | `lounge/*.kt`; planned names: `Constants`, `Player`, `Playlist`, `PlaylistRequestHandler`, `Sender`, `CastReceiver`, `BindParams`, `ChunkDecoder` (replaces `Message.parseIncoming`), `Message`, `Session`, `LoungeLog`, `YouTubeApp`, `YouTubeCastReceiver` | planned |
| yt-cast-receiver `lib/utils/DataStore.js` | `lounge/DataStore.kt` (interface contract, written in plan 02-03) | ported in plan 02-03 |
| yt-cast-receiver `lib/app/PairingCodeRequestService.js` | none | not ported (v2 PAIR-01) |
| peer-dial (LGPL-3.0-or-later) | `dial/DialServer.kt`, `dial/DialXml.kt`, `upnp/SsdpResponder.kt`, `upnp/SsdpMessage.kt` | planned |

## (c) Node tests

Every test title in `reference/tube2upnp/test`, where it is ported and which Kotlin class holds it. The Kotlin test keeps the Node title through `@DisplayName("<Node title>")` (PAR-02). The "Status" column is the phase that ports the test: `Phase 2`, `Phase 3`, `Phase 4`, `v2` or `out of scope`. `test/fakeRenderer.js` is a helper, not a test; it is the `FakeRenderer.kt` test fixture listed above.

| Node test file | Test title | Status | Kotlin test class |
|----------------|------------|--------|-------------------|
| `history.test.js` | counts plays per track and per speaker, newest plays first | v2 | (UI-02) |
| `history.test.js` | the list is capped but counts are kept | v2 | (UI-02) |
| `history.test.js` | persists to DATA_DIR/history.json and tolerates a corrupt file | v2 | (UI-02) |
| `history.test.js` | CSV: quoted cells, no spreadsheet formulas, speaker filter | v2 | (UI-02) |
| `history.test.js` | clear() empties everything on disk too | v2 | (UI-02) |
| `mediaServer.test.js` | proxies with Range, DLNA headers, and re-resolves on 403 | Phase 3 | (planned, direct proxy) |
| `ports.test.js` | hands out consecutive ports and reuses a released one | Phase 2 | renderer PortsTest |
| `ports.test.js` | a speaker keeps its port across restarts whatever the discovery order | Phase 2 | renderer PortsTest |
| `ports.test.js` | a new device never takes the port remembered for an offline one | Phase 2 | renderer PortsTest |
| `ports.test.js` | devices that never came up (filtered, failed) are not remembered | Phase 2 | renderer PortsTest |
| `ports.test.js` | tolerates a corrupt ports.json | Phase 2 | renderer PortsTest |
| `ports.test.js` | ignores a ports.json of the wrong shape, and entries that are not ports | Phase 2 | renderer PortsTest |
| `ports.test.js` | speakers coming up at the same time are all saved without write errors | Phase 2 | renderer PortsTest |
| `remux.test.js` | mp4: lossless remux to a non-fragmented, faststart MP4 with byte ranges | Phase 3 | (planned, format chain and download) |
| `remux.test.js` | mp4 at offset (renderer cannot Seek) reuses the downloaded source | Phase 3 | (planned, format chain and download) |
| `remux.test.js` | adts: lossless AAC stream, starts at offset | Phase 2 | media MediaServerLadderTest |
| `remux.test.js` | flac (${bits}-bit): lossless file with byte ranges and correct length | Phase 3 | (planned, format chain and download) |
| `remux.test.js` | flac at offset (renderer cannot Seek) | Phase 3 | (planned, format chain and download) |
| `remux.test.js` | mp3 transcode | Phase 2 | media MediaServerLadderTest |
| `remux.test.js` | adts/mp3: a refused YouTube URL is retried with a fresh extraction | Phase 2 | media MediaServerLadderTest |
| `remux.test.js` | adts/mp3: falls back to the yt-dlp download when YouTube keeps refusing ffmpeg | Phase 3 | (planned, format chain and download) |
| `remux.test.js` | adts/mp3: a stream that cannot be read at all is a 502, not an empty 200 | Phase 2 | media MediaServerLadderTest |
| `remux.test.js` | a renderer that hangs up while the stream is being prepared leaves nothing running | Phase 2 | media MediaServerLadderTest |
| `remux.test.js` | cache: a file larger than CACHE_MB is still served; older files are evicted first | Phase 4 | (planned, cache cap) |
| `remux.test.js` | cache: a file that vanished after it was built is built again | Phase 4 | (planned, cache cap) |
| `remux.test.js` | download retries with a fresh extraction after a 403 | Phase 3 | (planned, format chain and download) |
| `remux.test.js` | download gives up after the configured retries | Phase 3 | (planned, format chain and download) |
| `remux.test.js` | cache survives a restart; half-written files are dropped; clear works | Phase 4 | (planned, cache cap) |
| `remux.test.js` | clearing the cache keeps a file that is being played | Phase 4 | (planned, cache cap) |
| `remux.test.js` | range parsing | Phase 2 | media MediaServerLadderTest |
| `renderer.test.js` | init handles embedded renderer and names the receiver | Phase 2 | renderer RendererTest |
| `renderer.test.js` | doPlay loads short URL with DIDL metadata and plays | Phase 2 | renderer RendererTest |
| `renderer.test.js` | picks the first format the renderer advertises (lossless ADTS before MP3) | Phase 3 | (planned, format chain and seek) |
| `renderer.test.js` | prefers lossless FLAC over MP3 when the renderer lists FLAC but no AAC | Phase 3 | (planned, format chain and seek) |
| `renderer.test.js` | FLAC: if the renderer rejects Seek, a new FLAC starting at the offset is sent | Phase 3 | (planned, format chain and seek) |
| `renderer.test.js` | MP3 only when the renderer advertises nothing better | Phase 3 | (planned, format chain and seek) |
| `renderer.test.js` | exclude filter skips renderer | Phase 3 | (planned, DISC-04) |
| `renderer.test.js` | renderer errors without UPnP fault body do not crash, and UTF-8 bodies have byte-accurate length | Phase 2 | renderer RendererTest (wire-level cases also in upnp SoapTest) |
| `renderer.test.js` | walks the format chain when the renderer never starts, and remembers the winner | Phase 3 | (planned, format chain and seek) |
| `renderer.test.js` | MP4: if the renderer rejects Seek, a new MP4 starting at the offset is sent | Phase 3 | (planned, format chain and seek) |
| `renderer.test.js` | seeking a non-seekable stream (mp3/adts) restarts it at the offset | Phase 3 | (planned, format chain and seek) |
| `renderer.test.js` | casting mid-video with a non-seekable format starts the stream at that position | Phase 3 | (planned, format chain and seek) |
| `renderer.test.js` | gapless: next track is prepared, queued with SetNextAVTransportURI, and adopted without reloading | Phase 4 | (planned, gapless) |
| `renderer.test.js` | gapless: yt-cast-receiver stopping the player before play() does not cut off or reload the track | Phase 4 | (planned, gapless) |
| `renderer.test.js` | gapless: if the queue turns out to be over, the track the renderer played on into is stopped | Phase 4 | (planned, gapless) |
| `renderer.test.js` | no gapless support: falls back cleanly and uses the prepared track at end of song | Phase 4 | (planned, gapless) |
| `renderer.test.js` | queue change replaces the prepared next track | Phase 4 | (planned, gapless) |
| `renderer.test.js` | a next track that cannot be prepared is not retried on every poll | Phase 4 | (planned, gapless) |
| `renderer.test.js` | a renderer removed while a track is loading stays stopped | Phase 2 | renderer RendererTest |
| `renderer.test.js` | per-renderer format override wins over the global setting and is persisted | v2 | (status page setting) |
| `renderer.test.js` | SponsorBlock: seeks past a segment the renderer is inside | v2 | (EXT-01 / UI-02) |
| `renderer.test.js` | SponsorBlock: a segment running to the end skips to the next track | v2 | (EXT-01 / UI-02) |
| `renderer.test.js` | SponsorBlock off: no lookups, no seeks | v2 | (EXT-01 / UI-02) |
| `renderer.test.js` | duration comes from the video, not a renderer still reporting the previous track | Phase 2 | renderer RendererTest |
| `renderer.test.js` | history: a track is counted once, after it has played long enough | v2 | (EXT-01 / UI-02) |
| `renderer.test.js` | history: quick skips and paused time do not count | v2 | (EXT-01 / UI-02) |
| `renderer.test.js` | history: seek restarts do not double-count, playing the track again does | v2 | (EXT-01 / UI-02) |
| `renderer.test.js` | history: gapless continuation counts both tracks | v2 | (EXT-01 / UI-02) |
| `sponsorblock.test.js` | queries by hash prefix only and keeps just this video's skip segments | v2 | (EXT-01) |
| `sponsorblock.test.js` | 404 means no segments; errors never break playback; off means no request | v2 | (EXT-01) |
| `sponsorblock.test.js` | status-page settings override .env, persist, and reset | v2 | (EXT-01) |
| `sponsorblock.test.js` | merge joins overlapping and adjacent segments | v2 | (EXT-01) |
| `statusServer.test.js` | serves the page with a strict CSP and the status API | out of scope | (no status web page) |
| `statusServer.test.js` | format changes require the CSRF header and same origin | out of scope | (no status web page) |
| `statusServer.test.js` | STATUS_TOKEN protects the API (header or query), not the page or health check | out of scope | (no status web page) |
| `statusServer.test.js` | logs: ring buffer and live stream | out of scope | (no status web page) |
| `statusServer.test.js` | SponsorBlock settings: read, change (validated, CSRF-protected), reset | out of scope | (no status web page) |
| `statusServer.test.js` | history: list, most played, CSV download, clear (CSRF-protected) | out of scope | (no status web page) |
| `statusServer.test.js` | history endpoints answer 404 when HISTORY=false | out of scope | (no status web page) |
| `unit.test.js` | SSDP parsing handles CRLF, case and missing headers | Phase 2 | upnp SsdpClientTest |
| `unit.test.js` | description parser finds embedded MediaRenderer and normalises serviceIds | Phase 2 | upnp UpnpTest |
| `unit.test.js` | DIDL escapes metadata | Phase 2 | upnp UpnpTest |
| `unit.test.js` | sink protocol matching | Phase 2 | upnp UpnpTest |
| `unit.test.js` | time helpers | Phase 2 | renderer TimeHelpersTest |
| `unit.test.js` | resolver rejects IDs that could be used for injection | Phase 2 | media CachingResolverTest |
| `unit.test.js` | yt-dlp info JSON is private and never written through a planted symlink | Phase 3 | (planned, download path) |

The `yt-dlp info JSON is private and never written through a planted symlink` test (unit.test.js) is skipped on Windows in Node; the Kotlin port runs it on the JVM and on the phone in Phase 3 together with the download path.

## (d) Adaptations

Deliberate differences from the Node code, each with its reason. Filled during the phase; plan 02-14 reviews it.

1. **FakeRenderer actions.** The Kotlin `FakeRenderer` adds `SetVolume` (stores `DesiredVolume` into `state.volume`), `SetMute` (stores `DesiredMute`, "1" or "true") and `GetMute` (answers `CurrentMute` 1 or 0). The Node fake only answers `GetVolume`/`SetVolume` with a fixed reply. Every other action still answers an empty `u:<Action>Response`. It also XML-escapes the URIs it echoes back in `GetPositionInfo` and `GetMediaInfo`, which the Node fake pastes raw.
2. **D-04 exception: receiver names.** Receivers are named `🔊 {name} (app)` instead of the Node `🔊 {name}` (CAST-07) while the Docker server runs, so the two do not collide in the cast menu. `DOCKER_COEXISTENCE_SUFFIX` is removed at parity.
3. **CAST-08 fixtures.** The lounge fixtures are recorded from the yt-cast-receiver 2.1.0 library harness (`tools/lounge-capture`, dependencies from the Node project's lockfile at 5bd3025), the receiver inside the Node server, instead of the Docker server's logs, which print parsed objects rather than raw frames.
4. **SOAP transport.** `SoapClient` posts a `ByteArray` body through OkHttp (byte-exact `Content-Length`), where Node sets the header by hand from `Buffer.length`. A per-call timeout comes from `Call.timeout()`; cancelling the coroutine cancels the call.
5. **Cleartext guard (SVC-05).** Node has no equivalent. `PrivateLanGuard` refuses plain HTTP to names and to non-private IPs, and `lanHttpClient()` disables redirects, because the manifest allows cleartext app-wide.
6. **DIDL text.** `buildDidl` strips code points outside the XML 1.0 Char production before escaping (RESEARCH Pitfall 10); `upnp.js` only escapes.

## (e) Licence notices

- **tube2upnp** (the Node project this app is ported from): GPL-3.0-only. This repository is a derivative work and is released under GPL-3.0-only.
- **yt-cast-receiver 2.1.0**: MIT, Copyright (c) Patrick Kan. The notice is kept in every `lounge/` file derived from it.
- **peer-dial**: LGPL-3.0-or-later, Copyright (c) 2015 Louay Bassbouss, Fraunhofer FOKUS. The header is kept in the DIAL files derived from it (`dial/`, `upnp/SsdpResponder.kt`).
