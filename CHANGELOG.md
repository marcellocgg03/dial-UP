# Changelog

## 1.3.0

### Added

- **Grouped Sonos speakers show as one cast target.** When speakers are grouped in the Sonos app, the group is listed once, on its lead speaker, under a name like "Lamp + Deck", and the other speakers leave the cast menu until the group is split. A change shows within about 15 seconds. The volume and mute from the phone apply to the whole group. YouTube and YouTube Music may keep showing the old name. Tested with JVM tests, and on a phone with two speakers: the group listing, a split, group volume and unmuting. Muting from the phone was not checked, because the YouTube app tested says it does not support mute.

### Fixed

- **A cast of a long track that starts partway in no longer fails with "Renderer fetched the stream but never started playing".** For a track larger than about 10 MiB, YouTube sends the audio slowly (about 30 KB/s) when it is asked for the whole file. A resume at 0:40 made ffmpeg read the first 650 KB through that slow response instead of asking YouTube for the part it needed, took about 21 s, and was ended by the 20 s start limit before the speaker got any audio. ffmpeg now asks for the position it needs directly. Tested with real ffmpeg against a deliberately slow server (the test fails without the change). On a Sonos One a seek to 0:43 of the failing track fetched the needed part in about 140 ms and played; a cast that starts at 0:40 was not run on the phone.
- **An internet outage during a cast no longer ends the cast at once.** A message to the phone that failed because the internet was down used to be tried once more and then ended the session. It is now sent again for up to two minutes, like the connection that listens to the phone, and the phone gets the current state when the internet is back. Tested with JVM tests that fail without the change.
- **A seek the speaker refuses no longer leaves the phone showing loading.** When the speaker said no to a seek, the phone stayed on its loading state. It now gets the real position and play state right away. Tested with JVM tests that fail without the change.
- **A failed seek or resume no longer stops the app from following the speaker.** After a seek or resume that failed to load, the app stopped checking the speaker. The track now still moves on at its end, and a stop on the speaker still reaches the phone. Tested with JVM tests that fail without the change.
- **After the phone's address changes, a track whose position the speaker cannot report goes on from where it was, not from 0:00.** Some speakers answer with an empty or unusable position right after the address change. The track is now set again at the last position the app saw, and a warning goes in the log. Tested with JVM tests that fail without the change.
- **A next track prepared before Stop or a network change is no longer sent with the phone's old address.** A track prepared in advance could keep the address it was built with. It is now used only for the address it was built with, and prepared again when the address is different. Tested with JVM tests that fail without the change.
- **After the phone reconnects, Previous and the autoplay entry no longer offer the old queue.** A reset queue kept its next video, and a late answer from the old queue could overwrite the new one. Both are cleared now. Tested with JVM tests that fail without the change. Checked on a phone with a Sonos Arc: after a reconnect the bridge offered no Previous, Next or old autoplay entry.
- **Quick pause, seek and next no longer leave the phone and the speaker disagreeing.** Reports of the player's state could be taken in one order and sent in another when a command from the phone arrived while the speaker was being polled. They now go out in the order they were taken. Tested with JVM tests that fail without the change. Checked on a phone with a Sonos Arc for a pause followed by Next; a burst with a seek in it was not tried.
- **The connection to the phone no longer goes quiet after an unexpected error.** Replies sent while the connection starts are now delivered, a message the app cannot read no longer stops it from hearing the phone, and a second failure no longer runs the shutdown twice. Tested with JVM tests that fail without the change.
- **The media server copes with an ffmpeg that cannot start and with many streams at once.** If ffmpeg cannot start, the next format is tried and the speaker gets a clear 502 instead of a 500. A long track no longer loses its audio feed after many other streams were started. Tested with JVM tests that fail without the change.
- **A crafted Range request no longer breaks a stream's response, and a damaged cache list can no longer delete files outside the cache.** A Range request whose end would overflow, or a read-ahead answer that ends too early, is now refused instead of crashing the response, and names in the cache list that are not plain cache file names are ignored. Tested with JVM tests that fail without the change. Checked on a phone: Range requests to a direct stream, including one ending at 9223372036854775807, were answered 206 and never 500.
- **Speaker search keeps working after a network error.** An unexpected error on the connection that hears the speakers' replies stopped the search for good while the app still thought it was running, so Refresh found nothing. It now waits a moment and listens again, and a connection that keeps failing is rebuilt by the regular check every 5 minutes. Tested with JVM tests that fail without the change.
- **Switching the bridge off and on, or a Wi-Fi change, no longer leaves a port taken.** A start or move that failed halfway could leave the cast or media port held by a connection that no longer existed, so the speaker could not come back on it. Everything a failed start or move opened is now closed again, and a speaker whose port could not be saved stays in the list. Tested with JVM tests that fail without the change. Checked on a phone with the VPN on and a software speaker: after ten switch taps and one more off, no cast or media port answered, and both answered after the next on; a failed move was not tried.
- **A bridge that cannot start or move to a new network now says so.** A start that failed with an error was tried again forever with no word to the user, and a failed move was not tried again at all. Both are now tried again up to five times, after which the bridge switches itself off, the Enable switch shows it and a notification says the bridge is not running. Tested with JVM tests that fail without the change. Checked on a phone with the VPN on and the media port held by another program: the start failed 5 times, the bridge switched itself off and posted the notification, and it started and removed the notification once the port was free; a failed move was not tried.
- **Share log, settings changes and the bridge switch no longer make the app stutter.** They wrote files, flushed the log or checked the network on the screen's own thread. That work now runs in the background in the order the changes were made, and a setting that lets more devices cast is still applied only after it was saved. The app also reads its update record in the background when it opens. Tested with JVM tests for the order of the settings writes, the log zip and the update record read; the move off the screen's thread itself can only be seen on a phone. Checked on a phone with a debug build: the app's StrictMode log named these paths in 36 lines before the changes and in none after them (the app open with the VPN off, the rest with the VPN on). Starting the bridge service still does about ten small file reads on the screen's thread, because its notification has to appear within 5 seconds.
- **Switching event logging off now also silences the yt-dlp updates made while the bridge is off.** The daily yt-dlp update and Update now with the bridge off wrote to the event log even when event logging was off. They now use the same setting as the bridge. Tested with JVM tests that fail without the change. Checked on a phone with the VPN on and the bridge off: with logging off, a forced daily job and Update now wrote nothing to the log; yt-dlp was already current, so no update ran.
- **A damaged settings file no longer turns on automatic yt-dlp updates in the F-Droid build.** A build made with the F-Droid option fell back to defaults with automatic updates on when its settings.json was not a valid settings object. It now falls back to that build's own default, which is off. Tested with JVM tests that fail without the change. Checked on a phone with the VPN on and a build made with that option: a settings file replaced with text that is not JSON came back as a settings object with the automatic update off.
- **Closing the app during a restart or a yt-dlp update no longer leaves the bridge switched off.** The switch was saved as off for the whole restart or update, so an app closed in the middle came back with the bridge off. The switch now stays on and the bridge is only held off while the work runs, and a second Update now waits for the first instead of failing. Tested with JVM tests that fail without the change. Checked on a phone with the VPN on: closing the app in the middle of a restart left the switch on and the bridge started at the next open; closing it during a yt-dlp update was not tried.
- **Only one yt-dlp update runs at a time, and a stuck one gives up after 5 minutes.** The daily update, the bridge's update and Update now could install at the same moment, and an update that never finished blocked every later one. A second install at the same moment is now refused and tried again later, and an update that takes more than 5 minutes counts as failed. Tested with JVM tests that fail without the change.
- **The Wi-Fi lock is no longer kept after the bridge stops right after a cast.** A background tick could take the Wi-Fi lock again after the bridge had released it, so a stop right after a cast could leave it held. The lock is now released only after the bridge's work has stopped, and cannot be taken again after that. Tested with JVM tests that fail without the change. Checked on a phone with the VPN off: a Stop from the notification during a cast released the Wi-Fi lock right after the service stopped and none was taken after it.
- **Closing Settings during Check now no longer breaks the other update check.** Two checks that shared one request failed together when the screen that started it closed. The check now finishes on its own and every waiting check gets its result. Tested with JVM tests that fail without the change.
- **Speakers no longer go missing for five minutes after a Wi-Fi change.** When the bridge started right after the phone joined a network, the system cut off the first connections to some speakers, and each of them was left out of the cast menu until the next five minute check or a Refresh. A start caused by a network change now waits 3 seconds before it searches, speakers that still failed are tried again 3 and 6 seconds after any start, and the log says why a speaker failed. Tested with JVM tests, and on a phone in six app restarts around switching Wi-Fi off and on: the Sonos speakers that failed at first came back within a few seconds. An LG TV on the same network can still fail in the first seconds, sometimes past both tries; the cause is not known.
- **Moving to another network removes the old network's speakers.** When the phone went straight from home Wi-Fi to another network, such as a car's wireless Android Auto link, the home speakers stayed listed and every command to them timed out. They are now removed and the search starts again on the new network. A move inside the same network works as before. Two networks with the same address range and netmask are still taken for one. Tested with JVM tests; not tried on a phone or in a car.
- **A Wi-Fi network without an IPv4 address no longer hides the other connections.** The bridge now uses the first Wi-Fi network that has one, and otherwise the phone's hotspot. Tested with JVM tests, and on a phone: with Wi-Fi off the bridge moved to the hotspot and back again. A Wi-Fi network without an IPv4 address was not tried on the phone.
- **A temporary network error no longer unpairs the phone from the speakers.** A timeout or a server error while a speaker's cast target started replaced its saved identity, so phones no longer recognized it. The identity is now replaced only when YouTube refuses it. Tested with JVM tests, and on a phone: cutting Wi-Fi during a start left every identity unchanged.
- **A bad cast request no longer interrupts the cast that is playing.** A launch with a missing or malformed pairing code, or one YouTube does not accept, now changes nothing on the speaker. Tested with JVM tests, and on a phone with a malformed code during a cast.
- **A paused cast that the idle limit could not end is now reset.** If ending it did nothing, because the cast target was not running or had no session left, the cast stayed counted as casting and the notification stayed up. This is a safeguard found by reading the code: the problem was not reproduced, and the change was tested with JVM tests only.
- **A cast stopped while it was still connecting no longer leaves the speaker unusable until the app restarts.** Switching the bridge off, or a Wi-Fi change, during the connect is now undone completely. Tested with JVM tests, and on a phone by switching the bridge off and Wi-Fi off and on around the start of a cast: the next cast worked each time. The log does not show that the connect itself was interrupted.
- **The "never started playing" error no longer blames the speaker when no audio was sent.** When the speaker asked for the stream and the media server had nothing to send, the error now says so. Tested with JVM tests.
- **Smaller fixes.** A phone set to a language with its own digits sends the track length to the speaker in ordinary digits. Two quick uses of Share log can no longer damage the zip. A bridge restart that fails after a rename or prefix change is now written to the log. Tested with JVM tests.

### Changed

- **The event log records more, for less time.** The default level is now debug, five rotated files are kept (about 48 MiB at most), and files older than 7 days are deleted. The log now shows how long stream lookups and downloads took and which speaker each message from YouTube belongs to. Tested with JVM tests.
- **Share log sends one zip** with every log file, oldest first, and a summary of the time each one covers. Tested with JVM tests.

### Security

- **Another device on the network can no longer remove a speaker.** A speaker's goodbye message now counts only when it comes from the speaker's own address. Tested with JVM tests, and on a phone with a forged message, which was refused while the speaker kept playing.
- **Devices on the network can no longer add speakers without limit or take over each other's saved data.** At most 256 speakers run and 256 ports are saved, an announced speaker lifetime is capped at one day, and speakers with similar or file-like names no longer share a storage folder or overwrite the saved speaker lists. Tested with JVM tests; installing over the previous version on a phone kept every real speaker's port and identity.
- **The app only fetches speaker descriptions and sends commands to private network addresses**, never to the phone itself, a host name or a public address, over HTTPS as well. Tested with JVM tests.
- **LAN server mode answers cast requests only from devices on the Wi-Fi network**, the same rule as discovery, and no longer puts the address a request names into its replies. Tested with JVM tests, and on a phone: a computer on the same network was served in LAN mode and refused in phone mode, and a forged host name did not appear in the reply.
- **Text from speakers is cleaned and shortened** before it is shown, logged or sent on, and a volume from a phone outside 0 to 100 is brought into that range. Tested with JVM tests, and on a phone with a software speaker that had a very long name.
- **Pairing codes are removed from the log** also when a request writes them in an unusual way, and errors from the player are logged without their text, which could hold a private link. Tested with JVM tests; the logs from a phone session held no private link.

## 1.2.2

### Fixed

- **A stream that goes quiet no longer leaves the speaker waiting until it gives up.** On a very long track the audio source could stop sending without closing the connection (seen after about 6 h 50 min of a 10 h video). ffmpeg then waited on it for hours, the speaker ran out of audio and stopped, and nothing in the app noticed. ffmpeg now fails a network read that is silent for 30 s so it can reconnect from where it was, and a live stream that gets no bytes from ffmpeg for 60 s is ended instead of held open. If the audio link has expired the track still ends; restarting it is not part of this fix.
- **A video that plays for more than about 6 hours no longer loses its audio link.** YouTube's audio links stop working after about 6 hours. The media server now sits between ffmpeg and YouTube: it looks up the next link in the background shortly before the current one expires and ends the response a little early, so ffmpeg reconnects at the byte where it stopped and continues on the new link. The speaker and the phone see one unbroken stream. A link is reused until it is close to its end, so a reconnect hours later does not wait for a new lookup. Tested against a local stand-in for YouTube and real ffmpeg, and on a 10 h video cast to a Sonos speaker, which played to the end of the track across the link replacement and one dropped connection (the log shows it; I did not listen for gaps). The old way of reading the link directly is kept as the fallback.

## 1.2.1

### Added

- **A first-launch question about yt-dlp.** The yt-dlp inside the app is old and YouTube refuses its streams, so the first cast of a new install fails until it is updated. A new install now asks once whether to update it. Update installs the newest yt-dlp at once; Not now installs nothing, and Update now in Settings does it later. The answer does not change the daily automatic update switch (on by default). It is asked once per install; an install that has already run is not asked.
- **An F-Droid build option** (`-Pfdroid=true`), (its store metadata was added in 1.2.1 and removed again from `main` afterwards, until the app is submitted to F-Droid). It holds the automatic updates off in that build. It has no effect on the APK from GitHub.

### Fixed

- **A paused cast whose phone is gone is now ended by the idle limit.** When the phone's YouTube app disappears without saying it left, the speaker keeps the cast (1.2.0), but the "Disconnect an idle cast" setting then did nothing for a cast paused in that state, because there was no sender to end it for. The speaker stayed loaded and the app's notification kept saying it was casting. The idle limit now resets the player in that case too, which empties the speaker when "Clear the renderer when a cast ends" is on.

## 1.2.0

### Added

- **Memory in the event log**, to see why Android kills the app (a process exit with reason LOW_MEMORY is its most common end on some phones). Every heartbeat now carries what the phone has left (`memAvailMb`, `memThresholdMb`, `memLow`) and what the app holds (`javaUsedMb`, `nativeMb`, `pssMb`). The system's requests to give memory back are logged as `trim-memory` events with their level, and each `exit-info` entry adds the memory the earlier process held (`rssMb`, and `pssMb` when the system recorded it) and its importance. Numbers only, nothing that identifies a track or a speaker.
- **Disconnect an idle cast** (Settings, Renderers: Off, 15 min, 30 min, 1 hour or 2 hours; off by default). A cast that stays paused that long is ended: the phone is told the screen is gone, as when a stream fails to start. The renderer's queue is emptied only when "Clear the renderer when a cast ends" is on. Playing, seeking, loading a track or stopping before the time is up cancels it, and a change applies from the next pause with no restart.
- **Pair manually with code, from a speaker's ⋮ menu.** For a network that blocks YouTube and YouTube Music from discovering renderers even with the bridge on. It shows a TV code for YouTube and one for YouTube Music, each with a Copy button. Type each under Link with TV code in its own app (a code typed into the other app links the speaker but the cast drops on connect). A new code is requested every five minutes while the dialog is open, or with New code. The codes are never logged or stored. The Add renderer by address prompt now points to it.
- **A Prefetch switch in Settings** ("Prepare the next track ahead", on by default). Turning it off looks the next track up only after the current one ends, so there is a noticeable pause. Gapless needs it and is greyed out and shown as off while it is off. Both need a bridge restart.

### Changed

- **The event log is quieter.** The replies the app sends to a search for a speaker (`ssdp-tx`, about two thirds of a typical log, because the YouTube apps search again and again) are logged once per address, search type and speaker per five minutes instead of every time. The searches are still answered every time. An `exit-info` entry now also has `at`, the time of the exit in the same form as the line's own `ts`.
- **Gapless track changes are off for new installs.** An install that has already run keeps its current setting. With it off the next track is still prepared ahead and starts about a second after the speaker reports the end.
- The "Notify when another device casts" switch is gone from Settings. The notice itself stays; to silence it, turn off the "Other devices" notification channel in Android's app notification settings. A phone that had the switch off will get the notice again until you do.
- The event log's lookup records now name the YouTube client that signed the URL, its itag and format id, whether it carries a PO token, and the IP family it is bound to, so a refused (403) stream can be told apart from a working one. The URL, the address and the token are never logged.

### Fixed

- **A cast no longer ends when the phone's YouTube app is killed.** When the phone's app disappears without saying it left (an Android System WebView or app update, or the system freeing memory), the speaker now keeps playing instead of being stopped and emptied; the phone picks the cast up again when its app returns. Pressing disconnect in the YouTube app still ends it. Before, an overnight update stopped a 10-hour track after four hours.
- **A slow recovery no longer reads as a speaker that cannot play the format.** When YouTube refused a stream's first URL and the app had to extract a fresh one (which takes about 15 s on a cold start), the speaker's wait timed out and the app stepped down from adts to mp3. The start wait now holds while the media server is recovering and restarts when the first audio arrives.
- **A stream YouTube refuses on every attempt ends the cast at once.** When every way of reading the stream gets a 403 (or 410), the app no longer tries the next format on the same refused source and waits out a second round; it ends the cast and says "YouTube refused the stream".
- **A track that has not started playing is shown as loading, not playing.** Until the speaker plays, the phone's seek bar stays at the start position instead of running ahead and jumping back; once the speaker plays, the phone is told its real position. The renderer's card in the app says Loading too, instead of Playing, until then.

## 1.1.1

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

- User-facing text says "renderer" instead of "speaker" in Settings and the dialogs.
- The Add renderer popup is shorter, and a typed address that is not a private LAN IPv4 address now says
  "That is not a valid IP address on this LAN".
- The log setting is titled "Event logging".
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
