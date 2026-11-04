// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.t2u.renderer

// CR-01 c, WR-02, D-1 (plan 07-19): a speaker that never answers SOAP (the LG TV held the stop for 10 s) must not stretch
// Bridge.stop past its per-speaker stop or clear budget plus the destroy budget, and every port must be free when stop
// returns. A destroy that outlives its budget finishes in the background and releases its port only when it really ends.
//
// Every test is `= h.run<Unit> { ... }`: JUnit silently skips a test whose expression body returns a value.

import dev.t2u.upnp.EventSink
import dev.t2u.upnp.RENDERER_ST
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

private const val VID = "AAAAAAAAAAA"

class BridgeStopBudgetTest {
    private val h = BridgeHarness()

    @AfterEach
    fun tearDown() = h.close()

    /** Starts [bridge], connects the sender and casts until the fake speaker plays our stream; returns the speaker's DIAL port. */
    private suspend fun castUntilPlaying(bridge: Bridge): Int {
        bridge.start()
        val launched = h.launch(bridge)
        h.innerTube.scriptNext(VID, previousId = null, nextId = null)
        h.connectSender()
        h.rpc("cl", "setPlaylist", h.playlist(VID))
        h.awaitUntil("the speaker holding our stream") { h.fakeRenderer.state.currentUri.contains("/stream/") }
        h.awaitUntil("the speaker told to play") { h.speakerCount("Play") >= 1 }
        return launched.port
    }

    private fun mediaPort(bridge: Bridge): Int = bridge.state()["mediaBase"].toString().trim('"').substringAfterLast(':').toInt()

    private fun assertFree(port: Int, what: String) {
        ServerSocket(port, 1, h.loopback).use { assertEquals(port, it.localPort, "$what is free") }
    }

    private suspend fun stopAgainstASilentSpeaker(clear: Boolean) {
        val bridge = h.newBridge(h.testConfig(clearOnCastEnd = false))
        val dialPort = castUntilPlaying(bridge)
        val media = mediaPort(bridge)
        h.fakeRenderer.beforeAction = { delay(30_000) }

        val t0 = System.nanoTime()
        if (clear) bridge.stop(clearSpeakers = true) else bridge.stop(stopSpeakers = true)
        val tookMs = (System.nanoTime() - t0) / 1_000_000

        val limit = CLEAR_BUDGET_MS + RendererRegistry.DESTROY_BUDGET_MS + 1000
        assertTrue(tookMs < limit, "stop took $tookMs ms, limit $limit ms")
        assertFree(dialPort, "the speaker's DIAL port")
        assertFree(media, "the media port")
    }

    @Test
    @DisplayName("a speaker that never answers does not hold the stop")
    fun aSilentSpeakerDoesNotHoldTheStop() = h.run<Unit> {
        stopAgainstASilentSpeaker(clear = false)
    }

    @Test
    @DisplayName("a speaker that never answers does not hold the clearing stop")
    fun aSilentSpeakerDoesNotHoldTheClearingStop() = h.run<Unit> {
        stopAgainstASilentSpeaker(clear = true)
    }

    /** A speaker whose destroy ends only after [destroyMs], under NonCancellable as the lounge shutdown is. */
    private class SlowDestroyRenderer(
        override val udn: String,
        override val location: String,
        override val address: String,
        override val port: Int,
        private val destroyMs: Long,
        private val finished: AtomicBoolean,
    ) : ManagedRenderer {
        override val name: String = "Slow speaker"
        override val lastSeen: Long = 0

        override fun refresh(maxAge: Int) {}

        override fun isStale(now: Long) = false

        override suspend fun init() = true

        override suspend fun destroy() {
            withContext(NonCancellable) {
                delay(destroyMs)
                finished.set(true)
            }
        }
    }

    @Test
    @DisplayName("a destroy that outlives its budget still releases its port when it ends")
    fun aLateDestroyReleasesItsPortWhenItEnds() = h.run<Unit> {
        val loopback = InetAddress.getByName("127.0.0.1")
        val ports = PortRegistry(h.dataDir, 3000).load()
        val finished = AtomicBoolean(false)
        val logged = CopyOnWriteArrayList<Map<String, Any?>>()
        val registry = RendererRegistry(
            Config(dataDir = h.dataDir),
            ports,
            { udn, location, address, _, port -> SlowDestroyRenderer(udn, location, address, port, destroyMs = 3000, finished) },
            EventSink { kind, fields -> if (kind == "discovery") logged.add(fields) },
            destroyBudgetMs = 300,
        )
        registry.onAlive(
            mapOf("ST" to RENDERER_ST, "USN" to "uuid:slow::$RENDERER_ST", "LOCATION" to "http://127.0.0.1:1400/xml/d.xml"),
            InetSocketAddress(loopback, 1900),
        )
        val port = registry.renderers().single().port

        val t0 = System.nanoTime()
        registry.removeAll()
        val tookMs = (System.nanoTime() - t0) / 1_000_000

        assertTrue(tookMs < 2000, "removeAll took $tookMs ms for a 300 ms budget")
        assertEquals(1, logged.count { it["event"] == "destroy-slow" && it["udn"] == "slow" }, "one destroy-slow line: $logged")
        assertEquals(false, finished.get(), "the late destroy is still running")
        // the allocator still holds the port: a claim for the same udn must not hand it out
        val during = ports.claim("slow")
        assertNotEquals(port, during, "the port of a destroy that is still running is not handed out")
        ports.release(during)

        h.awaitUntil("the late destroy finished", 35_000) { finished.get() }
        h.awaitUntil("the port released", 5_000) { ports.claim("slow").also { ports.release(it) } == port }
    }
}
