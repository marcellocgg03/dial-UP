package dev.t2u.media

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** WR-03: a yt-dlp lookup has its own deadline, every coalesced waiter fails with it, and the resolver is not wedged afterwards. */
class CachingResolverTimeoutTest {
    private val id = "aaaaaaaaaaa"
    private val okJson =
        """{"url":"https://rr1.googlevideo.com/videoplayback?itag=140&sig=SECRET","ext":"m4a","format_id":"140","title":"T","http_headers":{"User-Agent":"ua"},"duration":60}"""

    /** Blocks until its thread is interrupted (a hung yt-dlp) while [hang] is true, otherwise answers. */
    private class SwitchGateway(@Volatile var hang: Boolean, private val json: String) : YtDlpGateway {
        val calls = AtomicInteger()
        val entered = CountDownLatch(1)
        val interrupted = AtomicInteger()

        override fun run(call: YtDlpCall): GatewayResult {
            calls.incrementAndGet()
            if (hang) {
                entered.countDown()
                try {
                    CountDownLatch(1).await()
                } catch (e: InterruptedException) {
                    interrupted.incrementAndGet()
                    throw e
                }
            }
            return GatewayResult(0, json, "", 5)
        }

        override fun version(): String? = "fake"

        override fun update(channel: String): String = "DONE"
    }

    @Test
    fun coalescedWaitersAllFailWithTheTimeoutAndNothingStaysInFlight() = runBlocking<Unit> {
        val gw = SwitchGateway(hang = true, json = okJson)
        val events = CopyOnWriteArrayList<Map<String, Any?>>()
        val r = CachingResolver(gw, "/c/yt-dlp", onEvent = { events += it }, lookupTimeoutMs = 150)

        val a = async(Dispatchers.Default) { runCatching { r.resolve(id) } }
        assertTrue(gw.entered.await(5, TimeUnit.SECONDS))
        val b = async(Dispatchers.Default) { runCatching { r.resolve(id) } }

        for (res in listOf(a.await(), b.await())) {
            val e = res.exceptionOrNull()
            assertTrue(e is ResolveException, "got $e")
            assertTrue(e.message!!.contains("timed out"), e.message)
            assertTrue(e.message!!.contains(id), e.message)
            assertTrue(!e.message!!.contains("googlevideo"), "no URL in the message")
        }
        assertEquals(1, gw.calls.get(), "the two callers shared one lookup")
        assertEquals(1, gw.interrupted.get(), "the hung call was interrupted")
        // invokeOnCompletion runs on the lookup's thread; give it a moment
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (r.inFlightCount() != 0 && System.nanoTime() < until) Thread.sleep(10)
        assertEquals(0, r.inFlightCount())
        assertTrue(events.none { it["result"] == "hit" }, "a failed lookup is not cached")
    }

    @Test
    fun theResolverWorksAgainAfterATimedOutLookup() = runBlocking<Unit> {
        val gw = SwitchGateway(hang = true, json = okJson)
        val r = CachingResolver(gw, "/c/yt-dlp", lookupTimeoutMs = 100)
        assertFailsWith<ResolveException> { r.resolve(id) }
        gw.hang = false
        val info = r.resolve(id)
        assertEquals("m4a", info.ext)
        assertEquals(2, gw.calls.get(), "the failure was not cached")
    }

    @Test
    fun cancellingOneWaiterDoesNotCancelTheLookupTheOtherWaitsFor() = runBlocking<Unit> {
        val gw = SwitchGateway(hang = true, json = okJson)
        val r = CachingResolver(gw, "/c/yt-dlp", lookupTimeoutMs = 60_000)
        val first = async(Dispatchers.Default, start = CoroutineStart.DEFAULT) { r.resolve(id) }
        assertTrue(gw.entered.await(5, TimeUnit.SECONDS))
        val second = async(Dispatchers.Default) { r.resolve(id) }
        // the second caller has joined the running lookup once it is waiting on it
        Thread.sleep(100)
        first.cancel()
        assertFailsWith<CancellationException> { first.await() }
        assertEquals(1, r.inFlightCount(), "the lookup is still running for the other waiter")
        assertEquals(0, gw.interrupted.get())
        // an outer limit on the remaining waiter stays an outer cancellation, not a ResolveException
        assertFailsWith<kotlinx.coroutines.TimeoutCancellationException> { withTimeout(100) { second.await() } }
        second.cancel()
    }
}
