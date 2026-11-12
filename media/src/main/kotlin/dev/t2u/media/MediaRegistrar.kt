package dev.t2u.media

/**
 * A video to stream to a speaker. [format] is `adts` or `mp3` (ffmpeg pipes) or `direct` (a relay of YouTube's own
 * URL; then [ext] and [sourceMime] name the source container, e.g. `m4a` and `audio/mp4`). [startAt] is the offset in
 * seconds for streams the speaker cannot seek, [onRequest] runs once per GET (mediaServer.js `entry.onRequest`).
 * [onFailure] runs when a GET of this entry is answered with a 5xx (every ffmpeg attempt or the upstream fetch failed),
 * so the renderer can step to the next format without waiting out the start timeout.
 */
data class VideoEntry(
    val videoId: String,
    val format: String,
    val startAt: Int = 0,
    val ext: String? = null,
    val sourceMime: String? = null,
    val onRequest: (() -> Unit)? = null,
    val onFailure: (() -> Unit)? = null,
)

/** What the renderer side needs from the media server: hand a video over, get the path to give the speaker. */
interface MediaRegistrar {
    /**
     * Builds whatever [entry]'s format needs before the URL is handed to a speaker: the file formats (mp4)
     * are downloaded and converted here. The renderer calls it before [registerVideo], so the speaker's start clock
     * does not include the build. The default does nothing, so stubs that implement only [registerVideo] keep compiling.
     */
    suspend fun prepare(entry: VideoEntry) {}

    /** Registers before preparing so file ownership survives cancellation and concurrent requests. */
    suspend fun prepareAndRegister(entry: VideoEntry): String {
        prepare(entry)
        return registerVideo(entry)
    }

    /**
     * The file formats already built and cached for [videoId], best first. Auto mode plays one of these (when the speaker takes
     * it) instead of building the format the priority list would pick. The default is empty, for stubs.
     */
    fun cachedFormats(videoId: String): List<String> = emptyList()

    /** @return `/stream/<token>.<ext>`. @throws IllegalArgumentException for an invalid ID or a non-pipe format. */
    fun registerVideo(entry: VideoEntry): String

    /**
     * The renderer no longer needs the stream at [path] (a newer track replaced it, or the speaker was stopped or cleared):
     * whatever the server holds for it in memory can go. The token itself stays registered, so a speaker that
     * still asks for it is served. A file stream's file can be evicted by the cache from now on (the playing and the
     * prefetched file are pinned from [registerVideo] until this call). The default does nothing, so stubs that implement
     * only [registerVideo] keep compiling.
     */
    fun release(path: String) {}
}
