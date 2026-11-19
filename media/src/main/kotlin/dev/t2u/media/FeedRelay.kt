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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The source an ffmpeg pipe reads from, instead of YouTube's own URL: ffmpeg asks this server (over the phone's own address),
 * and the server asks YouTube. ffmpeg keeps its seeking and its reconnect, but a signed URL never lives longer than one
 * response here: every request resolves again (the resolver hands out a fresh URL once the old one is near its end), and a
 * response is ended [cutMarginMs] before its URL expires, so ffmpeg reconnects with a Range at the byte it stopped at and
 * the next request gets a fresh URL. The same video's next URL is looked up [refreshMarginMs] before the expiry, in the
 * background, so the reconnect does not wait for yt-dlp (about 17 s). The bytes of a format are the same for every URL
 * of it, so the join is exact.
 *
 * The URL comes only from the resolver, never from the request. Only a request from this phone is answered.
 */
internal class FeedRelay(
    private val resolver: VideoResolver,
    private val upstream: UpstreamFetcher,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val refreshMarginMs: Long = REFRESH_MARGIN_MS,
    private val cutMarginMs: Long = CUT_MARGIN_MS,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * The newest answer this relay got for each video. The resolver keeps a URL for at most an hour, but a feed reconnect can
     * come hours after the last lookup (ffmpeg only reads when the speaker has drained its pipe), and a lookup takes up to
     * 25 s while ffmpeg waits for the response headers under its 30 s read timeout. So a URL is reused here until it is
     * [cutMarginMs] from its expiry, whatever the resolver cache has dropped.
     */
    private val known = object : LinkedHashMap<String, ResolvedInfo>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ResolvedInfo>?) = size > MAX_KNOWN
    }

    /** Ends the background lookups. */
    fun close() {
        scope.cancel()
        synchronized(known) { known.clear() }
    }

    /** The remembered answer for [videoId] while its URL is further than the cut margin from expiring, else null. */
    private fun usable(videoId: String): ResolvedInfo? {
        val info = synchronized(known) { known[videoId] } ?: return null
        val expireMs = urlExpire(info.url)?.times(1000) ?: return null
        return info.takeIf { expireMs - nowMs() > cutMarginMs }
    }

    private suspend fun lookup(videoId: String): ResolvedInfo =
        (usable(videoId) ?: resolver.resolve(videoId)).also { remember(videoId, it) }

    /** A new lookup. [dropKnown] forgets the remembered URL first (a refused one); the background refresh keeps it until the new one lands. */
    private suspend fun lookupFresh(videoId: String, dropKnown: Boolean = true): ResolvedInfo {
        resolver.invalidate(videoId)
        if (dropKnown) synchronized(known) { known.remove(videoId) }
        return resolver.resolve(videoId).also { remember(videoId, it) }
    }

    private fun remember(videoId: String, info: ResolvedInfo) {
        synchronized(known) { known[videoId] = info }
    }

    /** Answers one GET or HEAD of [videoId]; ffmpeg's `-headers` are not read, the resolver's headers go upstream. */
    suspend fun serve(call: ApplicationCall, videoId: String, head: Boolean, out: Outcome) {
        out.pipeMode = "feed"
        val range = call.request.headers[HttpHeaders.Range]?.takeIf { it.length <= MAX_RANGE_HEADER && SIMPLE_RANGE.matches(it) }
        var resp: UpstreamResponse? = null
        var refresh: Job? = null
        try {
            var info = lookup(videoId)
            // A URL already inside the cut margin gets one fresh lookup first, so the answer is not cut at once.
            var expireMs = urlExpire(info.url)?.times(1000)
            if (expireMs != null && expireMs - nowMs() <= cutMarginMs) {
                info = lookupFresh(videoId)
                expireMs = urlExpire(info.url)?.times(1000)
            }
            var retried = false
            while (true) {
                val headers = LinkedHashMap<String, String>(info.headers)
                headers["Accept-Encoding"] = "identity"
                if (range != null) headers["Range"] = range
                val fetched = upstream.fetch(UpstreamRequest(info.url, if (head) "HEAD" else "GET", headers))
                out.upstream.add(UpstreamTiming("feed", fetched.status, 0))
                if ((fetched.status == 403 || fetched.status == 410) && !retried) {
                    fetched.close()
                    retried = true
                    info = lookupFresh(videoId)
                    expireMs = urlExpire(info.url)?.times(1000)
                    continue
                }
                resp = fetched
                break
            }
            val success = resp.status in 200..299
            val length = resp.headers["content-length"]?.toLongOrNull()?.takeIf { it >= 0 }
            call.response.header(HttpHeaders.AcceptRanges, resp.headers["accept-ranges"] ?: "bytes")
            resp.headers[HttpHeaders.ContentRange.lowercase()]?.let { call.response.header(HttpHeaders.ContentRange, it) }
            out.status = resp.status
            val body = resp.body
            val type = ContentType.parse(info.mime.ifBlank { "application/octet-stream" })
            if (head || body == null || !success) {
                call.respond(
                    object : OutgoingContent.NoContent() {
                        override val status: HttpStatusCode = HttpStatusCode.fromValue(resp.status)
                        override val contentType: ContentType = type
                        override val contentLength: Long? = if (success || head) length else 0L
                    },
                )
                return
            }
            val cutAtMs = expireMs?.minus(cutMarginMs)
            if (expireMs != null) refresh = scheduleRefresh(videoId, expireMs - refreshMarginMs)
            val status = HttpStatusCode.fromValue(resp.status)
            call.respond(
                object : OutgoingContent.WriteChannelContent() {
                    override val status: HttpStatusCode = status
                    override val contentType: ContentType = type
                    override val contentLength: Long? = length

                    override suspend fun writeTo(channel: ByteWriteChannel) {
                        withContext(Dispatchers.IO) {
                            val buf = ByteArray(FILE_CHUNK)
                            while (true) {
                                if (cutAtMs != null && nowMs() >= cutAtMs) {
                                    out.note = "feed ended before the URL expires"
                                    break
                                }
                                val n = body.read(buf)
                                if (n < 0) break
                                try {
                                    channel.writeFully(buf, 0, n)
                                    channel.flush()
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Throwable) {
                                    out.note = "client closed: ${e.javaClass.simpleName}"
                                    break
                                }
                                out.bytes += n
                            }
                        }
                    }
                },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (out.status != 0) {
                out.note = failureLine("feed", "transfer ended: ${e.message ?: e.javaClass.simpleName}", false)
                throw e
            }
            out.status = 502
            out.note = failureLine("feed", e.message ?: e.javaClass.simpleName, false)
            call.respond(HttpStatusCode.BadGateway)
        } finally {
            refresh?.cancel()
            resp?.close()
        }
    }

    /** One background lookup at [atMs]: drop the cached URL and resolve again, so the answer after the cut is already there. */
    private fun scheduleRefresh(videoId: String, atMs: Long): Job = scope.launch {
        val wait = atMs - nowMs()
        if (wait > 0) delay(wait)
        runCatching { lookupFresh(videoId, dropKnown = false) }
    }

    companion object {
        /** The next URL is looked up this long before the current one expires. */
        const val REFRESH_MARGIN_MS = 10 * 60_000L

        /** A response ends this long before its URL expires. */
        const val CUT_MARGIN_MS = 3 * 60_000L

        private const val MAX_KNOWN = 8

        private const val MAX_RANGE_HEADER = 64
        private val SIMPLE_RANGE = Regex("bytes=(?:\\d+-\\d*|-\\d+)")
    }
}
