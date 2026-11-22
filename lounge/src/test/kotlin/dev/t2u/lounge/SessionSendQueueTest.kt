package dev.t2u.lounge

// The send queue on its own, on virtual time: the retry in place after a network error (D-01), the budget, the HTTP refusal
// route, the free send lock and the end during a wait (D-02). Session-level behaviour is in SessionTest.

import dev.t2u.upnp.EventSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.Random
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
internal class SessionSendQueueTest {
    private val events = CopyOnWriteArrayList<Pair<String, Map<String, Any?>>>()

    private fun bindParams() = BindParams("dev-1", "T2U test", "ytcr", "cl", "T2U", "Test", Random(7)).also {
        it.updateWithLoungeToken("TOKEN")
        it.updateWithMessage(IncomingMessage(3, "c", JsonArray(listOf(JsonPrimitive("SIDX")))))
        it.updateWithMessage(IncomingMessage(4, "S", JsonPrimitive("GSX")))
    }

    private fun ok(request: Request): Response =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").body("".toResponseBody()).build()

    private fun bodyOf(request: Request): String = Buffer().also { request.body!!.writeTo(it) }.readUtf8()

    private fun ofsOf(request: Request): Int = Regex("(?:^|&)ofs=(\\d+)").find(bodyOf(request))!!.groupValues[1].toInt()

    /** Names of the messages of one POST, in order. */
    private fun namesOf(request: Request): List<String> = Regex("req\\d+__sc=([A-Za-z]+)").findAll(bodyOf(request)).map { it.groupValues[1] }.toList()

    private class Counts {
        val refreshes = AtomicInteger()
        val ends = CopyOnWriteArrayList<Throwable>()
        val posts = CopyOnWriteArrayList<Request>()
        val canSend = AtomicBoolean(true)
    }

    private fun TestScope.queue(
        counts: Counts,
        scope: CoroutineScope = backgroundScope,
        execute: suspend (Request) -> Response,
    ): SessionSendQueue = SessionSendQueue(
        scope = scope,
        endpoints = LoungeEndpoints("http://127.0.0.1:1"),
        bind = bindParams(),
        log = LoungeLog(EventSink { kind, fields -> events.add(kind to fields) }, "cl"),
        formType = "application/x-www-form-urlencoded;charset=UTF-8".toMediaType(),
        execute = { request ->
            counts.posts.add(request)
            execute(request)
        },
        canSend = { counts.canSend.get() },
        refreshToken = { counts.refreshes.incrementAndGet() },
        endSession = { counts.ends.add(it) },
        outageBudgetMs = 120_000,
        outageWaitsMs = listOf(2_000, 4_000, 8_000, 16_000, 30_000),
        nowMs = { testScheduler.currentTime },
    )

    private fun volume(level: Int) = OutgoingMessage.onVolumeChanged(null, Volume(level, false))

    private fun sendWaits() = events.filter { it.first == "lounge-session" && it.second["what"] == "send-wait" }

    @Test
    fun aNetworkErrorWaitsAndRetriesTheSameOffsetUntilTheBudgetEnds() = runTest {
        val counts = Counts()
        val queue = queue(counts) { request ->
            if (testScheduler.currentTime < 100_000) throw IOException("network unreachable")
            ok(request)
        }
        val sent = async { queue.sendMessage(listOf(volume(12))) }
        testScheduler.advanceUntilIdle()
        assertTrue(sent.await())
        assertEquals(0, counts.refreshes.get(), "no token refresh while the tolerance lasts")
        assertTrue(counts.ends.isEmpty())
        assertEquals(listOf(0), counts.posts.map { ofsOf(it) }.distinct(), "every attempt carried the same offset")
        assertEquals(8, counts.posts.size)
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L, 30_000L), sendWaits().map { it.second["waitMs"] })
        assertEquals((1..7).toList(), sendWaits().map { it.second["attempt"] })
    }

    @Test
    fun aNetworkErrorAfterTheBudgetRefreshesOnceAndThenEndsTheSession() = runTest {
        val counts = Counts()
        val queue = queue(counts) { throw IOException("network unreachable") }
        val sent = async { queue.sendMessage(listOf(volume(12))) }
        testScheduler.advanceUntilIdle()
        assertFalse(sent.await())
        assertEquals(120_000, testScheduler.currentTime, "the reader's budget, not longer")
        assertEquals(1, counts.refreshes.get())
        assertEquals(1, counts.ends.size)
        assertIs<IOException>(counts.ends.single())
    }

    @Test
    fun anHttpRefusalNeverWaits() = runTest {
        val counts = Counts()
        val failed = AtomicBoolean(false)
        val queue = queue(counts) { request ->
            if (failed.compareAndSet(false, true)) throw LoungeHttpException(500, "/bc/bind")
            ok(request)
        }
        val sent = async { queue.sendMessage(listOf(volume(12))) }
        testScheduler.advanceUntilIdle()
        assertTrue(sent.await())
        assertEquals(1, counts.refreshes.get(), "the refusal refreshes the token at once")
        assertEquals(0, testScheduler.currentTime, "no virtual time passed")
        assertTrue(sendWaits().isEmpty())
        assertTrue(counts.ends.isEmpty())
    }

    @Test
    fun theSendLockIsFreeDuringTheWait() = runTest {
        val counts = Counts()
        val network = AtomicBoolean(false)
        val queue = queue(counts) { request -> if (network.get()) ok(request) else throw IOException("network unreachable") }
        val sent = async { queue.sendMessage(listOf(volume(12))) }
        runCurrent()
        assertEquals(1, sendWaits().size, "the send waits")
        val farewell = async { queue.withSendLock { "took the lock" } }
        runCurrent()
        assertTrue(farewell.isCompleted, "the farewell and the token refresh are not blocked by a waiting send")
        assertEquals(0, testScheduler.currentTime)
        network.set(true)
        testScheduler.advanceUntilIdle()
        assertTrue(sent.await())
    }

    @Test
    fun aSessionEndedDuringTheWaitPostsNothingMore() = runTest {
        val counts = Counts()
        val queue = queue(counts) { throw IOException("network unreachable") }
        val sent = async { queue.sendMessage(listOf(volume(12))) }
        runCurrent()
        assertEquals(1, counts.posts.size)
        // what Session.end does: the status leaves RUNNING, the queue is cleared
        counts.canSend.set(false)
        queue.clearQueuedSends()
        testScheduler.advanceUntilIdle()
        assertFalse(sent.await())
        assertEquals(1, counts.posts.size, "no POST after the end")
        assertEquals(0, counts.refreshes.get())
        assertTrue(counts.ends.isEmpty())
    }

    // ---- coalescing (D-02)

    private fun msg(name: String, tag: Int? = null, videoId: String? = null): OutgoingMessage {
        val payload = LinkedHashMap<String, Any?>()
        if (tag != null) payload["currentTime"] = tag
        if (videoId != null) payload["videoId"] = videoId
        return OutgoingMessage(null, name, payload)
    }

    /** "name" or "name:tag" of every message of one POST, in order. */
    private fun labels(request: Request): List<String> {
        val form = bodyOf(request).split('&').associate {
            java.net.URLDecoder.decode(it.substringBefore('='), "UTF-8") to java.net.URLDecoder.decode(it.substringAfter('='), "UTF-8")
        }
        return (0 until form.getValue("count").toInt()).map { i ->
            val tag = form["req${i}_currentTime"] ?: form["req${i}_videoId"]
            form.getValue("req${i}__sc") + (tag?.let { ":$it" } ?: "")
        }
    }

    @Test
    fun stateMessagesQueuedBehindAWaitingSendAreCoalesced() = runTest {
        val counts = Counts()
        val network = AtomicBoolean(false)
        val delivered = CopyOnWriteArrayList<List<String>>()
        val queue = queue(counts) { request ->
            if (!network.get()) throw IOException("network unreachable")
            delivered.add(labels(request))
            ok(request)
        }
        val waiting = async { queue.sendMessage(listOf(msg("onVolumeChanged", 12))) }
        runCurrent()
        assertEquals(1, sendWaits().size, "the first send waits")
        val queued = listOf(
            listOf(msg("onStateChange", 10)),
            listOf(msg("onStateChange", 20), msg("nowPlaying", 1), msg("onHasPreviousNextChanged")),
            listOf(msg("autoplayUpNext", videoId = "X")),
            listOf(msg("nowPlaying", 2)),
            listOf(msg("onStateChange", 30)),
        ).map { async { queue.sendMessage(it) } }
        runCurrent()
        network.set(true)
        testScheduler.advanceUntilIdle()
        assertTrue(waiting.await())
        assertEquals(
            listOf(
                listOf("onVolumeChanged:12"),
                listOf("onHasPreviousNextChanged"),
                listOf("autoplayUpNext:X"),
                listOf("nowPlaying:2"),
                listOf("onStateChange:30"),
            ),
            delivered.toList(),
            "the waiting task unchanged first, then the newest state of each name, order kept",
        )
        assertEquals(listOf(false, true, true, true, true), queued.map { it.await() }, "a task left with no message answers false")
        assertEquals(0, counts.refreshes.get())
    }

    @Test
    fun deferredAndFarewellSendsAreNeverCoalesced() = runTest {
        val counts = Counts()
        val network = AtomicBoolean(false)
        val delivered = CopyOnWriteArrayList<List<String>>()
        val queue = queue(counts) { request ->
            if (!network.get()) throw IOException("network unreachable")
            delivered.add(labels(request))
            ok(request)
        }
        val waiting = async { queue.sendMessage(listOf(msg("onVolumeChanged", 12))) }
        runCurrent()
        val keyed = async { queue.sendMessage(listOf(msg("onStateChange", 5)), deferKey = "volume", deferMs = 0) }
        val newer = async { queue.sendMessage(listOf(msg("onStateChange", 6))) }
        val farewell = async { queue.sendMessage(listOf(OutgoingMessage.loungeScreenDisconnected())) }
        runCurrent()
        network.set(true)
        testScheduler.advanceUntilIdle()
        assertTrue(waiting.await())
        assertTrue(keyed.await(), "a keyed send keeps its message even with a newer onStateChange behind it")
        assertTrue(newer.await())
        assertTrue(farewell.await())
        assertEquals(
            listOf(
                listOf("onVolumeChanged:12"),
                listOf("onStateChange:5"),
                listOf("onStateChange:6"),
                listOf("loungeScreenDisconnected"),
            ),
            delivered.toList(),
        )
    }

    @Test
    fun aHealthyQueueIsNotCoalesced() = runTest {
        val counts = Counts()
        val delivered = CopyOnWriteArrayList<List<String>>()
        val queue = queue(counts) { request ->
            delivered.add(labels(request))
            ok(request)
        }
        val first = async { queue.sendMessage(listOf(msg("onStateChange", 10))) }
        val second = async { queue.sendMessage(listOf(msg("onStateChange", 20))) }
        testScheduler.advanceUntilIdle()
        assertTrue(first.await())
        assertTrue(second.await())
        assertEquals(listOf(listOf("onStateChange:10"), listOf("onStateChange:20")), delivered.toList())
        assertTrue(sendWaits().isEmpty())
    }

    @Test
    fun clearQueuedSendsAlsoFailsTheHeldBackTasks() = runTest {
        val counts = Counts()
        val queue = queue(counts) { throw IOException("network unreachable") }
        val waiting = async { queue.sendMessage(listOf(msg("onVolumeChanged", 12))) }
        runCurrent()
        val behind = listOf(
            async { queue.sendMessage(listOf(msg("autoplayUpNext", videoId = "X"))) },
            async { queue.sendMessage(listOf(msg("onStateChange", 30))) },
        )
        runCurrent()
        // the first wait ends, the queue behind it is drained, the retry fails and the next wait starts
        testScheduler.advanceTimeBy(2_001)
        runCurrent()
        assertEquals(2, sendWaits().size)
        val posts = counts.posts.size
        // what Session.end does: the status leaves RUNNING, the queue is cleared
        counts.canSend.set(false)
        queue.clearQueuedSends()
        runCurrent()
        assertTrue(behind.all { it.isCompleted }, "the tasks held back answer at once, not when the consumer gets to them")
        assertEquals(listOf(false, false), behind.map { it.await() })
        testScheduler.advanceUntilIdle()
        assertFalse(waiting.await())
        assertEquals(posts, counts.posts.size, "nothing is posted after the end")
    }
}
