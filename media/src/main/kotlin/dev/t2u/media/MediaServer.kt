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
import kotlinx.coroutines.delay
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
 */
class MediaServer(
    private val host: String,
    private val port: Int,
    private val launcher: PipeLauncher?,
    private val onRequest: (MediaRequest) -> Unit,
    private val bufferDir: File? = null,
) {
    private var server: EmbeddedServer<*, *>? = null

    @Volatile private var boundPort: Int = port
    private val tokens = LinkedHashMap<String, StreamEntry>()
    private val random = SecureRandom()

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
            val entry = match?.let { synchronized(tokens) { tokens[it.groupValues[1]] } }
            if (entry == null || (method != HttpMethod.Get && method != HttpMethod.Head)) {
                out.status = 404
                call.respond(HttpStatusCode.NotFound)
                return
            }
            out.label = entry.label
            val head = method == HttpMethod.Head
            when (val src = entry.source) {
                is StreamSource.FileSource -> serveFile(call, entry, src.file, head, out, "file")
                is StreamSource.PipeSource -> servePipe(call, entry, src, head, out)
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

    private suspend fun servePipe(call: ApplicationCall, entry: StreamEntry, src: StreamSource.PipeSource, head: Boolean, out: Outcome) {
        out.pipeMode = "chunked"
        val type = ContentType.parse(entry.mime)
        val tail = outputArgs(entry.format)
        if (head) {
            // Headers only, no process (a HEAD must never start ffmpeg).
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
        if (l == null || tail == null) {
            out.status = 501
            out.note = if (l == null) "no ffmpeg launcher" else "format ${entry.format} is not a pipe format"
            call.respond(HttpStatusCode.NotImplemented)
            return
        }
        // HTTP/1.0 clients cannot parse chunked framing and Ktor CIO answers them chunked (Pitfall 6):
        // give them a complete, length-delimited body instead.
        val dir = bufferDir
        if (call.request.httpVersion == "HTTP/1.0" && dir != null) {
            serveBuffered(call, entry, src, l, tail, dir, out)
            return
        }

        val proc = withContext(Dispatchers.IO) { l.start(src.inputArgs + tail + "pipe:1") }
        try {
            val stdout = proc.inputStream
            val buf = ByteArray(PIPE_CHUNK)
            // Wait for the first audio byte; headers go out only after it, so a dead start is a 502, not an empty 200.
            val firstLen = coroutineScope {
                val watchdog = launch {
                    delay(FIRST_BYTE_TIMEOUT_MS)
                    out.note = "no first byte after ${FIRST_BYTE_TIMEOUT_MS / 1000} s"
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
                out.status = 502
                out.note = listOfNotNull(out.note, err.takeIf { it.isNotEmpty() }).joinToString(" | ").ifEmpty { "ffmpeg produced no output" }
                call.respond(HttpStatusCode.BadGateway)
                return
            }
            val first = buf.copyOf(firstLen)
            dlnaHeaders(call, entry)
            out.status = 200
            call.respondBytesWriter(contentType = type, status = HttpStatusCode.OK) {
                try {
                    writeFully(first, 0, first.size)
                    out.bytes += first.size
                    flush()
                    while (true) {
                        val n = withContext(Dispatchers.IO) { stdout.read(buf) }
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
                    killAndWait(proc)
                }
            }
        } finally {
            killAndWait(proc)
        }
    }

    /** HTTP/1.0 path: run ffmpeg to completion into a temp file, serve it like a file entry, delete it. */
    private suspend fun serveBuffered(
        call: ApplicationCall,
        entry: StreamEntry,
        src: StreamSource.PipeSource,
        l: PipeLauncher,
        outputArgs: List<String>,
        dir: File,
        out: Outcome,
    ) {
        val tmp = withContext(Dispatchers.IO) {
            dir.mkdirs()
            File.createTempFile("pipe-", "." + extensionOf(entry.format), dir)
        }
        var proc: Process? = null
        try {
            val p = withContext(Dispatchers.IO) { l.start(listOf("-y") + src.inputArgs + outputArgs + tmp.absolutePath) }
            proc = p
            val done = withContext(Dispatchers.IO) { p.waitFor(BUFFER_TIMEOUT_SEC, TimeUnit.SECONDS) }
            if (!done) {
                killAndWait(p)
                out.status = 502
                out.pipeMode = "buffered"
                out.note = "ffmpeg did not finish within $BUFFER_TIMEOUT_SEC s"
                call.respond(HttpStatusCode.BadGateway)
                return
            }
            val code = p.exitValue()
            if (code != 0 || tmp.length() == 0L) {
                val err = withContext(Dispatchers.IO) { l.stderrTail(p) }
                out.status = 502
                out.pipeMode = "buffered"
                out.note = "ffmpeg exit $code: $err".trim()
                call.respond(HttpStatusCode.BadGateway)
                return
            }
            serveFile(call, entry, tmp, false, out, "buffered")
        } finally {
            proc?.let { killAndWait(it) }
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
        private val TOKEN_PATH = Regex("^/stream/([0-9a-f]{32})\\.\\w+$")
    }
}
