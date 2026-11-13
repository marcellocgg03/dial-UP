# Changelog

## Unreleased

### Added

- **Nightly builds.** One rolling signed pre-release (tag `nightly`) is replaced every night when main has changed and CI is green. Choose
  Stable or Nightly under Settings > App updates; Stable never offers a nightly. A nightly installs over the stable app
  and the next stable release installs over it.
- **Notification when a DialUP update is available**, from a daily background check, once per release, on its own
  "App updates" channel. It follows the existing app-updates switch.
- **Switch for the daily yt-dlp update** next to the Stable/Nightly choice. It never runs during a cast.
- The debug build installs next to the release app (package `dev.dialup.debug`).

### Fixed

- The restart card in Settings now sits above the keyboard instead of under it.

### Changed

- The version code is now the stable release number times 1000 (plus the nightly number), so nightlies order between
  stable releases. It only ever goes up, so updates over 1.1.0 work as before.

## 1.1.0

### Added

- **Tap the bridge notification to open DialUP.** The notification stays ongoing; a tap does not dismiss it.
- **Name prefix** in Settings (the speaker emoji by default, empty allowed, at most 16 characters). It applies to the
  running speakers at once and a cast keeps playing. A speaker you renamed by hand keeps its name. The " (app)" suffix
  is gone.
- **Notice when another device starts a cast.** When another device connects to one of your speakers, DialUP posts a
  notification naming that device and the speaker (for example "COZYLA CD-8S544F2 started a cast on Lamp"). It has its
  own "Other devices" channel and a switch in Settings, on by default. Your own phone never triggers it.
- **Pin a speaker card.** A pinned card stays in the list across restarts; hiding or deleting a card unpins it.
- **Restart prompts for rename and prefix.** With nothing cast, a rename or a new prefix applies live and shows a note.
  With a cast running it offers Restart or Later. Android cannot clear YouTube and YouTube Music from recents, so the
  notes ask you to do that yourself to see the new name.

### Changed

- One **Refresh** button beside "Add renderer by address"; the name prefix field in Settings is compact.
- A stream failure now moves to the next format at once instead of after the 12 s start timeout, so audio no longer
  starts about 15 s late when the first format fails.
- A speaker taken over by another app (for example the Sonos app) now ends the cast on the phone as well, instead of
  YouTube Music still showing it as connected.
- Deleting a speaker you saved by address brings its card back at once when discovery also finds it.
- The log records which speaker a DIAL launch reached, and the speaker replies we send, at the default log level.

### Fixed

- A token refresh during a cast no longer drops the connected phone.
- Resetting the name prefix to its default now survives a restart.
- Messages queued behind a lounge refresh no longer go out with a stale id, and a cancelled retry no longer leaves its
  caller waiting.
- Starting a cast on a speaker can no longer leave its port held after a cancel, and a cancelled stream start can no
  longer leave an ffmpeg process running.

### Security

- Replies from speakers and other devices are size-limited, so a device on the network cannot exhaust the app's memory.
- In LAN server mode, discovery answers only go to devices on the Wi-Fi network.
- A speaker's control address must belong to the same device that announced it and may not point at the phone itself.
- DIAL refuses requests that carry a browser Origin.
- A known speaker's entry is no longer replaced from a different address while it is still fresh. A speaker that moves
  without saying goodbye is picked up again once its old entry expires, which can take up to about 45 minutes.
- ffmpeg only reads Google video addresses over https and runs with a protocol whitelist, and the number of ffmpeg
  processes is limited, in total and per stream.
- Speaker names and the prefix are cleaned of control characters; stream addresses are shortened in the log; a failed
  settings write can no longer loosen LAN server mode after a restart; settings and speaker files are flushed to disk
  before they replace the old file.

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
