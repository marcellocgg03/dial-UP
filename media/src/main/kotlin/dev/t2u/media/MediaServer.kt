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
import io.ktor.utils.io.ByteWriteChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.net.BindException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Token media server, a port of reference/tube2upnp/src/mediaServer.js.
 *
 * Only `GET` and `HEAD` on `/stream/<32 lowercase hex>.<ext>` for a registered token are served; every other
 * path, method or token answers 404 (Node parity, and there is no route that takes a URL or a path, which
 * keeps the server from becoming an open proxy). Body lengths are set only through the
 * OutgoingContent content length: a Content-Length header written by hand is duplicated by Ktor CIO.
 *
 * [mp3Bitrate] and [ffmpegLogLevel] are the `mp3Bitrate` and `ffmpegLogLevel` config switches: the caller
 * passes values that already passed `Config.validate`, so only a whitelisted name or a `NNNk` shape reaches ffmpeg.
 *
 * [bufferDir] is where HTTP/1.0 clients get ffmpeg output buffered first (they cannot parse chunked framing).
 *
 * Ranged GETs on video pipe tokens: the first response is always the live chunked stream, with no
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

    /** True while an engine is bound and accepting; false before [start], after [stop] and after a [rebindHost] whose bind failed. */
    @Volatile private var listening = false

    @Volatile private var boundPort: Int = port
    private val tokens = LinkedHashMap<String, StreamEntry>()
    private val random = SecureRandom()

    /** Token to the release of the cache pin a file token holds from registration. Each release is one-shot. */
    private val tokenPins = ConcurrentHashMap<String, () -> Unit>()

    private val directRelay = DirectRelay(resolver, upstream)
    private val byteRanges = ByteRangeResponder()

    /** Finished runs of video pipe tokens, for ranged GETs; null when disabled or without a [bufferDir]. */
    private val rangeCache: PipeRangeCache? =
        if (pipeRanges.enabled && bufferDir != null) PipeRangeCache(File(bufferDir, "range-cache"), pipeRanges) else null

    suspend fun startSuspending() {
        check(server == null) { "already started" }
        rangeCache?.clear() // files of an earlier run are never reused
        files?.start() // keeps the finished files of an earlier run, drops half-written ones
        startEngine(host, port)
    }

    /**
     * Blocking form of [startSuspending], for tests and callers that are not in a coroutine. Production calls
     * [startSuspending]: a blocking start inside a coroutine would hold its dispatcher thread through the bind.
     */
    fun start() {
        runBlocking { startSuspending() }
    }

    /**
     * Binds and starts the engine for [startSuspending] and [rebindHostSuspending]. A busy address throws [BindException] to the caller:
     * Ktor CIO binds inside its accept job, where a failure is uncaught and ends the process, so the address
     * is probed first (reuseAddress, as for the real bind: a restart must not hit EADDRINUSE from TIME_WAIT) and the
     * engine gets a supervisor that records a failure that slips past the probe. A failed call leaves [server] untouched.
     */
    private suspend fun startEngine(bindHost: String, bindPort: Int) {
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
            boundPort = s.engine.resolvedConnectors().first().port
        } catch (e: Throwable) {
            runCatching { s.stop(0, 0) }
            val cause = asyncFailure.get() ?: e
            throw cause as? BindException
                ?: BindException("media server cannot bind $bindHost:$bindPort: ${cause.message}").also { it.initCause(cause) }
        }
        server = s
        listening = true
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
     * Moves the listener to [newHost] on the port it already has. Only the HTTP engine is restarted:
     * the registered tokens, the pins on cached files, the range cache and the file builder are kept, so a stream URL a
     * speaker was given before the move still answers after it. [stop] is never used to rebind, because it clears the range
     * cache, releases every pin and closes the file builder, which would 404 the track that is playing. A bind failure
     * propagates and leaves the server not listening ([isListening] is false, [isRunning] stays true, so the call can be repeated).
     */
    suspend fun rebindHostSuspending(newHost: String) {
        check(server != null) { "not started" }
        val keepPort = boundPort
        listening = false
        // [server] keeps pointing at the stopped engine until the new one is up, so a failed bind can be retried
        server?.stop(200, 1000)
        host = newHost
        startEngine(newHost, keepPort)
    }

    /** Blocking form of [rebindHostSuspending], for tests and callers that are not in a coroutine; production calls the suspend form. */
    fun rebindHost(newHost: String) {
        runBlocking { rebindHostSuspending(newHost) }
    }

    fun stop() {
        listening = false
        server?.stop(200, 1000)
        server = null
        rangeCache?.clear()
        releaseAllTokenPins()
        files?.close()
    }

    /** Only says the server object was started and not stopped. After a failed [rebindHostSuspending] it stays true; [isListening] is the truth about the socket. */
    fun isRunning(): Boolean = server != null

    /** True while the socket is bound and accepting. A housekeeping pass rebinds a running server that is not listening. */
    fun isListening(): Boolean = listening

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
            releaseTokenPin(it) // and its file may be evicted from the cache again
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
    internal fun readAheadHeldBytes(): Long = directRelay.heldBytes()

    private fun dropReadAhead(token: String, forget: Boolean) = directRelay.dropReadAhead(token, forget)

    override fun cachedFormats(videoId: String): List<String> =
        runCatching { files?.cachedFormats(videoId) }.getOrNull().orEmpty()

    override suspend fun prepare(entry: VideoEntry) {
        if (entry.format !in FILE_FORMATS) return
        val builder = files ?: throw IllegalStateException("no file builder: format ${entry.format} cannot be prepared")
        // The total build time is bounded apart from the speaker's start clock. Giving up only
        // stops waiting: the builder's shared job lives in its own scope, so a later prepare joins it.
        val done = withTimeoutOrNull(prepareTimeoutMs) { builder.ensure(entry.videoId, entry.format, entry.startAt) }
        if (done == null) {
            throw IllegalStateException("preparing ${entry.format} took longer than ${prepareTimeoutMs / 1000} s")
        }
    }

    override suspend fun prepareAndRegister(entry: VideoEntry): String {
        if (entry.format in FILE_FORMATS && files == null) {
            prepare(entry)
        }
        val path = registerVideo(entry)
        try {
            prepare(entry)
            return path
        } catch (e: Throwable) {
            release(path)
            throw e
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
     * A file format: [prepare] normally built the file already, but a missing file does not fail the registration:
     * the token is registered with a rebuild path and the first request builds it. The token pins the file's name in the
     * cache until [release], token eviction or [stop], so the playing and the prefetched file are never evicted.
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
        if (token != null) {
            tokenPins[token] = { builder.discardWhenUnused(file); unpin() }
        } else {
            unpin()
        }
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
                    val release = files?.sourcePin(src.entry.videoId)
                    try {
                        servePipe(call, entry, src, head, out, token)
                    } finally {
                        if (files?.hasSource(src.entry.videoId) == true) files.discardSourceWhenUnused(src.entry.videoId)
                        release?.invoke()
                    }
                }
                is StreamSource.DirectProxy -> {
                    if (!head) runCatching { src.entry.onRequest?.invoke() }
                    directRelay.serveDirect(call, entry, src, head, out, token)
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
        // Pinned while the response is written, so eviction and clearCache skip it (Node `#serveFile` pin).
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
            // Evicted or reclaimed by Android since registration. Build it again (downloading the source again if
            // that is gone too) and serve it; the sender sees nothing. A failure answers 502 with a fixed note: no
            // exception text, which could carry a URL. FileBuilder coalesces per key and the wait is bounded.
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
        byteRanges.respond(call, entry, file, head, out)
    }

    // ------------------------------------------------------------------------------------ direct

    // ------------------------------------------------------------------------------------ pipe

    /** Output side of the ffmpeg command per format (mediaServer.js lines 123-124); null for non-pipe formats. */
    private fun outputArgs(format: String): List<String>? = when (format) {
        "adts" -> listOf("-c:a", "copy", "-f", "adts")
        "mp3" -> listOf("-c:a", "libmp3lame", "-b:a", mp3Bitrate, "-f", "mp3")
        else -> null
    }

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

    private suspend fun servePipe(
        call: ApplicationCall,
        entry: StreamEntry,
        src: StreamSource,
        head: Boolean,
        out: Outcome,
        token: String,
    ) {
        out.pipeMode = "chunked"
        if (head) {
            respondPipeHead(call, entry, out)
            return
        }
        val l = launcher
        val tail = outputArgs(entry.format)
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
        // HTTP/1.0 clients cannot parse chunked framing and Ktor CIO answers them chunked:
        // give them a complete, length-delimited body instead.
        val dir = bufferDir
        if (call.request.httpVersion == "HTTP/1.0" && dir != null) {
            serveBuffered(call, entry, attempts, l, tail, dir, out)
            return
        }
        val cache = if (src is StreamSource.VideoPipe) rangeCache else null
        if (cache != null && serveFromRangeCache(call, entry, cache, token, attempts, l, tail, out)) return
        serveLive(call, entry, cache, token, attempts, l, tail, out)
    }

    /** Headers only, no process and no resolve (a HEAD must never start ffmpeg). */
    private suspend fun respondPipeHead(call: ApplicationCall, entry: StreamEntry, out: Outcome) {
        val type = ContentType.parse(entry.mime)
        dlnaHeaders(call, entry)
        // No length is known, so the answer is chunked, and CIO writes the terminator (0 CRLF CRLF) after the headers
        // even though a HEAD answer has no body. On a kept-alive connection a client reads those bytes as the start
        // of its next response (JDK client: Invalid status line "0"). CIO cannot send this answer without
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
    }

    /**
     * A ranged GET on a video token whose first run completed is answered from that run's bytes, before anything is
     * resolved or launched; a token with no finished run may get one bounded fill. A header parseRange rejects falls
     * through to the live path. True when the call was answered.
     */
    private suspend fun serveFromRangeCache(
        call: ApplicationCall,
        entry: StreamEntry,
        cache: PipeRangeCache,
        token: String,
        attempts: List<PipeAttempt>,
        l: PipeLauncher,
        tail: List<String>,
        out: Outcome,
    ): Boolean {
        val rangeHeader = call.request.headers[HttpHeaders.Range] ?: return false
        val cached = cache.lookup(token)
        if (cached != null && cached.file.exists() && parseRange(rangeHeader, cached.size) != null) {
            out.note = "range $rangeHeader of ${cached.size} bytes from the first run"
            serveFile(call, entry, cached.file, false, out, "cached-range")
            return true
        }
        // No finished run for this token (the speaker hung up the first fetch, or this request overtook it).
        // A resume-type Range is worth one bounded fill into a temp file: then 206 with the true total.
        if (cached == null && isResumeRange(rangeHeader) && entry.format in COLD_FILL_FORMATS && !cache.isOversize(token)) {
            val filled = coldFill(cache, token, attempts, l, tail, out)
            if (filled != null) {
                out.note = "range $rangeHeader of ${filled.size} bytes from a bounded fill"
                serveFile(call, entry, filled.file, false, out, "range-fill")
                return true
            }
        }
        return false
    }

    /** The attempt ladder for a live GET: the first attempt that produces a byte answers; 502 with every failure otherwise. */
    private suspend fun serveLive(
        call: ApplicationCall,
        entry: StreamEntry,
        cache: PipeRangeCache?,
        token: String,
        attempts: List<PipeAttempt>,
        l: PipeLauncher,
        tail: List<String>,
        out: Outcome,
    ) {
        val pipeTee = PipeTee(l)
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
            val failure = pipeTee.serve(call, entry, out, cache, token, input + tail + "pipe:1") ?: return
            failures += failureLine(attempt.name, failure, attempts.size > 1)
        }
        out.status = 502
        out.note = failures.joinToString(" | ").ifEmpty { "ffmpeg produced no output" }
        call.respond(HttpStatusCode.BadGateway)
    }

    /**
     * Runs the attempt ladder once with ffmpeg writing straight into a part file and registers the result, or
     * returns null so the caller falls back to the live path (which then produces the usual 200 or 502). Bounded
     * three ways (a cold fill must not exhaust disk, time or part files): the file may not pass the per-token cap
     * (the token is then remembered as too big), the whole fill may not pass [PipeRangeConfig.coldFillTimeoutMs], and a cancelled request kills it.
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
        private const val MP3_BITRATE = "192k"
        private const val FILL_POLL_MS = 50L

        /** Formats a cold token may be filled for: their file output equals their pipe output (see [coldFill]). mp3's does not. */
        private val COLD_FILL_FORMATS = setOf("adts")
        private const val BUFFER_TIMEOUT_SEC = 180L
        private val DIRECT_EXT = Regex("[a-z0-9]{1,5}")
        private val DIRECT_MIME = Regex("[a-z0-9][a-z0-9.+-]*/[a-z0-9][a-z0-9.+-]*")
        private val TOKEN_PATH = Regex("^/stream/([0-9a-f]{32})\\.\\w+$")
    }
}
