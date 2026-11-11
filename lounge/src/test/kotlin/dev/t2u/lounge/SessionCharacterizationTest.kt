package dev.t2u.lounge

// Characterization tests for Session's send queue and long-poll stream (plan 09-28, D-05): they pin what the code does
// today, before the file is split. Where today's behaviour looks odd it is pinned as it is and named in the test;
// nothing here encodes a fix. Every wait is on an observable condition (awaitUntil), never on a fixed delay, except the
// two "stays pending" checks, which can only pass wrongly when run early, never fail wrongly.

import dev.t2u.upnp.EventSink
import dev.t2u.upnp.lanHttpClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SessionCharacterizationTest {
    private val fake = FakeLoungeServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val events = CopyOnWriteArrayList<Pair<String, Map<String, Any?>>>()
    private val sink = EventSink { kind, fields -> events.add(kind to fields) }
    private val terminated = CopyOnWriteArrayList<Throwable>()
    private val release = CountDownLatch(1)

    @AfterEach
    fun tearDown() {
        release.countDown()
        fake.sendGate?.complete(Unit)
        scope.cancel()
        fake.close()
    }

    private fun session(http: OkHttpClient = lanHttpClient()): Session {
        val s = Session(
            client = Client.YT,
            screenName = "T2U test",
            brand = "T2U",
            model = "Test",
            dataStore = null,
            http = http,
            endpoints = fake.endpoints,
            sink = sink,
            scope = scope,
            rpcRetryDelayMs = 20,
        )
        s.onTerminate = { terminated.add(it) }
        return s
    }

    private suspend fun awaitUntil(timeoutMs: Long = 10_000, cond: () -> Boolean) {
        withTimeout(timeoutMs) { while (!cond()) delay(10) }
    }

    private fun <T> run(block: suspend () -> T): T = runBlocking { withTimeout(30_000) { block() } }

    private fun volume(level: Int, aid: Int? = null) = OutgoingMessage.onVolumeChanged(aid, Volume(level, false))

    /** The AID query value of every message POST (bind with count >= 1) in arrival order; null when the POST had none. */
    private fun postedAids(): List<String?> =
        fake.requests.filter { it.method == "POST" && it.path.endsWith("/bind") && (it.form["count"] ?: "0") != "0" }.map { it.query["AID"] }

    // ---- send queue

    @Test
    fun aSendWaitingAcrossATokenRefreshGoesOutWithoutItsAid() = run {
        // The send is deferred 2 s, far longer than the refresh takes, so it leaves after the epoch changed.
        // Not pinned: a send that is already queued behind the refresh when the send lock is released can still leave with
        // its AID, because refreshLoungeToken bumps the epoch after it unlocks; which of the two wins is a race.
        val s = session()
        s.begin()
        coroutineScope {
            val stale = async(start = CoroutineStart.UNDISPATCHED) { s.sendMessage(listOf(volume(20, aid = 7)), deferKey = "volume", deferMs = 2_000) }
            s.refreshLoungeToken()
            assertEquals(SessionStatus.RUNNING, s.status)
            assertTrue(stale.await())
        }
        assertTrue(s.sendMessage(listOf(volume(30, aid = 8))))
        val aids = postedAids()
        assertEquals(2, aids.size)
        assertTrue(aids[0] != "7", "the stale send's own AID was dropped: $aids")
        assertEquals("8", aids[1], "a send queued after the refresh keeps its AID")
        // the refreshed bind session counts messages from zero again
        assertEquals(listOf("0", "1"), fake.sent.getValue("cl").map { it["ofs"] })
    }

    @Test
    fun aFailedPostWhoseRefreshAlsoFailsReturnsFalseAndEndsTheSession() = run {
        val s = session()
        s.begin()
        fake.failNextSends.set(1)
        fake.refuseAllTokens = true
        assertFalse(s.sendMessage(listOf(volume(12))))
        awaitUntil { s.status == SessionStatus.STOPPED && terminated.isNotEmpty() }
        assertEquals(1, terminated.size)
        val error = assertIs<SessionException>(terminated.single())
        assertIs<LoungeHttpException>(error.cause)
        assertFalse(s.sendMessage(listOf(volume(13))), "an ended session sends nothing")
    }

    @Test
    fun cancellingTheScopeDuringAPostCompletesTheSendAsFalse() = run {
        val s = session()
        s.begin()
        val gate = CompletableDeferred<Unit>()
        fake.sendGate = gate
        coroutineScope {
            val send = async { s.sendMessage(listOf(volume(10))) }
            awaitUntil { fake.sent["cl"]?.size == 1 }
            scope.cancel()
            assertFalse(send.await())
        }
        gate.complete(Unit) // so the fake can answer the abandoned POST
        Unit
    }

    @Test
    fun cancellingTheScopeDuringTheRefreshOfARetryNeverAnswersTheCaller() = run {
        // Pinned as found: process() rethrows the cancellation from the refresh without completing the task's result,
        // so a caller that lives outside the cancelled scope waits for ever. In the app every send is launched inside
        // that scope (YouTubeApp.post), so it is cancelled with it.
        val inRefresh = CompletableDeferred<Unit>()
        val armed = AtomicBoolean(false)
        val http = lanHttpClient().newBuilder().addInterceptor { chain ->
            val req = chain.request()
            if (armed.get() && req.url.encodedPath.endsWith("get_lounge_token_batch")) {
                inRefresh.complete(Unit)
                release.await()
            }
            chain.proceed(req)
        }.build()
        val s = session(http)
        s.begin()
        fake.failNextSends.set(1)
        armed.set(true)
        coroutineScope {
            val caller = async { s.sendMessage(listOf(volume(12))) }
            inRefresh.await()
            scope.cancel()
            release.countDown()
            // an absence check: if it runs early it passes wrongly, it can never fail wrongly
            delay(300)
            assertFalse(caller.isCompleted)
            caller.cancel()
        }
    }

    @Test
    fun endAnswersQueuedAndDeferredSendsFalseAndLeavesTheOneInFlight() = run {
        val s = session()
        s.begin()
        val gate = CompletableDeferred<Unit>()
        fake.sendGate = gate
        coroutineScope {
            val inFlight = async { s.sendMessage(listOf(volume(10))) }
            awaitUntil { fake.sent["cl"]?.size == 1 }
            val queued = async(start = CoroutineStart.UNDISPATCHED) { s.sendMessage(listOf(volume(20))) }
            val deferred = async(start = CoroutineStart.UNDISPATCHED) { s.sendMessage(listOf(volume(30)), deferKey = "volume", deferMs = 60_000) }
            // the farewell cannot get the send lock while the first send is held, so end() gives up after its 2 s budget
            s.end()
            assertEquals(SessionStatus.STOPPED, s.status)
            assertFalse(queued.await())
            assertFalse(deferred.await())
            fake.sendGate = null
            gate.complete(Unit)
            assertTrue(inFlight.await(), "the POST already in flight is not recalled and was accepted")
        }
        assertEquals(listOf("10"), fake.sent.getValue("cl").map { it["req0_volume"] }, "no farewell and no queued send left the phone")
    }

    // ---- long-poll stream

    @Test
    fun aStreamThatCannotBeReopenedAndWhoseRefreshFailsEndsTheSessionWithThatError() = run {
        val s = session()
        s.begin()
        fake.refuseAllTokens = true
        fake.refuseNextRpc.set(3)
        fake.closeRpc("cl")
        awaitUntil { s.status == SessionStatus.STOPPED && terminated.isNotEmpty() }
        val error = assertIs<SessionException>(terminated.single())
        assertIs<LoungeHttpException>(error.cause)
        assertEquals(400, (error.cause as LoungeHttpException).status)
        assertTrue(events.any { it.first == "lounge-session" && it.second["what"] == "rpc-reconnect" })
        awaitUntil { !s.rpcOpen() }
    }
}
