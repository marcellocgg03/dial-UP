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
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.RandomAccessFile
import java.net.BindException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Where the bytes of a registered stream come from. */
sealed interface StreamSource {
    /**
     * A complete file: HEAD, GET and Range with a Content-Length. [rebuild] builds the file again when it has vanished
     * since registration (evicted, or reclaimed by Android; D-03) and returns the finished file; null for a plain file,
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

/**
 * A video to stream to a speaker. [format] is `adts` or `mp3` (ffmpeg pipes) or `direct` (a relay of YouTube's own
 * URL; then [ext] and [sourceMime] name the source container, e.g. `m4a` and `audio/mp4`). [startAt] is the offset in
 * seconds for streams the speaker cannot seek, [onRequest] runs once per GET (mediaServer.js `entry.onRequest`).
 */
data class VideoEntry(
    val videoId: String,
    val format: String,
    val startAt: Int = 0,
    val ext: String? = null,
    val sourceMime: String? = null,
    val onRequest: (() -> Unit)? = null,
)

/** What the renderer side needs from the media server: hand a video over, get the path to give the speaker. */
interface MediaRegistrar {
    /**
     * Builds whatever [entry]'s format needs before the URL is handed to a speaker: the file formats (mp4, flac)
     * are downloaded and converted here. The renderer calls it before [registerVideo], so the speaker's start clock
     * does not include the build. The default does nothing, so stubs that implement only [registerVideo] keep compiling.
     */
    suspend fun prepare(entry: VideoEntry) {}

    /**
     * The file formats already built and cached for [videoId], best first. Auto mode plays one of these (when the speaker takes
     * it) instead of building the format the priority list would pick. The default is empty, for stubs.
     */
    fun cachedFormats(videoId: String): List<String> = emptyList()

    /** @return `/stream/<token>.<ext>`. @throws IllegalArgumentException for an invalid ID or a non-pipe format. */
    fun registerVideo(entry: VideoEntry): String

    /**
     * The renderer no longer needs the stream at [path] (a newer track replaced it, or the speaker was stopped or cleared):
     * whatever the server holds for it in memory can go (plan 03-20). The token itself stays registered, so a speaker that
     * still asks for it is served. A file stream's file can be evicted by the cache from now on (D-04: the playing and the
     * prefetched file are pinned from [registerVideo] until this call). The default does nothing, so stubs that implement
     * only [registerVideo] keep compiling.
     */
    fun release(path: String) {}
}

/**
 * The input side of mediaServer.js `#urlInputArgs`: ffmpeg reads [info]'s URL itself with the headers yt-dlp
 * chose, reconnects on a dropped read, and (input-side `-ss`) range-requests from [startAt] instead of reading from 0.
 * [logLevel] is the `ffmpegLogLevel` config switch (a name from the validated whitelist).
 */
fun urlInputArgs(info: ResolvedInfo, startAt: Int, logLevel: String = "error"): List<String> {
    val hdrs = info.headers.entries.joinToString("") { (k, v) -> "$k: $v\r\n" }
    val args = mutableListOf("-hide_banner", "-loglevel", logLevel)
    if (hdrs.isNotEmpty()) args += listOf("-headers", hdrs)
    args += listOf("-reconnect", "1", "-reconnect_streamed", "1", "-reconnect_delay_max", "5")
    if (startAt > 0) args += listOf("-ss", startAt.toString())
    args += listOf("-i", info.url, "-vn")
    return args
}

/**
 * The input side of mediaServer.js `#fileInputArgs`: ffmpeg reads the downloaded file at [path], seeking on the
 * input side to [startAt] seconds when that is positive. [logLevel] is the `ffmpegLogLevel` config switch.
 */
fun fileInputArgs(path: String, startAt: Int, logLevel: String = "error"): List<String> {
    val args = mutableListOf("-hide_banner", "-loglevel", logLevel)
    if (startAt > 0) args += listOf("-ss", startAt.toString())
    args += listOf("-i", path, "-vn")
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
    /**
     * What the direct proxy asked upstream for this request, as text with milliseconds to the first byte and to the end of
     * each call and no URL (plan 03-20), or `window hit` when the answer came from memory; null for every other source.
     */
    val upstream: String? = null,
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
 * [mp3Bitrate] and [ffmpegLogLevel] are the `mp3Bitrate` and `ffmpegLogLevel` config switches (plan 03-10): the caller
 * passes values that already passed `Config.validate`, so only a whitelisted name or a `NNNk` shape reaches ffmpeg.
 *
 * [bufferDir] is where HTTP/1.0 clients get ffmpeg output buffered first (they cannot parse chunked framing,
 * RESEARCH Pitfall 6).
 *
 * Ranged GETs on video pipe tokens (plan 02-16, G4): the first response is always the live chunked stream, with no
 * length and no Accept-Ranges. While it streams, the bytes are also kept in a bounded file under
 * `bufferDir/range-cache` ([PipeRangeCache], limits in [pipeRanges]); once the run has ended cleanly, a `Range`
 * request on that token is answered 206 with the true total from those bytes, without a new ffmpeg run. A
 * resume-type Range on an adts token with no finished run (the speaker hung up the first fetch, or the request
 * overtook it) gets one bounded ffmpeg fill into a temp file instead ([coldFill]); mp3 is not filled, because its
 * file output differs from its pipe output, but its warm cache is served as usual. Anything the caps or the time
 * budget refuse falls back to today's live 200 from byte 0. Files are removed when their token is evicted, on
 * [stop] and on [start]. Node's `#pipe` ignores Range; this is a Kotlin-only addition (PARITY.md Adaptation 11).
 */
class MediaServer(
    host: String,
    private val port: Int,
    private val launcher: PipeLauncher?,
    private val onRequest: (MediaRequest) -> Unit,
    private val bufferDir: File? = null,
    private val resolver: VideoResolver? = null,
    private val pipeRanges: PipeRangeConfig = PipeRangeConfig(),
    private val upstream: UpstreamFetcher? = null,
    private val files: FileBuilder? = null,
    private val prepareTimeoutMs: Long = 120_000,
    private val mp3Bitrate: String = MP3_BITRATE,
    private val ffmpegLogLevel: String = "error",
) : MediaRegistrar {
    /** The address the engine listens on now; [rebindHost] replaces it. */
    @Volatile private var host: String = host

    private var server: EmbeddedServer<*, *>? = null

    @Volatile private var boundPort: Int = port
    private val tokens = LinkedHashMap<String, StreamEntry>()
    private val random = SecureRandom()

    /** Token to the release of the cache pin a file token holds from registration (D-04). Each release is one-shot. */
    private val tokenPins = ConcurrentHashMap<String, () -> Unit>()

    /** Windows read ahead for `direct` tokens (plan 03-20); bounded by [READ_AHEAD_TOTAL_BYTES]. */
    private val readAhead = ReadAheadStore(READ_AHEAD_TOTAL_BYTES)
    private val scans = ConcurrentHashMap<String, DirectScan>()

    /** Finished runs of video pipe tokens, for ranged GETs (plan 02-16); null when disabled or without a [bufferDir]. */
    private val rangeCache: PipeRangeCache? =
        if (pipeRanges.enabled && bufferDir != null) PipeRangeCache(File(bufferDir, "range-cache"), pipeRanges) else null

    fun start() {
        check(server == null) { "already started" }
        rangeCache?.clear() // files of an earlier run are never reused
        files?.start() // keeps the finished files of an earlier run, drops half-written ones (D-12)
        startEngine(host, port)
    }

    /**
     * Binds and starts the engine for [start] and [rebindHost]. A busy address throws [BindException] to the caller
     * (CR-01 b): Ktor CIO binds inside its accept job, where a failure is uncaught and ends the process, so the address
     * is probed first (reuseAddress, as for the real bind: a restart must not hit EADDRINUSE from TIME_WAIT) and the
     * engine gets a supervisor that records a failure that slips past the probe. A failed call leaves [server] untouched.
     */
    private fun startEngine(bindHost: String, bindPort: Int) {
        if (bindPort != 0) requireBindable(bindHost, bindPort)
        val asyncFailure = AtomicReference<Throwable?>(null)
        val handler = CoroutineExceptionHandler { _, e -> asyncFailure.compareAndSet(null, e) }
        val s = embeddedServer(CIO, serverConfig {
            parentCoroutineContext = SupervisorJob() + handler
            module { configure(this) }
        }) {
            reuseAddress = true
            connectors.add(
                EngineConnectorBuilder().apply {
                    host = bindHost
                    port = bindPort
                },
            )
        }
        try {
            s.start(wait = false)
            boundPort = runBlocking { s.engine.resolvedConnectors().first().port }
        } catch (e: Throwable) {
            runCatching { s.stop(0, 0) }
            val cause = asyncFailure.get() ?: e
            throw cause as? BindException
                ?: BindException("media server cannot bind $bindHost:$bindPort: ${cause.message}").also { it.initCause(cause) }
        }
        server = s
    }

    private fun requireBindable(bindHost: String, bindPort: Int) {
        ServerSocket().use { probe ->
            probe.reuseAddress = true
            try {
                probe.bind(InetSocketAddress(bindHost, bindPort))
            } catch (e: BindException) {
                throw BindException("media port $bindHost:$bindPort is busy: ${e.message}").also { it.initCause(e) }
            }
        }
    }

    /**
     * Moves the listener to [newHost] on the port it already has (plan 05-07, SVC-04). Only the HTTP engine is restarted:
     * the registered tokens, the pins on cached files, the range cache and the file builder are kept, so a stream URL a
     * speaker was given before the move still answers after it. [stop] is never used to rebind, because it clears the range
     * cache, releases every pin and closes the file builder, which would 404 the track that is playing. A bind failure
     * propagates and leaves the server not listening ([isRunning] stays true, so the call can be repeated).
     */
    fun rebindHost(newHost: String) {
        check(server != null) { "not started" }
        val keepPort = boundPort
        // [server] keeps pointing at the stopped engine until the new one is up, so a failed bind can be retried
        server?.stop(200, 1000)
        host = newHost
        startEngine(newHost, keepPort)
    }

    fun stop() {
        server?.stop(200, 1000)
        server = null
        rangeCache?.clear()
        releaseAllTokenPins()
        files?.close()
    }

    fun isRunning(): Boolean = server != null

    /** Node `clearCache`: deletes every cached built and source file and returns how many went. */
    fun clearCache(): Int = files?.clear() ?: 0

    /** Node `cacheInfo`: the number and size of cached files and the cap; null without a file builder. */
    fun cacheInfo(): CacheInfo? = files?.info()

    /** How many file builds and source downloads run now; 0 without a file builder. The yt-dlp updater counts them as busy. */
    fun activeBuilds(): Int = files?.activeCount() ?: 0

    fun resolvedPort(): Int = boundPort

    fun tokenCount(): Int = synchronized(tokens) { tokens.size }

    /** What the range cache holds now (zeros when range answering is off or there is no buffer directory). */
    fun rangeCacheStats(): PipeRangeStats = rangeCache?.stats() ?: PipeRangeStats(0, 0)

    /**
     * Registers [entry] and returns the path to hand to a renderer: `/stream/<token>.<ext>`. The token is 16
     * bytes from SecureRandom as 32 lowercase hex characters; at most [MAX_TOKENS] stay registered, the
     * oldest is evicted first (Node MAX_TOKENS).
     */
    fun register(entry: StreamEntry, ext: String = extensionOf(entry.format)): String {
        val raw = ByteArray(16).also(random::nextBytes)
        val token = raw.joinToString("") { "%02x".format(it) }
        val evicted = ArrayList<String>()
        synchronized(tokens) {
            tokens[token] = entry
            while (tokens.size > MAX_TOKENS) {
                val oldest = tokens.keys.first()
                tokens.remove(oldest)
                evicted += oldest
            }
        }
        evicted.forEach {
            rangeCache?.drop(it) // a token that left the table takes its cached bytes with it
            dropReadAhead(it, forget = true)
            releaseTokenPin(it) // and its file may be evicted from the cache again (D-04)
        }
        return "/stream/$token.$ext"
    }

    /** Drops what is held in memory for the token of [path] and lets the cache evict its file; see [MediaRegistrar.release]. */
    override fun release(path: String) {
        val token = TOKEN_PATH.matchEntire(path.substringBefore('?'))?.groupValues?.get(1) ?: return
        dropReadAhead(token, forget = false)
        releaseTokenPin(token)
    }

    private fun releaseTokenPin(token: String) {
        tokenPins.remove(token)?.invoke()
    }

    private fun releaseAllTokenPins() {
        for (token in tokenPins.keys.toList()) releaseTokenPin(token)
    }

    /** The bytes held for read-ahead now, windows being fetched included (never more than [READ_AHEAD_TOTAL_BYTES]). */
    internal fun readAheadHeldBytes(): Long = readAhead.heldBytes()

    private fun dropReadAhead(token: String, forget: Boolean) {
        scans[token]?.epoch?.incrementAndGet() // a fetch in flight must not install its window afterwards
        readAhead.drop(token)
        if (forget) scans.remove(token)
    }

    override fun cachedFormats(videoId: String): List<String> =
        runCatching { files?.cachedFormats(videoId) }.getOrNull().orEmpty()

    override suspend fun prepare(entry: VideoEntry) {
        if (entry.format !in FILE_FORMATS) return
        val builder = files ?: throw IllegalStateException("no file builder: format ${entry.format} cannot be prepared")
        // RESEARCH Pitfall 6: the total build time is bounded apart from the speaker's start clock. Giving up only
        // stops waiting: the builder's shared job lives in its own scope, so a later prepare joins it.
        val done = withTimeoutOrNull(prepareTimeoutMs) { builder.ensure(entry.videoId, entry.format, entry.startAt) }
        if (done == null) {
            throw IllegalStateException("preparing ${entry.format} took longer than ${prepareTimeoutMs / 1000} s")
        }
    }

    override fun registerVideo(entry: VideoEntry): String {
        requireVideoId(entry.videoId)
        if (entry.format == "direct") return registerDirect(entry)
        // Without a FileBuilder the file formats are not served at all: that stays the pipe-format refusal below.
        if (entry.format in FILE_FORMATS && files != null) return registerFile(entry)
        require(outputArgs(entry.format) != null) { "format ${entry.format} is not a pipe format (adts or mp3)" }
        require(entry.startAt >= 0) { "startAt must not be negative" }
        val mime = FORMATS.getValue(entry.format).mime ?: throw IllegalArgumentException("format ${entry.format} has no fixed MIME")
        return register(StreamEntry(StreamSource.VideoPipe(entry), entry.format, mime, entry.videoId))
    }

    /**
     * A file format: [prepare] normally built the file already, but a missing file does not fail the registration (D-14):
     * the token is registered with a rebuild path and the first request builds it. The token pins the file's name in the
     * cache until [release], token eviction or [stop] (D-04), so the playing and the prefetched file are never evicted.
     */
    private fun registerFile(entry: VideoEntry): String {
        require(entry.startAt >= 0) { "startAt must not be negative" }
        check(files != null) { "no file builder: format ${entry.format} cannot be served" }
        val builder: FileBuilder = files
        val file = builder.fileFor(entry.videoId, entry.format, entry.startAt)
            ?: builder.pathFor(entry.videoId, entry.format, entry.startAt)
        val mime = FORMATS.getValue(entry.format).mime ?: throw IllegalArgumentException("format ${entry.format} has no fixed MIME")
        val unpin = files.pin(file) // by name: a file that does not exist yet is pinned too
        val rebuild: suspend () -> File = { builder.ensure(entry.videoId, entry.format, entry.startAt) }
        val path = try {
            register(StreamEntry(StreamSource.FileSource(file, rebuild), entry.format, mime, entry.videoId))
        } catch (e: Throwable) {
            unpin()
            throw e
        }
        val token = TOKEN_PATH.matchEntire(path)?.groupValues?.get(1)
        if (token != null) tokenPins[token] = unpin else unpin()
        return path
    }

    private fun registerDirect(entry: VideoEntry): String {
        require(entry.startAt >= 0) { "startAt must not be negative" }
        val ext = entry.ext
        require(ext != null && DIRECT_EXT.matches(ext)) { "direct needs the source extension" }
        val mime = entry.sourceMime?.trim()?.lowercase()
        require(mime != null && DIRECT_MIME.matches(mime)) { "direct needs the source MIME type" }
        return register(StreamEntry(StreamSource.DirectProxy(entry), "direct", mime, entry.videoId), ext)
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

        /** The upstream calls of this request, for the log (direct only). */
        val upstream = java.util.concurrent.CopyOnWriteArrayList<UpstreamTiming>()
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
                is StreamSource.FileSource -> serveFile(call, entry, src.file, head, out, "file", src.rebuild)
                is StreamSource.PipeSource -> servePipe(call, entry, src, head, out, token)
                is StreamSource.VideoPipe -> {
                    if (!head) runCatching { src.entry.onRequest?.invoke() }
                    servePipe(call, entry, src, head, out, token)
                }
                is StreamSource.DirectProxy -> {
                    if (!head) runCatching { src.entry.onRequest?.invoke() }
                    serveDirect(call, entry, src, head, out, token)
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
                    upstream = out.upstream.takeIf { it.isNotEmpty() }?.joinToString("; "),
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
        rebuild: (suspend () -> File)? = null,
    ) {
        // Pinned while the response is written, so eviction and clearCache skip it (D-04, Node `#serveFile` pin).
        // Only files in the builder's directory are pinned; a HEAD answer releases at once. A rebuilt file has the same
        // name as the vanished one, so this pin covers it too.
        val unpin = files?.pin(file)
        try {
            respondFile(call, entry, file, head, out, pipeMode, rebuild)
        } finally {
            unpin?.invoke()
        }
    }

    private suspend fun respondFile(
        call: ApplicationCall,
        entry: StreamEntry,
        original: File,
        head: Boolean,
        out: Outcome,
        pipeMode: String,
        rebuild: (suspend () -> File)?,
    ) {
        out.pipeMode = pipeMode
        var file = original
        if (!file.exists()) {
            if (rebuild == null) {
                // Registered, then gone, and nothing to build it with: a plain 404, never an empty 200.
                out.status = 404
                out.note = "file vanished"
                call.respond(HttpStatusCode.NotFound)
                return
            }
            // D-03: evicted or reclaimed by Android since registration. Build it again (downloading the source again if
            // that is gone too) and serve it; the sender sees nothing. A failure answers 502 with a fixed note: no
            // exception text, which could carry a URL (T-04-08). FileBuilder coalesces per key and the wait is bounded (T-04-07).
            val rebuilt = try {
                withTimeoutOrNull(prepareTimeoutMs) { rebuild() }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (rebuilt == null || !rebuilt.exists()) {
                out.status = 502
                out.note = "rebuild failed"
                call.respond(HttpStatusCode.BadGateway)
                return
            }
            file = rebuilt
            out.note = "rebuilt"
        }
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

    // ------------------------------------------------------------------------------------ direct

    /**
     * mediaServer.js `#proxy`: the speaker's request is answered from YouTube's own audio URL. The URL comes only
     * from the resolver, never from the request (no open proxy, T-03-06), and the fetcher decides which hosts it
     * will talk to (T-03-05). The upstream URL is never logged or returned.
     *
     * Read-ahead (plan 03-20): from the second small bounded Range of a token (at most [READ_AHEAD_SCAN_MAX_BYTES]) the
     * answer comes from a window of [READ_AHEAD_WINDOW_BYTES] fetched from upstream starting at the requested offset, so
     * the Lamp's post-seek scan of 6144-byte ranges costs one upstream call per window instead of one per range. The bytes
     * a speaker receives are those upstream returns for its Range. Anything unexpected falls back to the request-per-request path.
     */
    private suspend fun serveDirect(
        call: ApplicationCall,
        entry: StreamEntry,
        src: StreamSource.DirectProxy,
        head: Boolean,
        out: Outcome,
        token: String,
    ) {
        out.pipeMode = "proxy"
        val r = resolver
        val up = upstream
        if (r == null || up == null) {
            out.status = 501
            out.note = if (r == null) "no resolver for video tokens" else "no upstream fetcher"
            call.respond(HttpStatusCode.NotImplemented)
            return
        }
        val id = src.entry.videoId
        val range = call.request.headers[HttpHeaders.Range]?.takeIf { it.length <= MAX_RANGE_HEADER && SIMPLE_RANGE.matches(it) }
        val span = if (head) null else range?.let { scanSpan(it) }
        if (span != null) {
            val state = scans.computeIfAbsent(token) { DirectScan() }
            if (state.scans.incrementAndGet() > READ_AHEAD_AFTER_SCANS && !state.disabled) {
                val window = try {
                    windowFor(id, token, state, span, out, r, up)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Nothing was sent yet: the plain path below gives the speaker its answer.
                    state.disabled = true
                    out.note = "read-ahead off for this stream: ${e.javaClass.simpleName}"
                    null
                }
                if (window != null) {
                    respondFromWindow(call, entry, window, span, out)
                    return
                }
            }
        }
        var resp: UpstreamResponse? = null
        var timing: UpstreamTiming? = null
        var startedNanos = 0L
        try {
            var retried = false
            while (true) {
                val info = r.resolve(id)
                // The speaker's Range is the only thing of its request that goes upstream (T-03-06).
                val headers = LinkedHashMap<String, String>(info.headers)
                headers["Accept-Encoding"] = "identity"
                if (range != null) headers["Range"] = range
                val t0 = System.nanoTime()
                val fetched = up.fetch(UpstreamRequest(info.url, if (head) "HEAD" else "GET", headers))
                val first = UpstreamTiming("proxy", fetched.status, elapsedMs(t0))
                out.upstream.add(first)
                // Signed googlevideo URLs expire or get revoked: drop the cached resolve, resolve again, fetch once more.
                if ((fetched.status == 403 || fetched.status == 410) && !retried) {
                    fetched.close()
                    first.totalMs = first.ttfbMs
                    retried = true
                    r.invalidate(id)
                    continue
                }
                resp = fetched
                timing = first
                startedNanos = t0
                break
            }
            relayDirect(call, entry, resp, head, out, timing, startedNanos)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (out.status != 0) {
                // The answer was already on its way: the transfer ended early. Say so and let the connection drop.
                out.note = failureLine("direct", "transfer ended: ${e.message ?: e.javaClass.simpleName}", false)
                throw e
            }
            out.status = 502
            out.note = failureLine("direct", e.message ?: e.javaClass.simpleName, false)
            call.respond(HttpStatusCode.BadGateway)
        } finally {
            resp?.close()
        }
    }

    /** A bounded `bytes=a-b` of at most [READ_AHEAD_SCAN_MAX_BYTES] bytes, else null. */
    private fun scanSpan(range: String): LongRange? {
        val m = BOUNDED_RANGE.matchEntire(range) ?: return null
        val a = m.groupValues[1].toLongOrNull() ?: return null
        val b = m.groupValues[2].toLongOrNull() ?: return null
        if (b < a || b - a + 1 > READ_AHEAD_SCAN_MAX_BYTES) return null
        return a..b
    }

    private fun elapsedMs(startNanos: Long): Long = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos)

    /**
     * The window that holds [span]: the token's held one, or a new one fetched from upstream starting at the span. Null when
     * the plain path must answer (read-ahead refused or failed, a start past the end, an error answer from upstream).
     */
    private suspend fun windowFor(
        id: String,
        token: String,
        state: DirectScan,
        span: LongRange,
        out: Outcome,
        r: VideoResolver,
        up: UpstreamFetcher,
    ): ReadAheadWindow? = state.mutex.withLock {
        val held = readAhead.get(token)
        if (held != null && span.first >= held.start && span.first < held.total && minOf(span.last, held.total - 1) <= held.end) {
            out.upstream.add(UpstreamTiming("window hit"))
            held
        } else {
            fetchWindow(id, token, state, span.first, out, r, up)
        }
    }

    /** 206 for [span] (its end clamped to the file, as upstream does) cut from [w]; the same headers the plain path passes on. */
    private suspend fun respondFromWindow(call: ApplicationCall, entry: StreamEntry, w: ReadAheadWindow, span: LongRange, out: Outcome) {
        val first = span.first
        val last = minOf(span.last, w.total - 1)
        val length = (last - first + 1).toInt()
        val from = (first - w.start).toInt()
        val body = w.bytes.copyOfRange(from, from + length)
        dlnaHeaders(call, entry)
        call.response.header(HttpHeaders.AcceptRanges, "bytes")
        call.response.header(HttpHeaders.ContentRange, "bytes $first-$last/${w.total}")
        w.lastModified?.let { call.response.header(HttpHeaders.LastModified, it) }
        w.etag?.let { call.response.header(HttpHeaders.ETag, it) }
        out.status = HttpStatusCode.PartialContent.value
        out.bytes = length.toLong()
        val type = ContentType.parse(entry.mime)
        call.respond(
            object : OutgoingContent.ByteArrayContent() {
                override val status: HttpStatusCode = HttpStatusCode.PartialContent
                override val contentType: ContentType = type
                override val contentLength: Long = length.toLong()

                override fun bytes(): ByteArray = body
            },
        )
    }

    /**
     * One upstream request for [READ_AHEAD_WINDOW_BYTES] bytes from [start]. The window is installed only when upstream
     * answered 206 with a Content-Range that starts at [start], a known total and exactly the bytes it names; a 200 (Range
     * ignored) or anything else turns read-ahead off for the token and returns null. A 403 or 410 drops the token's windows
     * before the one re-resolve (T-03-53). Only a Range goes upstream besides the resolver's headers (T-03-06).
     */
    private suspend fun fetchWindow(
        id: String,
        token: String,
        state: DirectScan,
        start: Long,
        out: Outcome,
        r: VideoResolver,
        up: UpstreamFetcher,
    ): ReadAheadWindow? {
        val want = READ_AHEAD_WINDOW_BYTES.toLong()
        if (!readAhead.reserve(token, want)) return null
        val epoch = state.epoch.get()
        var window: ReadAheadWindow? = null
        try {
            var retried = false
            while (true) {
                val info = r.resolve(id)
                val headers = LinkedHashMap<String, String>(info.headers)
                headers["Accept-Encoding"] = "identity"
                headers["Range"] = "bytes=$start-${start + want - 1}"
                val t0 = System.nanoTime()
                val resp = up.fetch(UpstreamRequest(info.url, "GET", headers))
                val timing = UpstreamTiming("window", resp.status, elapsedMs(t0))
                out.upstream.add(timing)
                try {
                    if ((resp.status == 403 || resp.status == 410) && !retried) {
                        timing.totalMs = timing.ttfbMs
                        retried = true
                        readAhead.drop(token)
                        r.invalidate(id)
                        continue
                    }
                    if (resp.status == 416 || resp.status in 400..599) {
                        timing.totalMs = timing.ttfbMs
                        return null // the plain path passes the error on
                    }
                    val parsed = if (resp.status == 206) CONTENT_RANGE.matchEntire(resp.headers["content-range"].orEmpty().trim()) else null
                    val cs = parsed?.groupValues?.get(1)?.toLongOrNull()
                    val ce = parsed?.groupValues?.get(2)?.toLongOrNull()
                    val total = parsed?.groupValues?.get(3)?.toLongOrNull()
                    val body = resp.body
                    if (cs != start || ce == null || total == null || ce < start || ce - start + 1 > want || ce >= total || body == null) {
                        state.disabled = true
                        timing.totalMs = timing.ttfbMs
                        out.note = "read-ahead off for this stream: upstream answered ${resp.status} to a ${want / 1024} KiB range"
                        return null
                    }
                    val expected = (ce - start + 1).toInt()
                    val bytes = withContext(Dispatchers.IO) { body.readNBytes(expected) }
                    timing.totalMs = elapsedMs(t0)
                    timing.bytes = bytes.size.toLong()
                    if (bytes.size != expected) {
                        state.disabled = true
                        out.note = "read-ahead off for this stream: upstream sent ${bytes.size} of $expected bytes"
                        return null
                    }
                    window = ReadAheadWindow(start, bytes, total, resp.headers["last-modified"], resp.headers["etag"])
                    return window
                } finally {
                    resp.close()
                }
            }
        } finally {
            // Install only if the token's windows were not dropped while this fetch ran (release, eviction, a re-resolve).
            val keep = window?.takeIf { state.epoch.get() == epoch }
            readAhead.finish(token, want, keep)
        }
    }

    /** Copies the upstream status, selected headers and body to the speaker. [startedNanos] is when the upstream call began. */
    private suspend fun relayDirect(
        call: ApplicationCall,
        entry: StreamEntry,
        resp: UpstreamResponse,
        head: Boolean,
        out: Outcome,
        timing: UpstreamTiming,
        startedNanos: Long,
    ) {
        val status = HttpStatusCode.fromValue(resp.status)
        val success = resp.status in 200..299
        val upstreamLength = resp.headers["content-length"]?.toLongOrNull()?.takeIf { it >= 0 }
        // Errors (404, 416, a second 403) are passed on by status alone: no upstream body reaches the speaker.
        val length = if (success || head) upstreamLength else if (resp.status == 204 || resp.status == 304) null else 0L
        dlnaHeaders(call, entry)
        call.response.header(HttpHeaders.AcceptRanges, resp.headers["accept-ranges"] ?: "bytes")
        for (name in PASSTHROUGH_HEADERS) resp.headers[name.lowercase()]?.let { call.response.header(name, it) }
        out.status = status.value
        val type = ContentType.parse(entry.mime)
        val body = resp.body
        if (head || body == null || !success) {
            timing.totalMs = timing.ttfbMs
            call.respond(
                object : OutgoingContent.NoContent() {
                    override val status: HttpStatusCode = status
                    override val contentType: ContentType = type
                    override val contentLength: Long? = length
                },
            )
            return
        }
        call.respond(
            object : OutgoingContent.WriteChannelContent() {
                override val status: HttpStatusCode = status
                override val contentType: ContentType = type
                override val contentLength: Long? = upstreamLength

                override suspend fun writeTo(channel: ByteWriteChannel) {
                    try {
                        withContext(Dispatchers.IO) {
                            val buf = ByteArray(FILE_CHUNK)
                            while (true) {
                                val n = body.read(buf) // a failing upstream read propagates: the connection drops and the speaker sees a short body
                                if (n < 0) break
                                try {
                                    channel.writeFully(buf, 0, n)
                                    channel.flush()
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Throwable) {
                                    out.note = "client closed: ${e.javaClass.simpleName}" // the speaker hung up; serveDirect closes the upstream
                                    break
                                }
                                out.bytes += n
                            }
                        }
                    } finally {
                        timing.totalMs = elapsedMs(startedNanos)
                        timing.bytes = out.bytes
                    }
                }
            },
        )
    }

    // ------------------------------------------------------------------------------------ pipe

    /** Output side of the ffmpeg command per format (mediaServer.js lines 123-124); null for non-pipe formats. */
    private fun outputArgs(format: String): List<String>? = when (format) {
        "adts" -> listOf("-c:a", "copy", "-f", "adts")
        "mp3" -> listOf("-c:a", "libmp3lame", "-b:a", mp3Bitrate, "-f", "mp3")
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
     * fresh extraction, and, when a [FileBuilder] is configured, finally lets yt-dlp download the track and feeds
     * ffmpeg the local file. A source that is already downloaded (or downloading) goes first, so YouTube is not asked.
     */
    private fun attemptsFor(src: StreamSource): List<PipeAttempt>? = when (src) {
        is StreamSource.PipeSource -> listOf(PipeAttempt("stream") { src.inputArgs })
        is StreamSource.VideoPipe -> resolver?.let { r ->
            val id = src.entry.videoId
            val startAt = src.entry.startAt
            val attempts = mutableListOf(
                PipeAttempt("YouTube URL") { urlInputArgs(r.resolve(id), startAt, ffmpegLogLevel) },
                PipeAttempt("a fresh extraction") {
                    r.invalidate(id)
                    urlInputArgs(r.resolve(id), startAt, ffmpegLogLevel)
                },
            )
            files?.let { f ->
                attempts += PipeAttempt("the yt-dlp download") { fileInputArgs(f.sourceFile(id).path, startAt, ffmpegLogLevel) }
                // Node moves the last attempt to the front when the source is there already.
                if (f.hasSource(id)) attempts.add(0, attempts.removeAt(attempts.lastIndex))
            }
            attempts
        }
        is StreamSource.FileSource, is StreamSource.DirectProxy -> null
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
            // No length is known, so the answer is chunked, and CIO writes the terminator (0 CRLF CRLF) after the headers
            // even though a HEAD answer has no body. On a kept-alive connection a client reads those bytes as the start
            // of its next response (JDK client: Invalid status line "0", plan 03-27). CIO cannot send this answer without
            // them (NoContent adds Content-Length: 0, which would say the track is empty; Transfer-Encoding is an
            // engine-only header), so the answer ends its connection and the client opens a new one for its GET.
            call.response.header(HttpHeaders.Connection, "close")
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
            // No finished run for this token (the speaker hung up the first fetch, or this request overtook it).
            // A resume-type Range is worth one bounded fill into a temp file: then 206 with the true total.
            if (cached == null && isResumeRange(rangeHeader) && entry.format in COLD_FILL_FORMATS && !cache.isOversize(token)) {
                val filled = coldFill(cache, token, attempts, l, tail, out)
                if (filled != null) {
                    out.note = "range $rangeHeader of ${filled.size} bytes from a bounded fill"
                    serveFile(call, entry, filled.file, false, out, "range-fill")
                    return
                }
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

    /**
     * Runs the attempt ladder once with ffmpeg writing straight into a part file and registers the result, or
     * returns null so the caller falls back to the live path (which then produces the usual 200 or 502). Bounded
     * three ways (threat T-02-56, T-02-57): the file may not pass the per-token cap (the token is then remembered as
     * too big), the whole fill may not pass [PipeRangeConfig.coldFillTimeoutMs], and a cancelled request kills it.
     * The cache also limits how many part files exist at once, so a fill may be refused outright.
     *
     * Only formats whose file output is byte-identical to their pipe output may be filled ([COLD_FILL_FORMATS]);
     * the offset the speaker holds indexes the pipe bytes. The adts equality is measured with ffmpeg 4.4.2 only;
     * the phone's ffmpeg 7.1.1 is checked in the device retest by comparing a fill's total with a plain GET.
     */
    private suspend fun coldFill(
        cache: PipeRangeCache,
        token: String,
        attempts: List<PipeAttempt>,
        l: PipeLauncher,
        outputArgs: List<String>,
        out: Outcome,
    ): CachedStream? {
        val part = withContext(Dispatchers.IO) { cache.newPartFile() } ?: return null
        var handedOver = false
        try {
            for (attempt in attempts) {
                val input = try {
                    attempt.input()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    continue // the live path that follows a failed fill records the failure
                }
                if (!currentCoroutineContext().isActive) return null
                withContext(Dispatchers.IO) { runCatching { part.writeBytes(ByteArray(0)) } } // a retry starts from an empty file
                val proc = withContext(Dispatchers.IO) { l.start(listOf("-y") + input + outputArgs + part.absolutePath) }
                try {
                    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(pipeRanges.coldFillTimeoutMs)
                    while (true) {
                        val exited = withContext(Dispatchers.IO) { proc.waitFor(FILL_POLL_MS, TimeUnit.MILLISECONDS) }
                        if (exited) break
                        if (part.length() > pipeRanges.maxBytesPerToken) {
                            cache.markOversize(token)
                            out.note = "range fill passed the size cap"
                            return null
                        }
                        if (System.nanoTime() > deadline) {
                            out.note = "range fill passed the ${pipeRanges.coldFillTimeoutMs} ms budget"
                            return null
                        }
                    }
                    if (proc.exitValue() != 0 || part.length() == 0L) continue
                    if (part.length() > pipeRanges.maxBytesPerToken) {
                        cache.markOversize(token)
                        return null
                    }
                } finally {
                    killAndWait(proc)
                }
                handedOver = true
                return withContext(Dispatchers.IO) { cache.promote(token, part) }
            }
            return null
        } finally {
            if (!handedOver) withContext(NonCancellable + Dispatchers.IO) { cache.discard(part) }
        }
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
        private const val FILL_POLL_MS = 50L

        /** Formats a cold token may be filled for: their file output equals their pipe output (see [coldFill]). mp3's does not. */
        private val COLD_FILL_FORMATS = setOf("adts")
        private const val BUFFER_TIMEOUT_SEC = 180L
        private const val MAX_NOTE = 400
        /** One `bytes=a-b` range with either side optional (not both), at most 64 characters. */
        private const val MAX_RANGE_HEADER = 64
        private val SIMPLE_RANGE = Regex("bytes=(?:\\d+-\\d*|-\\d+)")
        private val BOUNDED_RANGE = Regex("bytes=(\\d+)-(\\d+)")
        private val CONTENT_RANGE = Regex("bytes (\\d+)-(\\d+)/(\\d+)")
        private val DIRECT_EXT = Regex("[a-z0-9]{1,5}")
        private val DIRECT_MIME = Regex("[a-z0-9][a-z0-9.+-]*/[a-z0-9][a-z0-9.+-]*")

        /** Upstream headers that go to the speaker as they are (mediaServer.js PASSTHROUGH_HEADERS). */
        private val PASSTHROUGH_HEADERS = listOf(HttpHeaders.ContentRange, HttpHeaders.LastModified, HttpHeaders.ETag)
        private val TOKEN_PATH = Regex("^/stream/([0-9a-f]{32})\\.\\w+$")
    }
}
