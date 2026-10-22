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

Status values: `planned` (later phase), `not ported`, `out of scope`.

| Node file | Kotlin file(s) | Status |
|-----------|----------------|--------|
| `src/config.js` | `renderer/Config.kt`, `renderer/ConfigFile.kt` (tolerant `config.json` parser), `app/ConfigLoader.kt` | ported (`config.json` replaces environment variables, SVC-07) |
| `src/dataStore.js` | `renderer/DataStore.kt` | ported |
| `src/formats.js` | `media/Formats.kt`, plus `MIME_ALIASES` in `upnp/Upnp.kt` (`:upnp` does not depend on `:media`, and `sinkAccepts` needs the aliases) | ported |
| `src/history.js` | none | not ported (v2 UI-02) |
| `src/index.js` | `renderer/RendererRegistry.kt`, `renderer/Bridge.kt` (wires the InnerTube handler, the file builder and the downloader), `app/CastFeature.kt` | ported |
| `src/logger.js` | `upnp/EventSink.kt`, `app/EvidenceLog.kt` | ported |
| `src/mediaServer.js` | `media/MediaServer.kt`, `media/RangeParser.kt`, `media/PipeLauncher.kt`, `media/UpstreamFetcher.kt`, `media/FileBuilds.kt` | ported (the CACHE_MB cache is Phase 4) |
| `src/net.js` | `renderer/Net.kt` | ported |
| `src/ports.js` | `renderer/Ports.kt` | ported |
| `src/renderer.js` | `renderer/Renderer.kt`, `renderer/FormatChain.kt`, `renderer/OkHttpUpstreamFetcher.kt` | partial (format chain, learned format, seek fallbacks and name filter ported in Phase 3; gapless planned for Phase 4) |
| `src/resolver.js` | `media/Resolver.kt`, `media/YtDlpSourceDownloader.kt` | ported |
| `src/settings.js` | none | not ported (status-page settings, v2 UI-01) |
| `src/soap.js` | `upnp/Soap.kt` | ported in plan 02-03 |
| `src/sponsorblock.js` | none | not ported (v2 EXT-01) |
| `src/ssdp.js` | `upnp/Ssdp.kt`, `upnp/SsdpMessage.kt` | ported |
| `src/stats.js` | none | not ported (v2 UI-03) |
| `src/statusServer.js`, `src/ui/` | none | out of scope (no status web page) |
| `src/upnp.js` | `upnp/Upnp.kt` | ported in plan 02-03 |
| `test/fakeRenderer.js` | `upnp/src/testFixtures/.../FakeRenderer.kt` | ported in plan 02-03 |

Libraries:

| Library file | Kotlin file(s) | Status |
|--------------|----------------|--------|
| yt-cast-receiver 2.1.0 (MIT): `lib/app/*.js`, `lib/Player.js`, `lib/YouTubeCastReceiver.js`, `lib/Constants.js` | `lounge/*.kt`; planned names: `Constants`, `Player`, `Playlist`, `PlaylistRequestHandler`, `Sender`, `CastReceiver`, `BindParams`, `ChunkDecoder` (replaces `Message.parseIncoming`), `Message`, `Session`, `LoungeLog`, `YouTubeApp`, `YouTubeCastReceiver` | ported |
| yt-cast-receiver `lib/app/DefaultPlaylistRequestHandler.js` (MIT) plus the youtubei.js `/next` protocol | `lounge/InnerTubePlaylistRequestHandler.kt`, `lounge/VisitorData.kt` | ported (autoplay and previous/next; `markWatched` stays deferred) |
| yt-cast-receiver `lib/utils/DataStore.js` | `lounge/DataStore.kt` (interface contract, written in plan 02-03) | ported in plan 02-03 |
| yt-cast-receiver `lib/app/PairingCodeRequestService.js` | none | not ported (v2 PAIR-01) |
| peer-dial (LGPL-3.0-or-later) | `dial/DialServer.kt`, `dial/DialXml.kt`, `upnp/SsdpResponder.kt`, `upnp/SsdpMessage.kt` | ported |

## (c) Node tests

Every test title in `reference/tube2upnp/test`, where it is ported and which Kotlin class holds it. The Kotlin test keeps the Node title through `@DisplayName("<Node title>")` (PAR-02). The "Status" column is the phase that ports the test: `Phase 2`, `Phase 3`, `Phase 4`, `v2` or `out of scope`. `test/fakeRenderer.js` is a helper, not a test; it is the `FakeRenderer.kt` test fixture listed above.

| Node test file | Test title | Status | Kotlin test class |
|----------------|------------|--------|-------------------|
| `history.test.js` | counts plays per track and per speaker, newest plays first | v2 | (UI-02) |
| `history.test.js` | the list is capped but counts are kept | v2 | (UI-02) |
| `history.test.js` | persists to DATA_DIR/history.json and tolerates a corrupt file | v2 | (UI-02) |
| `history.test.js` | CSV: quoted cells, no spreadsheet formulas, speaker filter | v2 | (UI-02) |
| `history.test.js` | clear() empties everything on disk too | v2 | (UI-02) |
| `mediaServer.test.js` | proxies with Range, DLNA headers, and re-resolves on 403 | Phase 3 | renderer DirectProxyTest |
| `ports.test.js` | hands out consecutive ports and reuses a released one | Phase 2 | renderer PortsTest |
| `ports.test.js` | a speaker keeps its port across restarts whatever the discovery order | Phase 2 | renderer PortsTest |
| `ports.test.js` | a new device never takes the port remembered for an offline one | Phase 2 | renderer PortsTest |
| `ports.test.js` | devices that never came up (filtered, failed) are not remembered | Phase 2 | renderer PortsTest |
| `ports.test.js` | tolerates a corrupt ports.json | Phase 2 | renderer PortsTest |
| `ports.test.js` | ignores a ports.json of the wrong shape, and entries that are not ports | Phase 2 | renderer PortsTest |
| `ports.test.js` | speakers coming up at the same time are all saved without write errors | Phase 2 | renderer PortsTest |
| `remux.test.js` | mp4: lossless remux to a non-fragmented, faststart MP4 with byte ranges | Phase 3 | media MediaServerRemuxTest |
| `remux.test.js` | mp4 at offset (renderer cannot Seek) reuses the downloaded source | Phase 3 | media MediaServerRemuxTest |
| `remux.test.js` | adts: lossless AAC stream, starts at offset | Phase 2 | media MediaServerLadderTest |
| `remux.test.js` | flac (${bits}-bit): lossless file with byte ranges and correct length | Phase 3 | media MediaServerRemuxTest |
| `remux.test.js` | flac at offset (renderer cannot Seek) | Phase 3 | media MediaServerRemuxTest |
| `remux.test.js` | mp3 transcode | Phase 2 | media MediaServerLadderTest |
| `remux.test.js` | adts/mp3: a refused YouTube URL is retried with a fresh extraction | Phase 2 | media MediaServerLadderTest |
| `remux.test.js` | adts/mp3: falls back to the yt-dlp download when YouTube keeps refusing ffmpeg | Phase 3 | media MediaServerDownloadRungTest |
| `remux.test.js` | adts/mp3: a stream that cannot be read at all is a 502, not an empty 200 | Phase 2 | media MediaServerLadderTest |
| `remux.test.js` | a renderer that hangs up while the stream is being prepared leaves nothing running | Phase 2 | media MediaServerLadderTest |
| `remux.test.js` | cache: a file larger than CACHE_MB is still served; older files are evicted first | Phase 4 | (planned, cache cap) |
| `remux.test.js` | cache: a file that vanished after it was built is built again | Phase 4 | (planned, cache cap) |
| `remux.test.js` | download retries with a fresh extraction after a 403 | Phase 3 | media MediaServerRemuxTest |
| `remux.test.js` | download gives up after the configured retries | Phase 3 | media MediaServerRemuxTest |
| `remux.test.js` | cache survives a restart; half-written files are dropped; clear works | Phase 4 | (planned, cache cap) |
| `remux.test.js` | clearing the cache keeps a file that is being played | Phase 4 | (planned, cache cap) |
| `remux.test.js` | range parsing | Phase 2 | media MediaServerLadderTest |
| `renderer.test.js` | init handles embedded renderer and names the receiver | Phase 2 | renderer RendererTest |
| `renderer.test.js` | doPlay loads short URL with DIDL metadata and plays | Phase 2 | renderer RendererTest |
| `renderer.test.js` | picks the first format the renderer advertises (lossless ADTS before MP3) | Phase 3 | renderer FormatChainTest |
| `renderer.test.js` | prefers lossless FLAC over MP3 when the renderer lists FLAC but no AAC | Phase 3 | renderer FormatChainTest |
| `renderer.test.js` | FLAC: if the renderer rejects Seek, a new FLAC starting at the offset is sent | Phase 3 | renderer SeekFallbackTest |
| `renderer.test.js` | MP3 only when the renderer advertises nothing better | Phase 3 | renderer FormatChainTest |
| `renderer.test.js` | exclude filter skips renderer | Phase 3 | renderer FormatChainTest |
| `renderer.test.js` | renderer errors without UPnP fault body do not crash, and UTF-8 bodies have byte-accurate length | Phase 2 | renderer RendererTest (wire-level cases also in upnp SoapTest) |
| `renderer.test.js` | walks the format chain when the renderer never starts, and remembers the winner | Phase 3 | renderer FormatChainTest |
| `renderer.test.js` | MP4: if the renderer rejects Seek, a new MP4 starting at the offset is sent | Phase 3 | renderer SeekFallbackTest |
| `renderer.test.js` | seeking a non-seekable stream (mp3/adts) restarts it at the offset | Phase 3 | renderer SeekFallbackTest |
| `renderer.test.js` | casting mid-video with a non-seekable format starts the stream at that position | Phase 3 | renderer SeekFallbackTest |
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
| `unit.test.js` | yt-dlp info JSON is private and never written through a planted symlink | Phase 3 | media YtDlpSourceDownloaderTest |

The `yt-dlp info JSON is private and never written through a planted symlink` test (unit.test.js) is skipped on Windows in Node; the Kotlin port runs it on the JVM (`YtDlpSourceDownloaderTest`), and the phone run is recorded in 03-DEVICE-RESULTS.md.

## (d) Adaptations

Deliberate differences from the Node code, each with its reason. Filled during the phase; plan 02-14 reviews it.

1. **FakeRenderer actions.** The Kotlin `FakeRenderer` adds `SetVolume` (stores `DesiredVolume` into `state.volume`), `SetMute` (stores `DesiredMute`, "1" or "true") and `GetMute` (answers `CurrentMute` 1 or 0). The Node fake only answers `GetVolume`/`SetVolume` with a fixed reply. Every other action still answers an empty `u:<Action>Response`. It also XML-escapes the URIs it echoes back in `GetPositionInfo` and `GetMediaInfo`, which the Node fake pastes raw.
2. **D-04 exception: receiver names.** Receivers are named `🔊 {name} (app)` instead of the Node `🔊 {name}` (CAST-07) while the Docker server runs, so the two do not collide in the cast menu. `DOCKER_COEXISTENCE_SUFFIX` is removed at parity.
3. **CAST-08 fixtures.** The lounge fixtures are recorded from the yt-cast-receiver 2.1.0 library harness (`tools/lounge-capture`, dependencies from the Node project's lockfile at 5bd3025), the receiver inside the Node server, instead of the Docker server's logs, which print parsed objects rather than raw frames.
4. **SOAP transport.** `SoapClient` posts a `ByteArray` body through OkHttp (byte-exact `Content-Length`), where Node sets the header by hand from `Buffer.length`. A per-call timeout comes from `Call.timeout()`; cancelling the coroutine cancels the call.
5. **Cleartext guard (SVC-05).** Node has no equivalent. `PrivateLanGuard` refuses plain HTTP to names and to non-private IPs, and `lanHttpClient()` disables redirects, because the manifest allows cleartext app-wide.
6. **DIDL text.** `buildDidl` strips code points outside the XML 1.0 Char production before escaping (RESEARCH Pitfall 10); `upnp.js` only escapes.
7. **Paused seek stays paused.** `Player.seek` on a paused player leaves it paused; yt-cast-receiver resumes it.
8. **Restart-seek while paused.** A restart-seek on a paused player loads the stream without Play and sends Play at resume; Node's `#load` always sends Play.
9. **Pause waits for a starting speaker.** Pause waits, bounded, for a speaker that is still starting; Node sends it at once.
10. **Speaker metadata evidence.** `FakeRenderer` answers `TrackMetaData` and the renderer logs the speaker's metadata as lengths. Kotlin-only evidence, no Node counterpart.
11. **Range on live video pipes.** Live video pipe tokens (adts, mp3) answer ranged GETs from the bytes of the first run: 206 with the true total, a bounded cache (32 MiB per token, 64 MiB total) and a bounded cold fill (8 s). Node's `#pipe` ignores Range and answers 200 from byte 0, which made the Lamp hang up after an early Pause. The switch `PIPE_RANGE_ANSWERING_DEFAULT` ended true (Kitchen Amp retest, forced adts, 2026-10-21). The start clock also restarts at a Play that follows a Pause when the speaker was never seen PLAYING; Node measures from the load only.
12. **Next availability counts the autoplay video.** `Player.getNavInfo().hasNext` is true when the only next item is the autoplay video; yt-cast-receiver counts queue items only. Reason: with the autoplay mix on, the phone's Next button must work on the last queue item (CAST-05).
13. **A stop seen mid-track is mirrored, not advanced.** When the speaker reports STOPPED after PLAYING, the renderer treats it as a track end only when the length is unknown, no position was polled, the position never left the start, or the last position is within `TRACK_END_TOLERANCE_S` (10 s) of the end. Otherwise the stop is mirrored to the phone as STOPPED and the queue does not move. Node advances on every stop. Reason: a Stop pressed in the Sonos app must not skip the queue on.
14. **`config.json` replaces environment variables (SVC-07, D-04).** The tolerant parser keeps the defaults for anything missing or wrong and reports unknown keys in one warning. `outputFormat` defaults to `auto`, as the Node `OUTPUT_FORMAT` does, and an invalid value falls back to the default with a warning instead of failing the start as Node does. The naming keys (`nameSuffix`, `nameTemplate`) are not read, so the `(app)` suffix of adaptation 2 cannot be changed from the file.
15. **Direct proxy policy.** The relay of a `direct` token (`OkHttpUpstreamFetcher`) only talks to https googlevideo hosts, follows one redirect hop and re-checks the target against the policy, and forwards only a plain `bytes=` Range header of at most 64 characters. Node's `#proxy` copies the speaker's Range header unchecked and talks to whatever URL the resolver returned. Reason: the media server is reachable by any device on the LAN, and the cleartext guard of adaptation 5 must hold.
16. **The speaker is cleared on disconnect and service Stop (CAST-06).** The renderer sends an empty transport URI when the sender disconnects and when the service stops, but only if `GetMediaInfo` shows the URI is ours. Node has no equivalent. Reason: a speaker should not keep showing, or replaying, a stream whose server is gone.
17. **A recently-asked guard stands in for `markWatched`.** The InnerTube handler drops an autoplay candidate it was asked about in its last 8 calls when the queue is at its last item, which ends the A to B to A Mix ping-pong. `markWatched` (the `/player` stats ping) is not ported and stays deferred.
18. **InnerTube fixtures are hand-trimmed.** The six `/next` fixtures are cut by hand from the response shapes recorded in RESEARCH, not captured from a live session. The real-device check is recorded in 03-DEVICE-RESULTS.md.
19. **Interim cleanup of built files.** `FileBuilder` keeps the newest six finished files (in completion order) and wipes the build directory at start and stop. It stands in for the `CACHE_MB` cache (size cap, pins, rebuild) until Phase 4 (PLAY-10).
20. **Restart offsets and native Seek on offset files.** A restart offset is clamped to one second before the end when the length is known (Node has no clamp). A native Seek on a file built at an offset is relative to that file, and a target before the file's start reloads at the target; Node sends the absolute target.
21. **A track end waits for a playlist refresh in flight.** The renderer waits, bounded by `queueSettleTimeoutMs`, for a refresh before it calls next, and ends STOPPED when nothing advanced. Reason: otherwise a track ending during a refresh stops the queue.
22. **The yt-dlp info JSON goes through a private file, and a failed download is retried with a fresh extraction.** `YtDlpSourceDownloader` writes the earlier lookup's info JSON to a new `rw-------` file after removing anything planted at the path, and passes it with `--load-info-json` and no URL. `FileBuilder` retries a failed download after invalidating the cached resolve, so each attempt extracts afresh (the Node `download retries` rule).

## (e) Licence notices

- **tube2upnp** (the Node project this app is ported from): GPL-3.0-only. This repository is a derivative work and is released under GPL-3.0-only.
- **yt-cast-receiver 2.1.0**: MIT, Copyright (c) Patrick Kan. The notice is kept in every `lounge/` file derived from it.
- **peer-dial**: LGPL-3.0-or-later, Copyright (c) 2015 Louay Bassbouss, Fraunhofer FOKUS. The header is kept in the DIAL files derived from it (`dial/`, `upnp/SsdpResponder.kt`).
