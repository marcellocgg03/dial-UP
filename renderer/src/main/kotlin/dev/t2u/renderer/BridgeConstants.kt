package dev.t2u.renderer

// Timing and sizing constants of Bridge, and the UDN form it keys speakers by, GPL-3.0-only.

/** One denial per source per minute in the log, so a scanner on the LAN cannot flood it. */
internal const val DROP_LOG_WINDOW_MS = 60_000L

/**
 * How often the bridge re-announces (LAN mode), probes its own SSDP listener and searches for speakers. Five minutes
 * is six times inside the advertised max-age of 1800 s (SsdpMessage CACHE-CONTROL); there is no jitter because one host
 * sends it.
 */
const val HOUSEKEEPING_INTERVAL_MS = 300_000L

/** SSDP M-SEARCH advertises MX=3, so speakers may defer their replies for up to three seconds. */
const val SSDP_SEARCH_REPLY_WINDOW_MS = 3_000L

/**
 * The waits between the media-server rebind attempts after a Wi-Fi move. The old Wi-Fi address can still be held for a
 * second or two (TIME_WAIT, a socket the system has not released), so a single failed bind is retried before it is given up;
 * a server that is still down after the last wait is rebound by the next housekeeping pass.
 */
val MEDIA_REBIND_BACKOFF_MS: List<Long> = listOf(1_000L, 2_000L, 4_000L)

/** How many sources the per-address reply record holds; the oldest goes when a new source arrives at the cap. */
internal const val MANUAL_REPLY_CAP = 64

/** How often [Bridge.addManual] looks for the answer. */
internal const val MANUAL_POLL_MS = 100L

/** The shortest time the description fetch of an add is given, whatever is left of the probe budget. */
internal const val MANUAL_DESCRIBE_MIN_MS = 1_000L

/** The timeout of one saved-speaker liveness fetch. */
internal const val MANUAL_LIVENESS_FETCH_MS = 3_000L

/** A receiver younger than this is kept in the move list even while it is still "stopped" (its start has not run yet). */
internal const val TRACK_GRACE_MS = 120_000L

/** How long a housekeeping run waits for its own listener to hear the probe datagram. */
internal const val PROBE_TIMEOUT_MS = 2000L

/**
 * A device description's UDN (`uuid:RINCON_..._MR` on a real Sonos, upper case) in the form `udnFromUsn` gives a USN's UDN,
 * which is the key the registry, replySources, hidden.json and manual.json all use: the `uuid:` prefix removed in any letter
 * case, trimmed and lower-cased.
 */
internal fun descriptionUdn(raw: String): String {
    val trimmed = raw.trim()
    val bare = if (trimmed.startsWith("uuid:", ignoreCase = true)) trimmed.substring("uuid:".length) else trimmed
    return bare.trim().lowercase()
}
