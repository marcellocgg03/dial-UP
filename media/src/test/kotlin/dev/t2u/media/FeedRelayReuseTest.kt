package dev.t2u.media

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The feed keeps the URL it last got for a video until it is near its expiry, so a reconnect hours after the last lookup does
 * not wait for yt-dlp (the resolver's own cache forgets a URL after an hour). No ffmpeg: a scripted resolver and upstream.
 */
class FeedRelayReuseTest {
    private val hour = 3_600_000L

    /** Every lookup is a new URL that expires six hours after the (fake) time it was asked; the resolver keeps nothing. */
    private class ScriptedResolver(private val now: () -> Long) : VideoResolver {
        val resolves = AtomicInteger()

        override suspend fun resolve(videoId: String): ResolvedInfo {
            val n = resolves.incrementAndGet()
            val expire = now() / 1000 + 6 * 3600
            return ResolvedInfo("http://upstream.invalid/s?expire=$expire&n=$n", "m4a", "audio/mp4", emptyMap(), "t", "a", "", "", 60.0, 0, "140")
        }

        override fun invalidate(videoId: String) = Unit
    }

    /** Answers every fetch with 10 bytes, or the scripted [first] statuses before that. */
    private class ScriptedUpstream(private val first: List<Int> = emptyList()) : UpstreamFetcher {
        val urls = CopyOnWriteArrayList<String>()

        override suspend fun fetch(request: UpstreamRequest): UpstreamResponse {
            val status = first.getOrNull(urls.size) ?: 200
            urls += request.url
            return UpstreamResponse(status, mapOf("content-length" to "10"), if (status in 200..299) ByteArrayInputStream(ByteArray(10)) else null)
        }
    }

    private fun feed(relay: FeedRelay, block: suspend (suspend () -> Int) -> Unit) = testApplication {
        application {
            intercept(ApplicationCallPipeline.Call) { relay.serve(call, "fffffffffff", false, Outcome()) }
        }
        block { client.get("/x") { header(HttpHeaders.Range, "bytes=0-") }.status.value }
    }

    @Test
    @DisplayName("a reconnect two hours after the last lookup reuses the URL instead of asking yt-dlp again")
    fun aReconnectHoursLaterReusesTheUrl() {
        var now = 1_800_000_000_000L
        val resolver = ScriptedResolver { now }
        val upstream = ScriptedUpstream()
        val relay = FeedRelay(resolver, upstream, nowMs = { now }, refreshMarginMs = 10 * 60_000L, cutMarginMs = 3 * 60_000L)
        try {
            feed(relay) { get ->
                assertEquals(200, get())
                now += 2 * hour
                assertEquals(200, get())
                now += 2 * hour
                assertEquals(200, get())
            }
        } finally {
            relay.close()
        }
        assertEquals(1, resolver.resolves.get(), "one lookup served three requests over four hours")
        assertEquals(3, upstream.urls.size)
        assertEquals(1, upstream.urls.toSet().size, "the same URL each time")
    }

    @Test
    @DisplayName("a URL inside the cut margin is replaced by a new lookup before the answer starts")
    fun aUrlNearItsExpiryIsLookedUpAgain() {
        var now = 1_800_000_000_000L
        val resolver = ScriptedResolver { now }
        val upstream = ScriptedUpstream()
        val relay = FeedRelay(resolver, upstream, nowMs = { now }, refreshMarginMs = 10 * 60_000L, cutMarginMs = 3 * 60_000L)
        try {
            feed(relay) { get ->
                assertEquals(200, get())
                now += 6 * hour - 2 * 60_000L // two minutes before expiry, inside the three-minute margin
                assertEquals(200, get())
            }
        } finally {
            relay.close()
        }
        assertEquals(2, resolver.resolves.get())
        assertEquals(2, upstream.urls.toSet().size, "the second request used the new URL")
    }

    @Test
    @DisplayName("a URL that upstream refuses is forgotten and replaced once")
    fun aRefusedUrlIsReplaced() {
        var now = 1_800_000_000_000L
        val resolver = ScriptedResolver { now }
        val upstream = ScriptedUpstream(first = listOf(200, 403))
        val relay = FeedRelay(resolver, upstream, nowMs = { now }, refreshMarginMs = 10 * 60_000L, cutMarginMs = 3 * 60_000L)
        try {
            feed(relay) { get ->
                assertEquals(200, get())
                now += hour
                assertEquals(200, get()) // 403 on the remembered URL, then the retry on a fresh one
                assertEquals(200, get()) // and the fresh one is the one remembered now
            }
        } finally {
            relay.close()
        }
        assertEquals(2, resolver.resolves.get())
        assertTrue(upstream.urls.size == 4 && upstream.urls[2] == upstream.urls[3] && upstream.urls[0] != upstream.urls[2], "${upstream.urls}")
    }

    @Test
    @DisplayName("the log line says how long the lookup and each upstream answer took, and that a refused URL was retried")
    fun theOutcomeCarriesTheTimings() {
        val now = 1_800_000_000_000L
        val relay = FeedRelay(ScriptedResolver { now }, ScriptedUpstream(first = listOf(403)), nowMs = { now })
        val out = Outcome()
        try {
            testApplication {
                application { intercept(ApplicationCallPipeline.Call) { relay.serve(call, "fffffffffff", false, out) } }
                assertEquals(200, client.get("/x") { header(HttpHeaders.Range, "bytes=0-") }.status.value)
            }
        } finally {
            relay.close()
        }
        val kinds = out.upstream.map { it.kind }
        assertEquals(listOf("lookup", "feed", "fresh lookup", "feed retry"), kinds, out.upstream.joinToString("; "))
        assertEquals(403, out.upstream[1].status)
        assertTrue(out.upstream[1].ttfbMs >= 0 && out.upstream[1].totalMs >= 0, "the refused answer has its time")
        assertEquals(200, out.upstream[3].status)
        assertEquals(10L, out.upstream[3].bytes, "the answer that was served has its byte count")
        assertTrue(out.upstream[3].totalMs >= 0)
    }
}
