package dev.t2u.renderer

// Records a Renderer keeps about the track, the next track and a Seek in flight, GPL-3.0-only.

import dev.t2u.lounge.Video
import dev.t2u.upnp.UpnpError

/**
 * The track the speaker was last told to play. [startAt] is the stream's offset; [offsetOnly] keeps
 * the temporary format's origin across seeks and network recovery, even when reloaded at zero.
 */
internal class Current(
    val video: Video,
    val format: String,
    val startAt: Int,
    val sourceMime: String,
    val offsetOnly: Boolean,
) {
    @Volatile var loadedAt: Long = 0

    @Volatile var fetched: Boolean = false

    @Volatile var streamPath: String? = null

    /** False for a stream loaded while paused (SetAVTransportURI only) until the first Play is sent for it. */
    @Volatile var playSent: Boolean = false

    /**
     * When the wait for this stream's first PLAYING began (the load, or the first Play of a stream loaded while paused).
     * Unlike [loadedAt] a Seek does not move it, so it anchors the hard cap on the start wait.
     */
    @Volatile var startedAt: Long = 0

    /**
     * The position (seconds, absolute) this stream is meant to play from: the position it was loaded at, or the target of
     * the last Seek the speaker accepted. A start-failure reload goes back to it when the speaker was never polled
     * playing, so the reload does not restart the song.
     */
    @Volatile var wantedAt: Double = 0.0

    /** A position the post-Play Seek could not be sent to (the speaker refused it while starting); sent once it plays. */
    @Volatile var owedSeek: Double? = null

    /**
     * True after a native Seek the speaker accepted while paused, until the next Play: that Play can leave the speaker
     * TRANSITIONING for seconds (Lamp, device session D), so the sender is told buffering until it plays.
     */
    @Volatile var seekedWhilePaused: Boolean = false
}

/** How a [Renderer.loadResult] ended. */
internal sealed interface LoadResult {
    /** The speaker was told to play the track. */
    data object Started : LoadResult

    /** A newer play, a stop or removal overtook the load. */
    data object Superseded : LoadResult

    /** Taking or preparing the item failed (the lookup, the format chain, the registration): a problem with the track. */
    class PrepareFailed(val error: Exception) : LoadResult

    /** A call to the speaker failed. */
    class SpeakerFailed(val error: Exception) : LoadResult
}

/** A track resolved, registered with the media server and described in DIDL, ready to send. */
internal class PreparedItem(
    val current: Current,
    val title: String,
    val artist: String,
    val duration: Double,
    val thumbnail: String,
    val format: String,
    val mime: String,
    val url: String,
    val metadata: String,
)

/** The next track, prepared ahead of its turn (renderer.js `#queued`): the video it was prepared for and the ready item. */
internal class Queued(val video: Video, val item: PreparedItem) {
    /** True once the speaker accepted this item as its next track (SetNextAVTransportURI). */
    @Volatile var sentToRenderer: Boolean = false

    @Volatile var sendAttempts: Int = 0
}

/**
 * True when a SetNextAVTransportURI refusal means this speaker has no gapless support (renderer.js line 483): UPnP error 401
 * (invalid action) or 602 (optional action not implemented), or no AVTransport service. Any other failure may be passing.
 */
internal fun isGaplessRefusal(e: UpnpError): Boolean =
    e.errorCode == "401" || e.errorCode == "602" || e.code == "ENOACTION" || e.code == "ENOSERVICE"

/**
 * A native Seek the speaker accepted but may not have finished: until the speaker has visibly moved on from it the
 * position handed to the sender is [target]. [shown] and [shownAt] are the last position handed out and when, which
 * is what the sender's seekbar counts on from. [sawTransitioning] is set once the speaker has been seen in a state
 * other than a settled one (TRANSITIONING) after the Seek: a Sonos keeps reporting its OLD state, PLAYING with the old
 * RelTime, for about 0.7 s after it accepted the Seek, so a bare PLAYING right after a Seek proves nothing.
 * [awaitPlay] marks the hold of a Play that followed a paused Seek: the speaker is believed only once it is
 * PLAYING (or stopped), because it still reports PAUSED_PLAYBACK at the target for a moment after the Play.
 */
internal class PendingSeek(val id: Int, val target: Double, val startedMs: Long, val awaitPlay: Boolean = false) {
    @Volatile var shown: Double = target

    @Volatile var shownAt: Long = startedMs

    @Volatile var sawTransitioning: Boolean = false
}

/** Result of the last poll: [rel] is already shifted by the stream offset. [reported] is false when the speaker gave no readable position and [rel] is carried over. */
internal data class PollPosition(val rel: Double, val dur: Double, val uri: String?, val at: Long, val reported: Boolean = true)
