package dev.t2u.media

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Port of resolver.js resolve/invalidate/urlExpiry: cache, expiry, coalescing, ID refusal, no URL in events. */
class CachingResolverTest {
    private val now = 1_700_000_000_000L
    private val host = "rr1---sn-test.googlevideo.com"

    private fun json(url: String) =
        """{"url":"$url","ext":"m4a","format_id":"140","title":"T","http_headers":{"User-Agent":"ua"},"duration":60}"""

    private fun signedUrl(expire: Long?) =
        "https://$host/videoplayback?" + (if (expire != null) "expire=$expire&" else "") + "itag=140&sig=SECRET"

    private class FakeGateway(
        private val result: (YtDlpCall) -> GatewayResult,
        private val onRun: () -> Unit = {},
    ) : YtDlpGateway {
        val calls = AtomicInteger()
        val seen = CopyOnWriteArrayList<YtDlpCall>()

        override fun run(call: YtDlpCall): GatewayResult {
            calls.incrementAndGet()
            seen += call
            onRun()
            return result(call)
        }

        override fun version(): String? = "fake"

        override fun update(channel: String): String = "DONE"
    }

    private fun ok(url: String) = GatewayResult(0, json(url), "", 5)

    private fun resolverWith(gateway: FakeGateway, clock: AtomicLong, events: MutableList<Map<String, Any?>> = CopyOnWriteArrayList()) =
        CachingResolver(gateway, "/c/yt-dlp", { clock.get() }, { events += it })

    @Test
    @DisplayName("resolver rejects IDs that could be used for injection")
    fun resolverRejectsIdsThatCouldBeUsedForInjection() = runBlocking {
        val gw = FakeGateway({ ok(signedUrl(null)) })
        val r = resolverWith(gw, AtomicLong(now))
        for (id in listOf("abc; rm -rf /", "--exec=evil")) {
            val e = assertFailsWith<IllegalArgumentException>("must refuse <$id>") { r.resolve(id) }
            assertTrue(e.message!!.contains("invalid video ID"), e.message)
        }
        assertEquals(0, gw.calls.get())
    }

    @Test
    fun emptyShortLongAndShellShapedIdsAreRefusedWithoutAGatewayCall() = runBlocking {
        val gw = FakeGateway({ ok(signedUrl(null)) })
        val r = resolverWith(gw, AtomicLong(now))
        val bad = listOf(
            "", // empty
            "abcdefghij", // 10
            "abcdefghijkl", // 12
            "abc/defghij", // 11 with '/'
            "abc=defghij", // 11 with '='
            "abc defghij", // 11 with a space
            "abcdéfghijk", // 11 characters with a non-ASCII letter
            "abc;defghij", // 11 with ';'
        )
        for (id in bad) {
            val e = assertFailsWith<IllegalArgumentException>("must refuse <$id>") { r.resolve(id) }
            assertTrue(e.message!!.startsWith("Refusing invalid video ID"), e.message)
        }
        assertEquals(0, gw.calls.get())
    }

    @Test
    fun theSameIdInsideTheWindowRunsTheGatewayOnceAndInvalidateRunsItAgain() = runBlocking {
        val gw = FakeGateway({ ok(signedUrl(null)) })
        val r = resolverWith(gw, AtomicLong(now))
        val first = r.resolve("dQw4w9WgXcQ")
        val second = r.resolve("dQw4w9WgXcQ")
        assertEquals(1, gw.calls.get())
        assertEquals(first, second)
        r.invalidate("dQw4w9WgXcQ")
        r.resolve("dQw4w9WgXcQ")
        assertEquals(2, gw.calls.get())
        // the call carries the cache dir and the id only as the watch URL, after the options
        val call = gw.seen.first()
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", call.url)
        assertEquals("/c/yt-dlp", call.options.single { it.name == "--cache-dir" }.value)
        assertTrue(call.options.none { it.name == "-v" })
    }

    @Test
    fun anEntryIsServedUntilExpireMinusFiveMinutes() = runBlocking {
        val clock = AtomicLong(now)
        val gw = FakeGateway({ ok(signedUrl(now / 1000 + 600)) })
        val r = resolverWith(gw, clock)
        r.resolve("dQw4w9WgXcQ")
        clock.set(now + 290_000)
        r.resolve("dQw4w9WgXcQ")
        assertEquals(1, gw.calls.get(), "290 s in: still cached")
        clock.set(now + 301_000)
        r.resolve("dQw4w9WgXcQ")
        assertEquals(2, gw.calls.get(), "301 s in: past expire - 5 min, resolved again")
    }

    @Test
    fun aUrlWithoutExpireIsCachedForExactlyOneHour() = runBlocking {
        val clock = AtomicLong(now)
        val gw = FakeGateway({ ok(signedUrl(null)) })
        val r = resolverWith(gw, clock)
        r.resolve("dQw4w9WgXcQ")
        clock.set(now + 3_600_000 - 1)
        r.resolve("dQw4w9WgXcQ")
        assertEquals(1, gw.calls.get())
        clock.set(now + 3_600_000)
        r.resolve("dQw4w9WgXcQ")
        assertEquals(2, gw.calls.get())
    }

    @Test
    fun anExpireFarInTheFutureIsCappedAtOneHour() = runBlocking {
        val clock = AtomicLong(now)
        val gw = FakeGateway({ ok(signedUrl(now / 1000 + 6 * 3600)) })
        val r = resolverWith(gw, clock)
        r.resolve("dQw4w9WgXcQ")
        clock.set(now + 3_600_000 + 1)
        r.resolve("dQw4w9WgXcQ")
        assertEquals(2, gw.calls.get())
    }

    @Test
    fun urlExpiryMatchesTheNodeFunction() {
        assertEquals(now + 300_000, urlExpiry(signedUrl(now / 1000 + 600), now))
        assertEquals(now + 3_600_000, urlExpiry(signedUrl(null), now))
        assertEquals(now + 3_600_000, urlExpiry("https://h.example/v?expire=0", now))
        assertEquals(now + 3_600_000, urlExpiry("https://h.example/v?expire=-5", now))
        assertEquals(now + 3_600_000, urlExpiry("not a url ::", now))
        assertEquals(now + 3_600_000, urlExpiry(signedUrl(now / 1000 + 999_999), now))
        // an expire already in the past (minus the margin) lands in the past: never served from the cache
        assertTrue(urlExpiry(signedUrl(now / 1000 - 10), now) < now)
    }

    @Test
    fun twoConcurrentResolvesRunTheGatewayOnceAndGetTheSameResult() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val gw = FakeGateway({ ok(signedUrl(null)) }, onRun = {
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS)) { "gateway never released" }
        })
        val events = CopyOnWriteArrayList<Map<String, Any?>>()
        val r = resolverWith(gw, AtomicLong(now), events)
        val a = async(Dispatchers.Default) { r.resolve("dQw4w9WgXcQ") }
        assertTrue(entered.await(10, TimeUnit.SECONDS), "first lookup never reached the gateway")
        val b = async(Dispatchers.Default) { r.resolve("dQw4w9WgXcQ") }
        Thread.sleep(150) // let the second caller join the in-flight lookup
        release.countDown()
        val (ra, rb) = awaitAll(a, b)
        assertEquals(1, gw.calls.get())
        assertEquals(ra, rb)
        assertEquals(setOf("miss", "coalesced"), events.map { it["result"] }.toSet(), events.toString())
    }

    @Test
    fun aLookupThatWasInvalidatedWhileRunningIsNotCachedAndTheNextResolveDoesNotJoinIt() = runBlocking {
        val first = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val order = AtomicInteger()
        val gw = object : YtDlpGateway {
            override fun run(call: YtDlpCall): GatewayResult {
                val n = order.incrementAndGet()
                if (n == 1) {
                    first.countDown()
                    check(releaseFirst.await(10, TimeUnit.SECONDS)) { "first lookup never released" }
                }
                return ok("https://$host/videoplayback?itag=140&n=$n")
            }

            override fun version(): String? = "fake"

            override fun update(channel: String): String = "DONE"
        }
        val r = CachingResolver(gw, "/c/yt-dlp", { now })
        val stale = async(Dispatchers.Default) { r.resolve("dQw4w9WgXcQ") }
        assertTrue(first.await(10, TimeUnit.SECONDS), "first lookup never reached the gateway")
        r.invalidate("dQw4w9WgXcQ")
        assertEquals(1, r.inFlightCount(), "the detached lookup still counts while it runs")
        val fresh = r.resolve("dQw4w9WgXcQ")
        assertTrue(fresh.url.endsWith("n=2"), "a new lookup, not the one that was running: ${fresh.url.substringAfterLast('?')}")
        releaseFirst.countDown()
        assertTrue(stale.await().url.endsWith("n=1"), "its own callers still get its answer")
        awaitInFlight(r, 0)
        assertTrue(r.resolve("dQw4w9WgXcQ").url.endsWith("n=2"), "the late result did not replace the fresh one")
        assertEquals(2, order.get(), "served from the cache, no third lookup")
    }

    private fun awaitInFlight(r: CachingResolver, expected: Int) {
        // the entry is removed by the lookup's completion handler, a moment after the caller got its answer
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (r.inFlightCount() != expected && System.nanoTime() < deadline) Thread.sleep(10)
        assertEquals(expected, r.inFlightCount())
    }

    @Test
    fun inFlightCountIsOneWhileALookupRunsEvenForTwoCallersAndZeroAfterwards() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val gw = FakeGateway({ ok(signedUrl(null)) }, onRun = {
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS)) { "gateway never released" }
        })
        val r = resolverWith(gw, AtomicLong(now))
        assertEquals(0, r.inFlightCount())
        val a = async(Dispatchers.Default) { r.resolve("dQw4w9WgXcQ") }
        assertTrue(entered.await(10, TimeUnit.SECONDS), "lookup never reached the gateway")
        val b = async(Dispatchers.Default) { r.resolve("dQw4w9WgXcQ") }
        Thread.sleep(150) // the second caller joins the same lookup
        assertEquals(1, r.inFlightCount())
        release.countDown()
        awaitAll(a, b)
        awaitInFlight(r, 0)
    }

    @Test
    fun inFlightCountIsZeroAgainAfterAFailedLookup() = runBlocking {
        val gw = FakeGateway({ GatewayResult(1, "", "ERROR: nope", 5) })
        val r = resolverWith(gw, AtomicLong(now))
        assertFailsWith<ResolveException> { r.resolve("dQw4w9WgXcQ") }
        awaitInFlight(r, 0)
    }

    @Test
    fun aFailingGatewayThrowsWithoutTheFullUrlAndIsNotCached() = runBlocking {
        val stderr = "ERROR: unable to download https://$host/videoplayback?expire=1&sig=SECRET: HTTP Error 403"
        val gw = FakeGateway({ GatewayResult(1, "", stderr, 5) })
        val r = resolverWith(gw, AtomicLong(now))
        val e = assertFailsWith<ResolveException> { r.resolve("dQw4w9WgXcQ") }
        assertTrue(e.message!!.startsWith("yt-dlp failed for dQw4w9WgXcQ: "), e.message)
        assertTrue(e.message!!.contains("https://$host"), e.message)
        assertFalse(e.message!!.contains("SECRET") || e.message!!.contains("/videoplayback"), e.message)
        assertFailsWith<ResolveException> { r.resolve("dQw4w9WgXcQ") }
        assertEquals(2, gw.calls.get(), "a failed lookup must not be cached")
    }

    @Test
    fun aGatewayThatThrowsOrPrintsGarbageIsAResolveExceptionWithoutTheUrl() = runBlocking {
        val throwing = FakeGateway({ throw java.io.IOException("cannot run https://$host/videoplayback?sig=SECRET") })
        val e1 = assertFailsWith<ResolveException> { resolverWith(throwing, AtomicLong(now)).resolve("dQw4w9WgXcQ") }
        assertFalse(e1.message!!.contains("SECRET"), e1.message)

        val garbage = FakeGateway({ GatewayResult(0, "this is not json", "", 5) })
        assertFailsWith<ResolveException> { resolverWith(garbage, AtomicLong(now)).resolve("dQw4w9WgXcQ") }

        val noUrl = FakeGateway({ GatewayResult(0, """{"id":"x","requested_formats":[{"ext":"mp4"}]}""", "", 5) })
        val e3 = assertFailsWith<ResolveException> { resolverWith(noUrl, AtomicLong(now)).resolve("dQw4w9WgXcQ") }
        assertEquals("yt-dlp returned no direct URL", e3.message)
    }

    @Test
    fun theCacheKeepsAtMostOneHundredEntriesOldestFirst() = runBlocking {
        val gw = FakeGateway({ ok(signedUrl(null)) })
        val r = resolverWith(gw, AtomicLong(now))
        val ids = (0..100).map { "vid%08d".format(it) }
        ids.forEach { r.resolve(it) }
        assertEquals(101, gw.calls.get())
        r.resolve(ids.last())
        assertEquals(101, gw.calls.get(), "the newest entry is cached")
        r.resolve(ids[1])
        assertEquals(101, gw.calls.get(), "the second entry survived")
        r.resolve(ids.first())
        assertEquals(102, gw.calls.get(), "the first entry was evicted by the 101st")
    }

    @Test
    fun eventsCarryHostAndExpireButNeverTheUrl() = runBlocking {
        val events = CopyOnWriteArrayList<Map<String, Any?>>()
        val gw = FakeGateway({ ok(signedUrl(now / 1000 + 600)) })
        val r = resolverWith(gw, AtomicLong(now), events)
        r.resolve("dQw4w9WgXcQ") // miss
        r.resolve("dQw4w9WgXcQ") // hit
        val failing = resolverWith(FakeGateway({ GatewayResult(1, "", "ERROR: https://$host/videoplayback?sig=SECRET", 1) }), AtomicLong(now), events)
        assertFailsWith<ResolveException> { failing.resolve("dQw4w9WgXcQ") }
        assertEquals(listOf("miss", "hit", "fail"), events.map { it["result"] })
        for (e in events) {
            assertFalse(e.toString().contains("/videoplayback"), e.toString())
            assertFalse(e.toString().contains("SECRET"), e.toString())
            assertEquals("dQw4w9WgXcQ", e["id"])
        }
        assertEquals(host, events[0]["urlHost"])
        assertEquals(now / 1000 + 600, events[0]["expire"])
        assertNotEquals(null, events[0]["ms"])
    }
}
