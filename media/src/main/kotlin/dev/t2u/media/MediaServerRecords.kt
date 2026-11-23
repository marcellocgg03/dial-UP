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

    /** True when every attempt of the ladder failed because YouTube refused the stream (a 403 or 410), so no other format can work. */
    var upstreamRefused: Boolean = false

    /** The upstream calls of this request, for the log (direct only). */
    val upstream = CopyOnWriteArrayList<UpstreamTiming>()
}

/**
 * One way of getting ffmpeg its input: [input] is evaluated when the attempt starts (it may resolve or throw).
 * [release] hands back what [input] made (a feed token) and is called once the attempt is over: its ffmpeg process has been
 * killed and waited for, or it never started one. It must be safe to call after an [input] that failed, and twice.
 */
internal class PipeAttempt(val name: String, val input: suspend () -> List<String>, val release: () -> Unit = {})

private const val MAX_NOTE = 400

private val UPSTREAM_REFUSAL = Regex("""(Server returned|HTTP Error) (403|410)\b""")

/** Does an attempt's failure line say that YouTube refused or expired the stream (ffmpeg "Server returned 403", yt-dlp "HTTP Error 403")? */
internal fun isUpstreamRefusal(line: String): Boolean = UPSTREAM_REFUSAL.containsMatchIn(line)

/** An attempt's failure as one line for [MediaRequest.note]; URLs are cut to their host. */
internal fun failureLine(name: String, detail: String, prefixed: Boolean): String {
    val text = Resolver.redactUrls(detail).take(MAX_NOTE)
    return if (prefixed) "$name: $text" else text
}
