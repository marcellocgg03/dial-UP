package dev.t2u.lounge

// Port of yt-cast-receiver 2.1.0 lib/app/PlaylistRequestHandler.js (MIT, Copyright (c) Patrick Kan).

/** What the handler found around a video: either side may be missing. */
data class PreviousNextVideos(val previous: Video?, val next: Video?)

/**
 * Answers the questions a [Playlist] cannot answer from the sender's payload alone: which video
 * comes before and after [target], and which is the autoplay video after the last one.
 *
 * Cancelling the calling coroutine (or the playlist cancelling a stale refresh) cancels the call, so
 * implementations must be cancellable and must not swallow CancellationException.
 */
interface PlaylistRequestHandler {
    suspend fun getPreviousNextVideos(target: Video, playlist: Playlist): PreviousNextVideos?

    /** Resets the handler to its initial state. By default this does nothing. */
    fun reset() {}
}

/**
 * Phase 2 seam: the InnerTube /next (TVHTML5) client replaces this in Phase 3 (CAST-05).
 *
 * Returns nothing, so with this handler hasPrevious and hasNext are false, next() stops the player
 * and there is no autoplay video; single-song casts (setPlaylist, pause, seek, volume) work fully.
 */
object NoOpPlaylistRequestHandler : PlaylistRequestHandler {
    override suspend fun getPreviousNextVideos(target: Video, playlist: Playlist): PreviousNextVideos? = null
}
