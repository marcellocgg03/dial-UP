package dev.t2u.media

/**
 * One handled call, reported after the response was produced (404s included). [pipeMode] is `file`,
 * `chunked` (ffmpeg stdout streamed) or `buffered` (ffmpeg run to a temp file first, for HTTP/1.0 clients),
 * and null when no stream was involved. [note] carries ffmpeg's stderr tail on a failed start.
 */
data class MediaRequest(
    val method: String,
    val uri: String,
    val httpVersion: String,
    val remoteHost: String,
    val remotePort: Int,
    val range: String?,
    val userAgent: String?,
    val status: Int,
    val bytes: Long,
    val pipeMode: String? = null,
    val label: String? = null,
    val note: String? = null,
    /**
     * What the direct proxy asked upstream for this request, as text with milliseconds to the first byte and to the end of
     * each call and no URL, or `window hit` when the answer came from memory; null for every other source.
     */
    val upstream: String? = null,
)
