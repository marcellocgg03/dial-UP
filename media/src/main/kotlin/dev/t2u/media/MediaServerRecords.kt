package dev.t2u.media

/** What one request did, filled in as the response is produced and reported in `finally`. */
internal class Outcome {
    var status: Int = 0
    var bytes: Long = 0
    var pipeMode: String? = null
    var label: String? = null
    var note: String? = null

    /** The upstream calls of this request, for the log (direct only). */
    val upstream = java.util.concurrent.CopyOnWriteArrayList<UpstreamTiming>()
}

/** One way of getting ffmpeg its input: [input] is evaluated when the attempt starts (it may resolve or throw). */
internal class PipeAttempt(val name: String, val input: suspend () -> List<String>)
