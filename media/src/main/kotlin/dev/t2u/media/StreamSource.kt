package dev.t2u.media

import java.io.File

/** Where the bytes of a registered stream come from. */
sealed interface StreamSource {
    /**
     * A complete file: HEAD, GET and Range with a Content-Length. [rebuild] builds the file again when it has vanished
     * since registration (evicted, or reclaimed by Android) and returns the finished file; null for a plain file,
     * which then answers 404 when it is gone.
     */
    data class FileSource(val file: File, val rebuild: (suspend () -> File)? = null) : StreamSource

    /**
     * An ffmpeg run: [inputArgs] are the input side only (`-i ... -vn`); the server appends the output side
     * per format (adts: `-c:a copy -f adts pipe:1`, mp3: `-c:a libmp3lame -b:a 192k -f mp3 pipe:1`).
     */
    data class PipeSource(val inputArgs: List<String>) : StreamSource

    /**
     * A cast video: the server resolves [entry]'s ID through its [VideoResolver] when the speaker asks, and
     * runs the attempt ladder (YouTube URL, then a fresh extraction) before answering 502.
     */
    data class VideoPipe(val entry: VideoEntry) : StreamSource

    /**
     * A cast video relayed from YouTube's own audio URL (format `direct`): the server resolves [entry]'s ID when
     * the speaker asks and proxies the bytes through its [UpstreamFetcher], Range included.
     */
    data class DirectProxy(val entry: VideoEntry) : StreamSource
}

/** A registered stream. [format] is a key of [FORMATS]; [label] only appears in the request log. */
data class StreamEntry(val source: StreamSource, val format: String, val mime: String, val label: String)
