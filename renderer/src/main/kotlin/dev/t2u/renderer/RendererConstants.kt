package dev.t2u.renderer

// Timing and service constants of Renderer, GPL-3.0-only.

import dev.t2u.lounge.PlayerStatus

internal const val AV_TRANSPORT = "AVTransport"
internal const val RENDERING_CONTROL = "RenderingControl"
internal const val CONNECTION_MANAGER = "ConnectionManager"
internal const val SERVICE_ID_PREFIX = "urn:upnp-org:serviceId:"

/** How long a track-start waits for the speaker to report PLAYING before the seek-after-play (renderer.js 8000). */
internal const val SEEK_AFTER_PLAY_WAIT_MS = 8000L
internal const val TRANSPORT_WAIT_STEP_MS = 300L

/** Step of the wait for a playlist refresh in flight at a track end. */
internal const val QUEUE_SETTLE_STEP_MS = 100L

/** How long a skip waits for the player to report the failed track STOPPED, and the step of that wait. */
internal const val SKIP_STOPPED_WAIT_MS = 2000L
internal const val SKIP_STEP_MS = 50L

/** Step of the wait before a retry of a track that could not be prepared, so a newer play ends it quickly. */
internal const val RETRY_WAIT_STEP_MS = 20L

/** How long a play whose retry wait was overtaken waits for the newer play to settle before it reports. */
internal const val SUPERSEDED_SETTLE_MS = 10_000L

/**
 * Upper bound on how long a Pause waits for a speaker that is still starting. A Pause that lands while the
 * speaker is fetching its first bytes makes it drop that connection and refetch the stream at the next Play
 * (Lamp session B, 19:47:43), so the Pause is held back until the speaker reports PLAYING.
 */
internal const val PAUSE_START_WAIT_MS = 3000L

/** How long after a cast receiver stopped itself the first new one is started; later attempts follow every 5 minutes. */
const val REVIVE_FIRST_DELAY_MS = 10_000L

/**
 * Upper bound on one speaker clear: the Stop, the emptied SetAVTransportURI and the read of the
 * current URI together. A speaker that does not answer must not hold up the lounge handler or the service
 * Stop (whose join is 10 s), and speakers are cleared in parallel, so the worst case is one budget.
 */
const val CLEAR_BUDGET_MS = 4000L

/** How long a clear waits for a SetAVTransportURI that a load has in flight, so the URI cannot land after the empty one. */
const val CLEAR_INFLIGHT_WAIT_MS = CLEAR_BUDGET_MS / 2

/** How long, after a clear gave up on a slow speaker, the renderer keeps reading the speaker for a late-landing stream. */
const val SLOW_CLEAR_WATCH_MS = 15_000L

/** The wait between two reads of that watch. */
const val SLOW_CLEAR_WATCH_INTERVAL_MS = 1_500L

/**
 * How close to the end of the track (seconds) the last polled position must be for a stop seen on the speaker
 * to count as the track ending. The poll interval is 2 s by default, so this covers one or two missed polls.
 */
const val TRACK_END_TOLERANCE_S = 10.0

/**
 * How long after a native Seek the position told to the sender is held at the seek target while the speaker has not
 * settled. A Sonos can spend 5 to 10 s TRANSITIONING after a Seek and reports a RelTime of 0-1 s meanwhile (device
 * session C, 2026-10-21); past this the speaker's own position is used again, so a speaker that never settles cannot
 * pin the seekbar.
 */
internal const val SEEK_SETTLE_DEADLINE_MS = 15_000L

/**
 * The seekbar of a sender counts on from the last position it was sent. When the speaker settles and its position
 * differs from where that count has got to by more than this (seconds), the sender is sent the speaker's position.
 */
internal const val SEEK_CORRECTION_TOLERANCE_S = 3.0

/**
 * The shortest time between two corrections of the sender's seekbar (drift or settled Seek): a speaker with a
 * jittery RelTime cannot flood the phone with positions. Measured on the injected clock.
 */
internal const val DRIFT_MIN_GAP_MS = 10_000L

/**
 * How many consecutive polls must show the speaker more than [SEEK_CORRECTION_TOLERANCE_S] away from the sender's seekbar
 * before it is corrected: one odd reading (a stale answer, a buffering stall) is not acted on.
 */
internal const val DRIFT_CONFIRM_POLLS = 2

/**
 * Upper bound on the wait for a speaker's first PLAYING, as a multiple of the start timeout, counted from the load (or
 * the first Play of a stream loaded while paused). A Seek sent before the speaker has played restarts the start timeout
 * clock (a Sonos can spend 13 s seeking near the end of a file, Lamp 2026-10-21 22:01), so this cap is what keeps a speaker
 * that is dead but still takes Seeks from being waited for forever.
 */
internal const val START_HARD_CAP_FACTOR = 4

/** Consecutive polls that must read another source's URI before the speaker counts as taken over. */
internal const val FOREIGN_POLLS_FOR_TAKEOVER = 2

/** How many earlier own addresses a renderer still counts as its own after the phone moved. */
internal const val OWN_HOSTS_KEPT = 4

/** How long the re-set after an address change waits for a load that was in flight, and the step of that wait. */
internal const val NETWORK_RESET_WAIT_MS = 20_000L
internal const val NETWORK_RESET_STEP_MS = 100L

/**
 * Polls a STOPPED reading at the end of a track is waited out while the speaker already holds the queued stream, before the
 * queue advances the normal way: a Sonos may report STOPPED for a moment as it switches.
 */
internal const val GAPLESS_STOP_GRACE_POLLS = 3

/** Transport states in which a speaker's RelTime can be believed again after a Seek. */
internal val SEEK_SETTLED_STATES = setOf("PLAYING", "PAUSED_PLAYBACK", "STOPPED", "NO_MEDIA_PRESENT")

/** The first wait before a next track that failed to prepare is tried again; it doubles per failure (renderer.js PREFETCH_RETRY_MS). */
internal const val PREFETCH_RETRY_MS = 30_000L

/** The longest that wait grows to (renderer.js PREFETCH_RETRY_MAX_MS). */
internal const val PREFETCH_RETRY_MAX_MS = 600_000L

/** How many video ids the prefetch remembers a failure for; the oldest are dropped first. */
internal const val PREFETCH_FAILURES_KEPT = 50

/** The player states in which an address change re-sets the loaded track; a stopped or idle player gets nothing. */
internal val RESET_STATUSES = setOf(PlayerStatus.PLAYING, PlayerStatus.PAUSED, PlayerStatus.LOADING)

internal val HTTP_URL =Regex("^https?:", RegexOption.IGNORE_CASE)
