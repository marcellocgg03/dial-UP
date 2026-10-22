package dev.t2u.renderer

// CAST-06, speaker side (plan 03-08): after a disconnect or a service Stop the speaker is left with nothing
// loaded, and only when the speaker still holds one of this app's own streams. GPL-3.0-only.
//
// Every test method has a block body that returns Unit: JUnit Jupiter silently skips a test whose expression
// body returns a value (the lesson of plan 03-07).

import dev.t2u.lounge.Video
import dev.t2u.upnp.FakeCall
import dev.t2u.upnp.FakeRenderer
import dev.t2u.upnp.RENDERER_ST
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val DQW = "dQw4w9WgXcQ"
private const val OTHER = "http://192.168.1.50/other.mp3"

/** A speaker as the registry sees it, recording clear and destroy in one shared timeline. */
private class StubSpeaker(
    override val udn: String,
    override val address: String,
    override val port: Int,
    private val timeline: CopyOnWriteArrayList<String>,
    private val onClear: suspend () -> Unit = {},
) : ManagedRenderer {
    override val location = "http://$address:1400/xml/device_description.xml"
    override val name: String? = "Stub $udn"
    @Volatile override var lastSeen: Long = 0
    val clearCalls = AtomicInteger()
    val destroyCalls = AtomicInteger()
    @Volatile var stale = false

    override fun refresh(maxAge: Int) {}

    override fun isStale(now: Long): Boolean = stale

    override suspend fun init(): Boolean = true

    override suspend fun destroy() {
        destroyCalls.incrementAndGet()
        timeline.add("destroy:$udn")
    }

    override suspend fun clearSpeaker() {
        clearCalls.incrementAndGet()
        timeline.add("clear-start:$udn")
        onClear()
        timeline.add("clear-end:$udn")
    }
}

class ClearSpeakerTest {
    private fun withFake(fake: FakeRenderer = FakeRenderer(), block: suspend (FakeRenderer) -> Unit) {
        fake.start()
        try {
            runBlocking<Unit> { block(fake) }
        } finally {
            fake.close()
        }
    }

    private fun List<FakeCall>.actions(): List<String> = map { it.action }

    private fun clearsSent(fake: FakeRenderer): List<FakeCall> =
        fake.calls.filter { it.action == "SetAVTransportURI" && it.body.contains("<CurrentURI></CurrentURI>") }

    @Test
    @DisplayName("reset after a cast stops the speaker, empties its transport URI and stops polling")
    fun resetEmptiesTheTransportUri() {
        withFake { fake ->
            RendererHarness(fake).use { h ->
                h.renderer.init()
                assertTrue(h.renderer.doPlay(Video(DQW), 0.0))
                assertTrue(fake.state.currentUri.contains("/stream/"), fake.state.currentUri)
                val before = fake.calls.size

                h.renderer.reset()

                val after = fake.calls.drop(before)
                val stopAt = after.indexOfFirst { it.action == "Stop" }
                val clearAt = after.indexOfFirst { it.action == "SetAVTransportURI" }
                assertTrue(stopAt >= 0 && clearAt > stopAt, "Stop then SetAVTransportURI expected, got ${after.actions()}")
                assertTrue(after[clearAt].body.contains("<CurrentURI></CurrentURI>"), after[clearAt].body)
                assertTrue(after[clearAt].body.contains("<CurrentURIMetaData></CurrentURIMetaData>"), after[clearAt].body)
                assertEquals("", fake.state.currentUri)
                assertNull(h.renderer.nowPlaying)
                assertTrue(h.notes("Cleared the speaker").isNotEmpty())

                val mark = fake.calls.size
                delay(300)
                val polled = fake.calls.drop(mark).filter { it.action == "GetTransportInfo" || it.action == "GetPositionInfo" }
                assertTrue(polled.isEmpty(), "polling went on after the clear: ${polled.actions()}")
            }
        }
    }

    @Test
    @DisplayName("a speaker that moved to another source is left alone")
    fun foreignUriIsNotCleared() {
        withFake { fake ->
            RendererHarness(fake).use { h ->
                h.renderer.init()
                assertTrue(h.renderer.doPlay(Video(DQW), 0.0))
                fake.state.currentUri = OTHER
                val before = fake.calls.size

                h.renderer.reset()

                val after = fake.calls.drop(before)
                assertTrue(after.none { it.action == "Stop" || it.action == "SetAVTransportURI" }, "sent ${after.actions()}")
                assertEquals(OTHER, fake.state.currentUri)
                assertTrue(h.notes("clear skipped").isNotEmpty())
            }
        }
    }

    @Test
    @DisplayName("a renderer that loaded nothing this session sends nothing, even if the speaker holds an app-looking URL")
    fun neverLoadedSendsNothing() {
        withFake { fake ->
            RendererHarness(fake).use { h ->
                h.renderer.init()
                fake.state.currentUri = "http://127.0.0.1:8000/stream/00000000000000000000000000000001.m4a"
                val before = fake.calls.size

                h.renderer.reset()

                val after = fake.calls.drop(before)
                assertTrue(after.none { it.action == "Stop" || it.action == "SetAVTransportURI" }, "sent ${after.actions()}")
            }
        }
    }

    @Test
    @DisplayName("the clear runs after a sender Stop, which alone leaves the track loaded")
    fun clearRunsAfterSenderStop() {
        withFake { fake ->
            RendererHarness(fake).use { h ->
                h.renderer.init()
                assertTrue(h.renderer.play(Video(DQW), 0.0))
                assertTrue(h.renderer.stop())
                assertTrue(fake.state.currentUri.contains("/stream/"), "a plain Stop must leave the track loaded")
                assertTrue(clearsSent(fake).isEmpty())

                h.renderer.reset()

                assertEquals(1, clearsSent(fake).size)
                assertEquals("", fake.state.currentUri)
            }
        }
    }

    @Test
    @DisplayName("a Stop the speaker answers with a bare fault does not stop the empty URI from being sent")
    fun stopFaultDoesNotBlockTheClear() {
        withFake(FakeRenderer(bareFaultOn = setOf("Stop"))) { fake ->
            RendererHarness(fake).use { h ->
                h.renderer.init()
                assertTrue(h.renderer.doPlay(Video(DQW), 0.0))

                h.renderer.reset()

                assertEquals(1, clearsSent(fake).size)
                assertEquals("", fake.state.currentUri)
            }
        }
    }

    @Test
    @DisplayName("after destroy, reset sends nothing and does not throw")
    fun resetAfterDestroyDoesNothing() {
        withFake { fake ->
            RendererHarness(fake).use { h ->
                h.renderer.init()
                assertTrue(h.renderer.doPlay(Video(DQW), 0.0))
                h.renderer.destroy()
                val before = fake.calls.size

                h.renderer.reset()

                assertTrue(fake.calls.drop(before).none { it.action == "Stop" || it.action == "SetAVTransportURI" })
            }
        }
    }

    @Test
    @DisplayName("a speaker that is gone does not hold the clear past its budget")
    fun unreachableSpeakerIsBounded() {
        withFake { fake ->
            RendererHarness(fake).use { h ->
                h.renderer.init()
                assertTrue(h.renderer.doPlay(Video(DQW), 0.0))
                fake.close()

                val t0 = System.nanoTime()
                h.renderer.clearSpeaker()
                val ms = (System.nanoTime() - t0) / 1_000_000

                assertTrue(ms < 5000, "took $ms ms")
            }
        }
    }

    @Test
    @DisplayName("a speaker that accepts the connection and never answers is cut at the budget")
    fun silentSpeakerIsBounded() {
        withFake { fake ->
            RendererHarness(fake).use { h ->
                h.renderer.init()
                assertTrue(h.renderer.doPlay(Video(DQW), 0.0))
                fake.onAction = { action -> if (action == "GetMediaInfo") Thread.sleep(15_000) }

                val t0 = System.nanoTime()
                h.renderer.clearSpeaker()
                val ms = (System.nanoTime() - t0) / 1_000_000

                assertTrue(ms in 3500..5000, "took $ms ms")
            }
        }
    }

    @Test
    @DisplayName("a speaker that refuses the empty URI is recorded in the evidence log")
    fun refusalIsRecorded() {
        val faults = java.util.concurrent.CopyOnWriteArraySet<String>()
        withFake(FakeRenderer(bareFaultOn = faults)) { fake ->
            RendererHarness(fake).use { h ->
                h.renderer.init()
                assertTrue(h.renderer.doPlay(Video(DQW), 0.0))
                faults.add("SetAVTransportURI")

                h.renderer.reset()

                assertTrue(h.notes("the speaker refused an empty transport URI").isNotEmpty(), h.events.toString())
            }
        }
    }

    // ---- plan 03-23: the clear is read back, and a slow speaker's late URI is watched for ----

    /** Holds the first request for [action] at the fake (recorded, not yet applied or answered) until [release] is completed. */
    private class Hold(private val action: String) {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        private val used = AtomicBoolean(false)

        suspend fun onCall(called: String) {
            if (called == action && used.compareAndSet(false, true)) {
                entered.complete(Unit)
                release.await()
            }
        }
    }

    /** The renderer's clock and the wait between two reads of the watch, moved by hand: one [tick] lets the watch go on one step. */
    private class WatchClock {
        val now = AtomicLong(1_000_000)
        val entered = AtomicInteger()
        private val ticks = Channel<Unit>(Channel.UNLIMITED)

        suspend fun pause(ms: Long) {
            entered.incrementAndGet()
            ticks.receive()
            now.addAndGet(ms)
        }

        fun tick() {
            ticks.trySend(Unit)
        }
    }

    private fun RendererHarness.messages(): List<String> =
        events.filter { it.first == "renderer" }.mapNotNull { it.second["message"] as? String }

    private fun RendererHarness.watchEnded(): Boolean = notes("clear watch ended").isNotEmpty()

    /** Lets the watch take one step and waits until it is waiting for the next one, or has ended. */
    private suspend fun step(h: RendererHarness, w: WatchClock) {
        val n = w.entered.get()
        w.tick()
        waitUntil(3000) { w.entered.get() > n || h.watchEnded() }
    }

    private fun List<FakeCall>.control(): List<String> = map { it.action }.filter { it == "GetMediaInfo" || it == "Stop" || it == "SetAVTransportURI" }

    private suspend fun castAndHold(h: RendererHarness, fake: FakeRenderer): Hold {
        h.renderer.init()
        val hold = Hold("SetAVTransportURI")
        fake.beforeAction = hold::onCall
        h.scope.async { h.renderer.doPlay(Video(DQW), 0.0) }
        withTimeout(5000) { hold.entered.await() }
        return hold
    }

    @Test
    @DisplayName("a clear reads the URI back once and logs that the speaker is confirmed empty")
    fun aClearReadsTheUriBackAndLogsConfirmedEmpty() {
        withFake { fake ->
            RendererHarness(fake).use { h ->
                h.renderer.init()
                assertTrue(h.renderer.doPlay(Video(DQW), 0.0))
                val mark = fake.calls.size

                h.renderer.clearSpeaker()

                assertEquals(
                    listOf("GetMediaInfo", "Stop", "SetAVTransportURI", "GetMediaInfo"),
                    fake.calls.drop(mark).control(),
                    "the guard read, the Stop, the empty URI and one read-back",
                )
                assertTrue(h.messages().any { it.startsWith("Cleared the speaker") && it.contains("confirmed empty") }, h.messages().toString())
                assertEquals("", fake.state.currentUri)
            }
        }
    }

    @Test
    @DisplayName("a clear that finds our stream still on the speaker sends the empty URI once more")
    fun aClearThatFindsOurUriStillThereSendsTheEmptyUriOnce() {
        withFake { fake ->
            RendererHarness(fake).use { h ->
                h.renderer.init()
                assertTrue(h.renderer.doPlay(Video(DQW), 0.0))
                val ours = fake.state.currentUri
                val ignored = AtomicInteger()
                // A speaker that drops the first empty URI and keeps playing its stream.
                fake.onAction = { action ->
                    if (action == "SetAVTransportURI" && fake.state.currentUri.isEmpty() && ignored.getAndIncrement() == 0) fake.state.currentUri = ours
                }

                h.renderer.clearSpeaker()

                assertEquals(2, clearsSent(fake).size, "one empty URI and exactly one more")
                assertEquals("", fake.state.currentUri)
                assertTrue(h.messages().any { it.startsWith("Cleared the speaker") && it.contains("confirmed empty") }, h.messages().toString())
            }
        }
    }

    @Test
    @DisplayName("a speaker that keeps our stream after the second empty URI is logged and not retried again")
    fun aSecondFailureIsLoggedAndDoesNotLoop() {
        withFake { fake ->
            RendererHarness(fake).use { h ->
                h.renderer.init()
                assertTrue(h.renderer.doPlay(Video(DQW), 0.0))
                val ours = fake.state.currentUri
                fake.onAction = { action -> if (action == "SetAVTransportURI" && fake.state.currentUri.isEmpty()) fake.state.currentUri = ours }

                h.renderer.clearSpeaker()

                assertEquals(2, clearsSent(fake).size, "the empty URI is sent twice and no more")
                assertTrue(h.messages().any { it.contains("still ours after retry") }, h.messages().toString())
                assertFalse(h.messages().any { it.startsWith("Cleared the speaker") }, "a speaker that still holds our stream was not cleared")
                assertTrue(h.messages().none { it.contains(ours) }, "the stream URI carries a token and is not logged")
            }
        }
    }

    @Test
    @DisplayName("a read-back that fails does not fail the clear")
    fun aReadBackFailureDoesNotFailTheClear() {
        withFake { fake ->
            RendererHarness(fake).use { h ->
                h.renderer.init()
                assertTrue(h.renderer.doPlay(Video(DQW), 0.0))
                // From the empty URI on, the speaker answers GetMediaInfo with a bare fault: the guard read before it was fine.
                fake.onAction = { action ->
                    if (action == "SetAVTransportURI" && fake.state.currentUri.isEmpty()) fake.bareFaultOn = setOf("GetMediaInfo")
                }

                h.renderer.clearSpeaker()

                assertEquals("", fake.state.currentUri)
                assertEquals(1, clearsSent(fake).size, "the empty URI is not sent again on a read that failed")
                assertTrue(h.messages().any { it.contains("read-back unavailable") }, h.messages().toString())
                assertNull(h.renderer.nowPlaying)
            }
        }
    }

    @Test
    @DisplayName("a clear on a speaker that answers at once does not wait for the in-flight ceiling and starts no watch")
    fun aClearOnAnImmediateSpeakerDoesNotWaitForTheInFlightCeiling() {
        withFake { fake ->
            val w = WatchClock()
            RendererHarness(fake, clock = { w.now.get() }, pause = w::pause).use { h ->
                h.renderer.init()
                assertTrue(h.renderer.doPlay(Video(DQW), 0.0))

                val t0 = System.nanoTime()
                h.renderer.clearSpeaker()
                val ms = (System.nanoTime() - t0) / 1_000_000

                assertTrue(ms < CLEAR_INFLIGHT_WAIT_MS, "the clear took $ms ms")
                assertEquals("", fake.state.currentUri)
                assertEquals(0, w.entered.get(), "a watch was waiting")
                assertTrue(h.messages().none { it.startsWith("clear watch") }, h.messages().toString())
            }
        }
    }

    @Test
    @DisplayName("a stream that lands after a clear gave up on a slow speaker is emptied by the watch")
    fun aLateLandingUriAfterATimedOutClearIsEmptiedByTheWatch() {
        withFake { fake ->
            val w = WatchClock()
            RendererHarness(fake, clock = { w.now.get() }, pause = w::pause).use { h ->
                val hold = castAndHold(h, fake)
                assertEquals("", fake.state.currentUri, "the URI has not landed yet")

                h.renderer.clearSpeaker()
                waitUntil(2000) { w.entered.get() >= 1 }
                hold.release.complete(Unit)
                waitUntil(3000) { fake.state.currentUri.contains("/stream/") }
                assertTrue(fake.state.currentUri.contains("/stream/"), "the late URI has landed")
                val mark = fake.calls.size

                step(h, w)

                assertEquals("", fake.state.currentUri, "the late-landing song stayed loaded on the speaker")
                assertEquals(listOf("GetMediaInfo", "Stop", "SetAVTransportURI"), fake.calls.drop(mark).control())
                assertEquals(1, clearsSent(fake).size, "the clear found nothing to empty; the watch sent the one empty URI")
                assertTrue(h.messages().any { it.startsWith("clear watch ended") && it.contains("emptied") }, h.messages().toString())
                assertTrue(fake.calls.none { it.action == "Play" }, "the cancelled load never reaches Play")
            }
        }
    }

    @Test
    @DisplayName("the watch ends when a new load starts and sends nothing after")
    fun theWatchStopsWhenANewLoadStarts() {
        withFake { fake ->
            val w = WatchClock()
            RendererHarness(fake, clock = { w.now.get() }, pause = w::pause).use { h ->
                val hold = castAndHold(h, fake)
                h.renderer.clearSpeaker()
                waitUntil(2000) { w.entered.get() >= 1 }
                assertTrue(w.entered.get() >= 1, "the watch is waiting for its first read")
                hold.release.complete(Unit)
                waitUntil(3000) { fake.state.currentUri.contains("/stream/") }
                assertTrue(h.renderer.doPlay(Video(DQW), 0.0), "a new cast starts")
                val mark = fake.calls.size

                step(h, w)

                assertTrue(h.messages().any { it.startsWith("clear watch ended") && it.contains("new load") }, h.messages().toString())
                assertEquals(emptyList(), fake.calls.drop(mark).control(), "the watch touched the speaker of the new cast")
                assertTrue(fake.state.currentUri.contains("/stream/"), "the new cast was emptied")
            }
        }
    }

    @Test
    @DisplayName("the watch stops at its time limit")
    fun theWatchStopsAtItsTimeLimit() {
        withFake { fake ->
            val w = WatchClock()
            RendererHarness(fake, clock = { w.now.get() }, pause = w::pause).use { h ->
                val hold = castAndHold(h, fake)
                h.renderer.clearSpeaker()
                waitUntil(2000) { w.entered.get() >= 1 }
                val mark = fake.calls.size
                try {
                    var steps = 0
                    while (!h.watchEnded() && steps < 30 && w.entered.get() >= 1) {
                        step(h, w)
                        steps++
                    }

                    assertTrue(h.messages().any { it.startsWith("clear watch ended") && it.contains("time") }, h.messages().toString())
                    val reads = fake.calls.drop(mark).count { it.action == "GetMediaInfo" }
                    assertTrue(reads in 1..(SLOW_CLEAR_WATCH_MS / SLOW_CLEAR_WATCH_INTERVAL_MS).toInt(), "$reads reads in $steps steps")
                    assertTrue(w.now.get() - 1_000_000 >= SLOW_CLEAR_WATCH_MS, "ended before the limit")
                    val end = fake.calls.size
                    w.tick()
                    delay(100)
                    assertEquals(end, fake.calls.size, "the watch went on after its limit")
                } finally {
                    hold.release.complete(Unit)
                }
            }
        }
    }

    @Test
    @DisplayName("the watch leaves a speaker that is on another source alone")
    fun theWatchLeavesAnotherSourceAlone() {
        withFake { fake ->
            val w = WatchClock()
            RendererHarness(fake, clock = { w.now.get() }, pause = w::pause).use { h ->
                val hold = castAndHold(h, fake)
                h.renderer.clearSpeaker()
                waitUntil(2000) { w.entered.get() >= 1 }
                hold.release.complete(Unit)
                waitUntil(3000) { fake.state.currentUri.contains("/stream/") }
                fake.state.currentUri = OTHER
                val mark = fake.calls.size

                step(h, w)

                val sent = fake.calls.drop(mark).control()
                assertTrue(sent.none { it == "Stop" || it == "SetAVTransportURI" }, "sent $sent")
                assertEquals(OTHER, fake.state.currentUri)
            }
        }
    }

    @Test
    @DisplayName("destroy cancels the watch and returns at once")
    fun destroyCancelsTheWatch() {
        withFake { fake ->
            val w = WatchClock()
            RendererHarness(fake, clock = { w.now.get() }, pause = w::pause).use { h ->
                val hold = castAndHold(h, fake)
                h.renderer.clearSpeaker()
                waitUntil(2000) { w.entered.get() >= 1 }
                assertTrue(w.entered.get() >= 1, "the watch is waiting for its first read")

                val t0 = System.nanoTime()
                h.renderer.destroy()
                val ms = (System.nanoTime() - t0) / 1_000_000
                hold.release.complete(Unit)
                waitUntil(3000) { fake.state.currentUri.contains("/stream/") }
                val mark = fake.calls.size
                w.tick()
                delay(100)

                assertTrue(ms < 1000, "destroy took $ms ms")
                assertEquals(mark, fake.calls.size, "the watch went on after destroy")
                assertTrue(fake.state.currentUri.contains("/stream/"), "nothing may touch the speaker after destroy")
            }
        }
    }

    // ---- Task 2: the registry clears on an explicit stop only ----

    private class RegistryRig(clearBudgetMs: Long = CLEAR_BUDGET_MS, val onClear: (String) -> suspend () -> Unit = { { } }) {
        val timeline = CopyOnWriteArrayList<String>()
        val speakers = CopyOnWriteArrayList<StubSpeaker>()
        val now = java.util.concurrent.atomic.AtomicLong(1_000_000)
        private val dir = Files.createTempDirectory("clear-registry-").toFile().also { it.deleteOnExit() }
        val registry = RendererRegistry(
            Config(dataDir = dir),
            PortRegistry(dir, 3000).load(),
            { udn, _, address, _, port -> StubSpeaker(udn, address, port, timeline, onClear(udn)).also { speakers.add(it) } },
            clock = { now.get() },
            clearBudgetMs = clearBudgetMs,
        )

        suspend fun alive(udn: String, ip: String) {
            registry.onAlive(
                mapOf(
                    "ST" to RENDERER_ST,
                    "USN" to "uuid:$udn::$RENDERER_ST",
                    "LOCATION" to "http://$ip:1400/xml/device_description.xml",
                    "CACHECONTROL" to "max-age=1800",
                ),
                InetSocketAddress(InetAddress.getByName(ip), 1900),
            )
        }

        fun speaker(udn: String) = speakers.first { it.udn == udn }
    }

    @Test
    @DisplayName("removeAll(clearSpeakers = true) clears every speaker in parallel before it is destroyed")
    fun removeAllClearsInParallelThenDestroys() {
        runBlocking<Unit> {
            val rig = RegistryRig(onClear = { { delay(300) } })
            rig.alive("a", "192.168.50.41")
            rig.alive("b", "192.168.50.42")

            val t0 = System.nanoTime()
            rig.registry.removeAll(clearSpeakers = true)
            val ms = (System.nanoTime() - t0) / 1_000_000

            assertTrue(ms < 600, "took $ms ms, so the clears ran one after the other")
            val tl = rig.timeline
            val firstDestroy = tl.indexOfFirst { it.startsWith("destroy:") }
            assertTrue(tl.indexOf("clear-start:a") in 0 until firstDestroy && tl.indexOf("clear-start:b") in 0 until firstDestroy, tl.toString())
            for (u in listOf("a", "b")) {
                assertTrue(tl.indexOf("clear-end:$u") < tl.indexOf("destroy:$u"), tl.toString())
                assertEquals(1, rig.speaker(u).destroyCalls.get())
            }
            assertEquals(0, rig.registry.count.value)
        }
    }

    @Test
    @DisplayName("a clear that never returns is cut at the registry budget and the speaker is still destroyed")
    fun hangingClearIsCutAndDestroyed() {
        runBlocking<Unit> {
            val rig = RegistryRig(clearBudgetMs = 200, onClear = { { delay(Long.MAX_VALUE) } })
            rig.alive("a", "192.168.50.41")

            val t0 = System.nanoTime()
            rig.registry.removeAll(clearSpeakers = true)
            val ms = (System.nanoTime() - t0) / 1_000_000

            assertTrue(ms < 1500, "took $ms ms")
            assertEquals(1, rig.speaker("a").clearCalls.get())
            assertEquals(1, rig.speaker("a").destroyCalls.get())
        }
    }

    @Test
    @DisplayName("a clear that throws does not stop the others from being cleared or destroyed")
    fun throwingClearDoesNotStopTheOthers() {
        runBlocking<Unit> {
            val rig = RegistryRig(onClear = { udn -> { if (udn == "a") throw IllegalStateException("boom") } })
            rig.alive("a", "192.168.50.41")
            rig.alive("b", "192.168.50.42")

            rig.registry.removeAll(clearSpeakers = true)

            for (u in listOf("a", "b")) {
                assertEquals(1, rig.speaker(u).clearCalls.get(), u)
                assertEquals(1, rig.speaker(u).destroyCalls.get(), u)
            }
            assertTrue(rig.timeline.contains("clear-end:b"))
        }
    }

    @Test
    @DisplayName("removeAll() by default, a goodbye and a stale speaker never clear")
    fun otherRemovalsNeverClear() {
        runBlocking<Unit> {
            val rig = RegistryRig()
            rig.alive("a", "192.168.50.41")
            rig.alive("b", "192.168.50.42")
            rig.alive("c", "192.168.50.43")

            rig.registry.onByebye(mapOf("USN" to "uuid:a::$RENDERER_ST", "NTS" to "ssdp:byebye"))
            rig.speaker("b").stale = true
            rig.registry.prune()
            rig.registry.removeAll()

            for (u in listOf("a", "b", "c")) {
                assertEquals(0, rig.speaker(u).clearCalls.get(), u)
                assertEquals(1, rig.speaker(u).destroyCalls.get(), u)
            }
        }
    }
}
