# Changelog

All notable changes to DialUP are recorded in this file.

The format follows [Keep a Changelog 1.1.0](https://keepachangelog.com/en/1.1.0/), and the version numbers follow
[Semantic Versioning](https://semver.org/spec/v2.0.0.html). A version with a hyphen, such as `0.9.0-beta.1`, is a
pre-release.

## [Unreleased]

## [0.9.0-beta.1] - 2026-11-03

First public beta of DialUP, the Android port of Tube2U[PnP]. It was built and tried by its author on one phone and a few
Sonos speakers, so expect rough edges.

### Added

- The phone finds UPnP/DLNA speakers (Sonos Amp, Arc, SYMFONISK and similar) on the Wi-Fi network and shows each one as a
  card in the app.
- Every speaker appears as a cast target in the YouTube and YouTube Music apps on the same phone. Playlists, pause, next
  and previous work from the YouTube app.
- Playing happens through the phone with no server: it looks up the audio with yt-dlp, converts it with ffmpeg when the
  speaker needs another format, and serves it to the speaker.
- Audio formats are chosen per speaker (direct, MP4, AAC stream, FLAC, MP3), with a setting to force one, and tracks
  change without a gap on speakers that support it.
- Add a speaker by its IPv4 address when discovery cannot see it, rename a speaker, hide it from the cast menus and
  restore it later, and delete a speaker that was added by address.
- Each card shows the cover art, title and artist, the play time, and buttons for play/pause, previous, next, stop and clear.
- A status notification with a Stop button, the bridge starting again after a phone restart, and banners that ask for the
  notification and battery permissions.
- Settings: access from other devices on the network (off by default), clear the speaker when a cast ends, gapless track
  changes, audio cache size and Clear cache, forced audio format, theme, and the event log with Share log and Clear log.
- yt-dlp is kept up to date in the background, with an Update now button and a Stable or Nightly channel.

### Changed

- The release build contains no developer or debug tools: the adb command receiver, the test lookups and the sample data
  of the development builds are left out.
- The event log is kept in the app's private storage, a single writer owns it, and an old copy in shared storage is
  removed when the bridge starts.
- Web addresses are removed from the ffmpeg lines written to the log.
- A network socket whose setup fails is closed at once, so a failed start no longer keeps the discovery port busy.
- A yt-dlp lookup or download that hangs now times out and is reported as a failure instead of waiting forever.
- After a Wi-Fi change the bridge retries its media server and brings back a missing media server or cast target by itself.
- A speaker that was still starting up when the bridge first looked is found the next time it announces itself.
- A failed start can no longer show the bridge as running, and the status file is replaced atomically.

### Known limits

- The phone and the speakers must be on the same Wi-Fi network; casting with a VPN on has not been tried.
- Only arm64 phones with Android 8.0 or newer are supported.
- The app is sideloaded; it is not on Google Play.
- yt-dlp can stop working when YouTube changes something, until it is updated.
- The YouTube cast protocol is undocumented and can change without notice.

### Not verified yet

- Stopping the bridge from the notification, reopening the app afterwards, and swiping the app out of the recent apps.
- The card of a speaker while a track plays on a real speaker: cover art, title, artist, the moving time bar and every button.
- Hiding a speaker and restoring it, as seen in both the YouTube and the YouTube Music cast menus.
- The ask-first dialogs when the bridge is switched off or restarted during a cast, and reopening the app mid-cast.
- Deleting a speaker that was added by address while discovery also finds it, whose card is expected to stay away for up to a few minutes.
- A speaker that is unplugged for a few minutes going offline in the app and the cast menus, and coming back.
- Restarting the bridge three times in a row while a speaker is not answering.
- The card buttons on a real Sonos, including a double tap on next, and the clear-when-cast-ends setting.
- Renaming a speaker in both cast menus without a restart, and changing a speaker's address.
- Cover art on the Sonos for tracks that have no album.

[Unreleased]: https://github.com/marcellocgg03/dial-UP/compare/v0.9.0-beta.1...HEAD
[0.9.0-beta.1]: https://github.com/marcellocgg03/dial-UP/releases/tag/v0.9.0-beta.1
