# Changelog

## 1.0.0

First stable release. It builds on 0.9.0-beta.2.

### Added

- **Playback modes**: Streaming (default), Direct and Cached replace Automatic. Existing Automatic selections move to
  Streaming. The README compares the three modes.
- **Refresh** button beside "Add renderer by address": it clears the list and searches for renderers again. While a cast
  is running it asks first, because the cast is disconnected.
- **Confirmation before hiding a casting renderer**: hiding a playing, paused or loading renderer ends its cast, so the
  card asks first.
- **yt-dlp update while casting**: Update now checks GitHub first. If a newer version exists and a cast is running, it asks,
  then stops the bridge, updates and restarts it.
- An optional line on each renderer's card showing the audio format in use.

### Changed

- Settings are grouped into Playback, Renderers, Maintenance, Appearance and Diagnostics.
- Hidden renderers moved to their own screen, opened from Settings.
- "LAN access" is now called "LAN server mode".
- The cache keeps only renderer-ready files. Downloaded sources are temporary and removed when nothing uses them; a track
  seeked to an offset or rebuilt after its file was lost is downloaded again.

### Fixed

- Cast volume controls are steadier: a stale target in the opposite direction of the button pressed is no longer applied.
- A format chosen only because playback started at an offset or after a seek is no longer remembered as the renderer's
  format, so later tracks aren't stuck on MP3 encoding (this caused high CPU overnight).
- Refresh no longer empties the list when it cannot search, for example while the network is down.
- Switching yt-dlp from nightly to stable now offers the stable build, even though its version number is older.
- The yt-dlp version shows at launch without waiting for the bridge, and an update waits correctly for the bridge to stop.

## 0.9.0-beta.2

See the [release notes](https://github.com/marcellocgg03/dial-UP/releases/tag/v0.9.0-beta.2).
