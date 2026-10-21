package dev.t2u.media

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.serverConfig
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.EngineConnectorBuilder
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.httpMethod
import io.ktor.server.request.httpVersion
import io.ktor.server.request.uri
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/** Where the bytes of a registered stream come from. */
sealed interface StreamSource {
    /** A complete file: HEAD, GET and Range with a Content-Length. */
    data class FileSource(val file: File) : StreamSource

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
}

/**
 * A video to stream to a speaker as an ffmpeg pipe. [format] is `adts` or `mp3` (file formats are Phase 3),
 * [startAt] is the offset in seconds for streams the speaker cannot seek, [onRequest] runs once per GET
 * (mediaServer.js `entry.onRequest`).
 */
data class VideoEntry(
    val videoId: String,
    val format: String,
    val startAt: Int = 0,
    val onRequest: (() -> Unit)? = null,
)

/** What the renderer side needs from the media server: hand a video over, get the path to give the speaker. */
interface MediaRegistrar {
    /** @return `/stream/<token>.<ext>`. @throws IllegalArgumentException for an invalid ID or a non-pipe format. */
    fun registerVideo(entry: VideoEntry): String
}

/**
 * The input side of mediaServer.js `#urlInputArgs`: ffmpeg reads [info]'s URL itself with the headers yt-dlp
 * chose, reconnects on a dropped read, and (input-side `-ss`) range-requests from [startAt] instead of reading from 0.
 */
fun urlInputArgs(info: ResolvedInfo, startAt: Int): List<String> {
    val hdrs = info.headers.entries.joinToString("") { (k, v) -> "$k: $v\r\n" }
    val args = mutableListOf("-hide_banner", "-loglevel", "error")
    if (hdrs.isNotEmpty()) args += listOf("-headers", hdrs)
    args += listOf("-reconnect", "1", "-reconnect_streamed", "1", "-reconnect_delay_max", "5")
    if (startAt > 0) args += listOf("-ss", startAt.toString())
    args += listOf("-i", info.url, "-vn")
    return args
}

/** A registered stream. [format] is a key of [FORMATS]; [label] only appears in the request log. */
data class StreamEntry(val source: StreamSource, val format: String, val mime: String, val label: String)

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
)

/**
 * Token media server, a port of reference/tube2upnp/src/mediaServer.js for the Phase 1 audio-path proof.
 *
 * Only `GET` and `HEAD` on `/stream/<32 lowercase hex>.<ext>` for a registered token are served; every other
 * path, method or token answers 404 (Node parity, and there is no route that takes a URL or a path, which
 * keeps the server from becoming an open proxy, threat T-01-14). Body lengths are set only through the
 * OutgoingContent content length: a Content-Length header written by hand is duplicated by Ktor CIO
 * (RESEARCH Pitfall 4).
 *
 * [bufferDir] is where HTTP/1.0 clients get ffmpeg output buffered first (they cannot parse chunked framing,
 * RESEARCH Pitfall 6).
 *
 * Ranged GETs on video pipe tokens (plan 02-16, G4): the first response is always the live chunked stream, with no
 * length and no Accept-Ranges. While it streams, the bytes are also kept in a bounded file under
 * `bufferDir/range-cache` ([PipeRangeCache], limits in [pipeRanges]); once the run has ended cleanly, a `Range`
 * request on that token is answered 206 with the true total from those bytes, without a new ffmpeg run. Node's
 * `#pipe` ignores Range; this is a Kotlin-only addition (PARITY.md Adaptation 11).
 */
class MediaServer(
    private val host: String,
    private val port: Int,
    private val launcher: PipeLauncher?,
    private val onRequest: (MediaRequest) -> Unit,
    private val bufferDir: File? = null,
    private val resolver: VideoResolver? = null,
    private val pipeRanges: PipeRangeConfig = PipeRangeConfig(),
) : MediaRegistrar {
    private var server: EmbeddedServer<*, *>? = null

    @Volatile private var boundPort: Int = port
    private val tokens = LinkedHashMap<String, StreamEntry>()
    private val random = SecureRandom()

    /** Finished runs of video pipe tokens, for ranged GETs (plan 02-16); null when disabled or without a [bufferDir]. */
    private val rangeCache: PipeRangeCache? =
        if (pipeRanges.enabled && bufferDir != null) PipeRangeCache(File(bufferDir, "range-cache"), pipeRanges) else null

    fun start() {
        check(server == null) { "already started" }
        // reuseAddress: a restart must not hit EADDRINUSE from TIME_WAIT
        val s = embeddedServer(CIO, serverConfig { module { configure(this) } }) {
            reuseAddress = true
            connectors.add(
                EngineConnectorBuilder().apply {
                    host = this@MediaServer.host
                    port = this@MediaServer.port
                },
            )
        }
        s.start(wait = false)
        boundPort = runBlocking { s.engine.resolvedConnectors().first().port }
        server = s
    }

    fun stop() {
        server?.stop(200, 1000)
        server = null
    }

    fun isRunning(): Boolean = server != null

    fun resolvedPort(): Int = boundPort

    fun tokenCount(): Int = synchronized(tokens) { tokens.size }

    /**
     * Registers [entry] and returns the path to hand to a renderer: `/stream/<token>.<ext>`. The token is 16
     * bytes from SecureRandom as 32 lowercase hex characters; at most [MAX_TOKENS] stay registered, the
     * oldest is evicted first (Node MAX_TOKENS).
     */
    fun register(entry: StreamEntry): String {
        val ext = extensionOf(entry.format)
        val raw = ByteArray(16).also(random::nextBytes)
        val token = raw.joinToString("") { "%02x".format(it) }
        synchronized(tokens) {
            tokens[token] = entry
            while (tokens.size > MAX_TOKENS) tokens.remove(tokens.keys.first())
        }
        return "/stream/$token.$ext"
    }

    override fun registerVideo(entry: VideoEntry): String {
        requireVideoId(entry.videoId)
        require(outputArgs(entry.format) != null) { "format ${entry.format} is not a pipe format (adts or mp3)" }
        require(entry.startAt >= 0) { "startAt must not be negative" }
        val mime = FORMATS.getValue(entry.format).mime ?: throw IllegalArgumentException("format ${entry.format} has no fixed MIME")
        return register(StreamEntry(StreamSource.VideoPipe(entry), entry.format, mime, entry.videoId))
    }

    /** Installs the handler into [app]; used by [start] and available to testApplication tests. */
    fun configure(app: Application) {
        app.intercept(ApplicationCallPipeline.Call) { handle(call) }
    }

    /** What one request did, filled in as the response is produced and reported in `finally`. */
    private class Outcome {
        var status: Int = 0
        var bytes: Long = 0
        var pipeMode: String? = null
        var label: String? = null
        var note: String? = null
    }

    private suspend fun handle(call: ApplicationCall) {
        val out = Outcome()
        try {
            val uri = call.request.uri
            val method = call.request.httpMethod
            val match = TOKEN_PATH.matchEntire(uri)
            val token = match?.groupValues?.get(1).orEmpty()
            val entry = match?.let { synchronized(tokens) { tokens[token] } }
            if (entry == null || (method != HttpMethod.Get && method != HttpMethod.Head)) {
                out.status = 404
                call.respond(HttpStatusCode.NotFound)
                return
            }
            out.label = entry.label
            val head = method == HttpMethod.Head
            when (val src = entry.source) {
                is StreamSource.FileSource -> serveFile(call, entry, src.file, head, out, "file")
                is StreamSource.PipeSource -> servePipe(call, entry, src, head, out, token)
                is StreamSource.VideoPipe -> {
                    if (!head) runCatching { src.entry.onRequest?.invoke() }
                    servePipe(call, entry, src, head, out, token)
                }
            }
        } finally {
            report(call, out)
        }
    }

    private fun report(call: ApplicationCall, out: Outcome) {
        try {
            val req = call.request
            val origin = req.local
            onRequest(
                MediaRequest(
                    method = req.httpMethod.value,
                    uri = req.uri,
                    httpVersion = req.httpVersion,
                    remoteHost = origin.remoteAddress,
                    remotePort = origin.remotePort,
                    range = req.headers[HttpHeaders.Range],
                    userAgent = req.headers[HttpHeaders.UserAgent],
                    status = out.status,
                    bytes = out.bytes,
                    pipeMode = out.pipeMode,
                    label = out.label,
                    note = out.note,
                ),
            )
        } catch (_: Throwable) {
            // reporting must never break request handling
        }
    }

    private fun dlnaHeaders(call: ApplicationCall, entry: StreamEntry) {
        call.response.header("transferMode.dlna.org", "Streaming")
        call.response.header("contentFeatures.dlna.org", dlnaFeatures(entry.format))
        call.response.header(HttpHeaders.CacheControl, "no-store")
    }

    // ------------------------------------------------------------------------------------ file

    private suspend fun serveFile(
        call: ApplicationCall,
        entry: StreamEntry,
        file: File,
        head: Boolean,
        out: Outcome,
        pipeMode: String,
    ) {
        out.pipeMode = pipeMode
        val size = file.length()
        val rangeHeader = call.request.headers[HttpHeaders.Range]
        val range = if (rangeHeader != null) parseRange(rangeHeader, size) else null
        if (range is ByteRange.Unsatisfiable) {
            out.status = 416
            call.response.header(HttpHeaders.ContentRange, "bytes */$size")
            call.respond(HttpStatusCode.RequestedRangeNotSatisfiable)
            return
        }
        val span = range as? ByteRange.Span ?: ByteRange.Span(0, size - 1)
        val length = if (size == 0L) 0 else span.end - span.start + 1
        val partial = range is ByteRange.Span
        val status = if (partial) HttpStatusCode.PartialContent else HttpStatusCode.OK

        dlnaHeaders(call, entry)
        call.response.header(HttpHeaders.AcceptRanges, "bytes")
        if (partial) call.response.header(HttpHeaders.ContentRange, "bytes ${span.start}-${span.end}/$size")
        out.status = status.value
        val type = ContentType.parse(entry.mime)

        if (head) {
            call.respond(
                object : OutgoingContent.NoContent() {
                    override val status: HttpStatusCode = status
                    override val contentType: ContentType = type
                    override val contentLength: Long = length
                },
            )
            return
        }
        call.respond(
            object : OutgoingContent.WriteChannelContent() {
                override val status: HttpStatusCode = status
                override val contentType: ContentType = type
                override val contentLength: Long = length

                override suspend fun writeTo(channel: ByteWriteChannel) {
                    withContext(Dispatchers.IO) {
                        RandomAccessFile(file, "r").use { raf ->
                            raf.seek(span.start)
                            var left = length
                            val buf = ByteArray(FILE_CHUNK)
                            while (left > 0) {
                                val n = raf.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                                if (n < 0) break
                                channel.writeFully(buf, 0, n)
                                out.bytes += n
                                left -= n
                            }
                        }
                    }
                }
            },
        )
    }

    // ------------------------------------------------------------------------------------ pipe

    /** Output side of the ffmpeg command per format (mediaServer.js lines 123-124); null for non-pipe formats. */
    private fun outputArgs(format: String): List<String>? = when (format) {
        "adts" -> listOf("-c:a", "copy", "-f", "adts")
        "mp3" -> listOf("-c:a", "libmp3lame", "-b:a", MP3_BITRATE, "-f", "mp3")
        else -> null
    }

    /** Kills [proc] and waits up to 2 s for it to be gone (Node `res.on('close', kill)`). */
    private suspend fun killAndWait(proc: Process) {
        withContext(NonCancellable + Dispatchers.IO) {
            proc.destroyForcibly()
            runCatching { proc.waitFor(2, TimeUnit.SECONDS) }
        }
    }

    /** One way of getting ffmpeg its input: [input] is evaluated when the attempt starts (it may resolve or throw). */
    private class PipeAttempt(val name: String, val input: suspend () -> List<String>)

    /**
     * The ladder of mediaServer.js `#pipe`: a [StreamSource.PipeSource] has one attempt, a
     * [StreamSource.VideoPipe] reads YouTube's URL and, when that fails, drops the cached resolve and reads a
     * fresh extraction. The yt-dlp download rung of the Node ladder belongs to the file formats (Phase 3).
     */
    private fun attemptsFor(src: StreamSource): List<PipeAttempt>? = when (src) {
        is StreamSource.PipeSource -> listOf(PipeAttempt("stream") { src.inputArgs })
        is StreamSource.VideoPipe -> resolver?.let { r ->
            val id = src.entry.videoId
            val startAt = src.entry.startAt
            listOf(
                PipeAttempt("YouTube URL") { urlInputArgs(r.resolve(id), startAt) },
                PipeAttempt("a fresh extraction") {
                    r.invalidate(id)
                    urlInputArgs(r.resolve(id), startAt)
                },
            )
        }
        is StreamSource.FileSource -> null
    }

    /** An attempt's failure as one line for [MediaRequest.note]; URLs are cut to their host (threat T-02-15). */
    private fun failureLine(name: String, detail: String, prefixed: Boolean): String {
        val text = Resolver.redactUrls(detail).take(MAX_NOTE)
        return if (prefixed) "$name: $text" else text
    }

    private suspend fun servePipe(
        call: ApplicationCall,
        entry: StreamEntry,
        src: StreamSource,
        head: Boolean,
        out: Outcome,
        token: String,
    ) {
        out.pipeMode = "chunked"
        val type = ContentType.parse(entry.mime)
        val tail = outputArgs(entry.format)
        if (head) {
            // Headers only, no process and no resolve (a HEAD must never start ffmpeg).
            dlnaHeaders(call, entry)
            out.status = 200
            call.respond(
                object : OutgoingContent.WriteChannelContent() {
                    override val status: HttpStatusCode = HttpStatusCode.OK
                    override val contentType: ContentType = type

                    override suspend fun writeTo(channel: ByteWriteChannel) {}
                },
            )
            return
        }
        val l = launcher
        val attempts = attemptsFor(src)
        if (l == null || tail == null || attempts == null) {
            out.status = 501
            out.note = when {
                l == null -> "no ffmpeg launcher"
                tail == null -> "format ${entry.format} is not a pipe format"
                else -> "no resolver for video tokens"
            }
            call.respond(HttpStatusCode.NotImplemented)
            return
        }
        // HTTP/1.0 clients cannot parse chunked framing and Ktor CIO answers them chunked (Pitfall 6):
        // give them a complete, length-delimited body instead.
        val dir = bufferDir
        if (call.request.httpVersion == "HTTP/1.0" && dir != null) {
            serveBuffered(call, entry, attempts, l, tail, dir, out)
            return
        }

        // Plan 02-16 (G4): a ranged GET on a video token whose first run completed is answered from that run's bytes,
        // before anything is resolved or launched. A header parseRange rejects falls through to the live path.
        val cache = if (src is StreamSource.VideoPipe) rangeCache else null
        val rangeHeader = call.request.headers[HttpHeaders.Range]
        if (cache != null && rangeHeader != null) {
            val cached = cache.lookup(token)
            if (cached != null && cached.file.exists() && parseRange(rangeHeader, cached.size) != null) {
                out.note = "range $rangeHeader of ${cached.size} bytes from the first run"
                serveFile(call, entry, cached.file, false, out, "cached-range")
                return
            }
        }

        val failures = mutableListOf<String>()
        for (attempt in attempts) {
            val input = try {
                attempt.input()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failures += failureLine(attempt.name, e.message ?: e.javaClass.simpleName, attempts.size > 1)
                continue
            }
            // Hung up while we resolved: an ffmpeg started now would block on a pipe nobody reads (mediaServer.js).
            // Measured with Ktor CIO 3.6.0: the call's coroutine stays active when the peer closes before the first
            // response byte, so this only catches cancellation (engine stop). A hang-up during the lookup is caught by
            // the first write below, which fails and kills ffmpeg within a flush (MediaServerLadderTest).
            if (!currentCoroutineContext().isActive) return
            val proc = withContext(Dispatchers.IO) { l.start(input + tail + "pipe:1") }
            try {
                val stdout = proc.inputStream
                val buf = ByteArray(PIPE_CHUNK)
                var watchdogNote: String? = null
                // Wait for the first audio byte; headers go out only after it, so a dead start is a 502, not an empty 200.
                val firstLen = coroutineScope {
                    val watchdog = launch {
                        delay(FIRST_BYTE_TIMEOUT_MS)
                        watchdogNote = "no first byte after ${FIRST_BYTE_TIMEOUT_MS / 1000} s"
                        proc.destroyForcibly()
                    }
                    try {
                        withContext(Dispatchers.IO) { stdout.read(buf) }
                    } finally {
                        watchdog.cancel()
                    }
                }
                if (firstLen <= 0) {
                    withContext(Dispatchers.IO) { runCatching { proc.waitFor(2, TimeUnit.SECONDS) } }
                    val err = withContext(Dispatchers.IO) { l.stderrTail(proc) }
                    val detail = listOfNotNull(watchdogNote, err.takeIf { it.isNotEmpty() }).joinToString(" | ")
                        .ifEmpty { "ffmpeg produced no output" }
                    failures += failureLine(attempt.name, detail, attempts.size > 1)
                    continue
                }
                val first = buf.copyOf(firstLen)
                // The tee copies exactly what the client is sent, so a complete run is the byte total a later Range can use.
                var tee: TeeFill? = cache?.openTee(token)
                if (tee?.write(first, 0, first.size) == false) tee = null
                dlnaHeaders(call, entry)
                out.status = 200
                try {
                    call.respondBytesWriter(contentType = type, status = HttpStatusCode.OK) {
                        try {
                            writeFully(first, 0, first.size)
                            out.bytes += first.size
                            flush()
                            while (true) {
                                // file writes stay on the IO dispatcher, never on the Ktor event loop
                                val n = withContext(Dispatchers.IO) {
                                    val k = stdout.read(buf)
                                    val t = tee
                                    if (t != null) {
                                        if (k > 0) {
                                            if (!t.write(buf, 0, k)) tee = null
                                        } else if (k < 0) {
                                            // End of stream: register the complete run BEFORE the body ends, so a Range
                                            // that follows the last byte already finds it. Only a clean exit counts.
                                            val clean = runCatching { proc.waitFor(2, TimeUnit.SECONDS) && proc.exitValue() == 0 }
                                                .getOrDefault(false)
                                            if (clean) t.complete() else t.abort()
                                            tee = null
                                        }
                                    }
                                    k
                                }
                                if (n < 0) break
                                writeFully(buf, 0, n)
                                out.bytes += n
                                flush()
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            // a write failure is the client hanging up; the finally below kills ffmpeg
                            out.note = "client closed: ${e.javaClass.simpleName}"
                        } finally {
                            tee?.abort()
                            killAndWait(proc)
                        }
                    }
                } finally {
                    tee?.abort()
                }
                return
            } finally {
                killAndWait(proc)
            }
        }
        out.status = 502
        out.note = failures.joinToString(" | ").ifEmpty { "ffmpeg produced no output" }
        call.respond(HttpStatusCode.BadGateway)
    }

    /** HTTP/1.0 path: run ffmpeg to completion into a temp file, serve it like a file entry, delete it. */
    private suspend fun serveBuffered(
        call: ApplicationCall,
        entry: StreamEntry,
        attempts: List<PipeAttempt>,
        l: PipeLauncher,
        outputArgs: List<String>,
        dir: File,
        out: Outcome,
    ) {
        val tmp = withContext(Dispatchers.IO) {
            dir.mkdirs()
            File.createTempFile("pipe-", "." + extensionOf(entry.format), dir)
        }
        val failures = mutableListOf<String>()
        try {
            for (attempt in attempts) {
                val input = try {
                    attempt.input()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failures += failureLine(attempt.name, e.message ?: e.javaClass.simpleName, attempts.size > 1)
                    continue
                }
                if (!currentCoroutineContext().isActive) return
                var proc: Process? = null
                try {
                    val p = withContext(Dispatchers.IO) { l.start(listOf("-y") + input + outputArgs + tmp.absolutePath) }
                    proc = p
                    val done = withContext(Dispatchers.IO) { p.waitFor(BUFFER_TIMEOUT_SEC, TimeUnit.SECONDS) }
                    if (!done) {
                        killAndWait(p)
                        failures += failureLine(attempt.name, "ffmpeg did not finish within $BUFFER_TIMEOUT_SEC s", attempts.size > 1)
                        continue
                    }
                    val code = p.exitValue()
                    if (code != 0 || tmp.length() == 0L) {
                        val err = withContext(Dispatchers.IO) { l.stderrTail(p) }
                        failures += failureLine(attempt.name, "ffmpeg exit $code: $err".trim(), attempts.size > 1)
                        continue
                    }
                } finally {
                    proc?.let { killAndWait(it) }
                }
                serveFile(call, entry, tmp, false, out, "buffered")
                return
            }
            out.status = 502
            out.pipeMode = "buffered"
            out.note = failures.joinToString(" | ").ifEmpty { "ffmpeg produced no output" }
            call.respond(HttpStatusCode.BadGateway)
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { tmp.delete() }
        }
    }

    companion object {
        const val MAX_TOKENS = 256
        private const val FILE_CHUNK = 64 * 1024
        private const val PIPE_CHUNK = 16 * 1024
        private const val MP3_BITRATE = "192k"
        private const val FIRST_BYTE_TIMEOUT_MS = 20_000L
        private const val BUFFER_TIMEOUT_SEC = 180L
        private const val MAX_NOTE = 400
        private val TOKEN_PATH = Regex("^/stream/([0-9a-f]{32})\\.\\w+$")
    }
}
