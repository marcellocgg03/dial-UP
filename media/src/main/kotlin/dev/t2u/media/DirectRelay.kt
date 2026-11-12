package dev.t2u.media

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * The direct proxy: answers a `direct` token from YouTube's own audio URL, request by request, with the read-ahead
 * windows and the scan detection that keep a speaker's post-seek scan from costing one upstream call per range.
 * Owns the read-ahead store and the per-token scan state.
 */
internal class DirectRelay(
    private val resolver: VideoResolver?,
    private val upstream: UpstreamFetcher?,
) {
    /** Windows read ahead for `direct` tokens; bounded by [READ_AHEAD_TOTAL_BYTES]. */
    private val readAhead = ReadAheadStore(READ_AHEAD_TOTAL_BYTES)
    private val scans = ConcurrentHashMap<String, DirectScan>()

    /** The bytes held for read-ahead now, windows being fetched included (never more than [READ_AHEAD_TOTAL_BYTES]). */
    fun heldBytes(): Long = readAhead.heldBytes()

    fun dropReadAhead(token: String, forget: Boolean) {
        scans[token]?.epoch?.incrementAndGet() // a fetch in flight must not install its window afterwards
        readAhead.drop(token)
        if (forget) scans.remove(token)
    }

    /**
     * mediaServer.js `#proxy`: the speaker's request is answered from YouTube's own audio URL. The URL comes only
     * from the resolver, never from the request (no open proxy), and the fetcher decides which hosts it
     * will talk to. The upstream URL is never logged or returned.
     *
     * Read-ahead: from the second small bounded Range of a token (at most [READ_AHEAD_SCAN_MAX_BYTES]) the
     * answer comes from a window of [READ_AHEAD_WINDOW_BYTES] fetched from upstream starting at the requested offset, so
     * the Lamp's post-seek scan of 6144-byte ranges costs one upstream call per window instead of one per range. The bytes
     * a speaker receives are those upstream returns for its Range. Anything unexpected falls back to the request-per-request path.
     */
    suspend fun serveDirect(
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
        if (span != null && answerFromWindow(call, entry, id, token, span, out, r, up)) return
        relayPerRequest(call, entry, id, range, head, out, r, up)
    }

    /** True when read-ahead answered [span]. A failed read-ahead turns itself off for the token and leaves the answer to the plain path. */
    private suspend fun answerFromWindow(
        call: ApplicationCall,
        entry: StreamEntry,
        id: String,
        token: String,
        span: LongRange,
        out: Outcome,
        r: VideoResolver,
        up: UpstreamFetcher,
    ): Boolean {
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
                return true
            }
        }
        return false
    }

    /** The request-per-request path: one upstream fetch (one re-resolve after a 403 or 410) relayed to the speaker. */
    private suspend fun relayPerRequest(
        call: ApplicationCall,
        entry: StreamEntry,
        id: String,
        range: String?,
        head: Boolean,
        out: Outcome,
        r: VideoResolver,
        up: UpstreamFetcher,
    ) {
        var resp: UpstreamResponse? = null
        var timing: UpstreamTiming? = null
        var startedNanos = 0L
        try {
            var retried = false
            while (true) {
                val info = r.resolve(id)
                // The speaker's Range is the only thing of its request that goes upstream.
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
     * before the one re-resolve. Only a Range goes upstream besides the resolver's headers.
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
                                // a failing upstream read propagates: the connection drops and the speaker sees a short body
                                val n = body.read(buf)
                                if (n < 0) break
                                try {
                                    channel.writeFully(buf, 0, n)
                                    channel.flush()
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Throwable) {
                                    // the speaker hung up; serveDirect closes the upstream
                                    out.note = "client closed: ${e.javaClass.simpleName}"
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

    private companion object {
        /** One `bytes=a-b` range with either side optional (not both), at most 64 characters. */
        const val MAX_RANGE_HEADER = 64
        val SIMPLE_RANGE = Regex("bytes=(?:\\d+-\\d*|-\\d+)")
        val BOUNDED_RANGE = Regex("bytes=(\\d+)-(\\d+)")
        val CONTENT_RANGE = Regex("bytes (\\d+)-(\\d+)/(\\d+)")

        /** Upstream headers that go to the speaker as they are (mediaServer.js PASSTHROUGH_HEADERS). */
        val PASSTHROUGH_HEADERS = listOf(HttpHeaders.ContentRange, HttpHeaders.LastModified, HttpHeaders.ETag)
    }
}
