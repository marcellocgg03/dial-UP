package dev.t2u.media

import java.util.concurrent.CopyOnWriteArrayList

/** What one request did, filled in as the response is produced and reported in `finally`. */
internal class Outcome {
    var status: Int = 0
    var bytes: Long = 0
    var pipeMode: String? = null
    var label: String? = null
    var note: String? = null

    /** True when the answer was the server's own busy limit (503 with Retry-After), not a failure to produce the stream. */
    var busy: Boolean = false

    /** With [busy]: the refusal was the token's own cap, not the global limit. */
    var perTokenBusy: Boolean = false

    /** The upstream calls of this request, for the log (direct only). */
    val upstream = CopyOnWriteArrayList<UpstreamTiming>()
}

/** One way of getting ffmpeg its input: [input] is evaluated when the attempt starts (it may resolve or throw). */
internal class PipeAttempt(val name: String, val input: suspend () -> List<String>)

private const val MAX_NOTE = 400

/** An attempt's failure as one line for [MediaRequest.note]; URLs are cut to their host. */
internal fun failureLine(name: String, detail: String, prefixed: Boolean): String {
    val text = Resolver.redactUrls(detail).take(MAX_NOTE)
    return if (prefixed) "$name: $text" else text
}
