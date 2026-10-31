package dev.t2u.renderer

// GUI-08 (plan 07-07): the "clear the speaker when a cast ends" setting. Off (the default): the last sender leaving resets
// the player and stops polling but sends no empty transport URI, so the speaker keeps the track loaded. On: today's CAST-06
// clear. The setting is read through a supplier at clear time, so a flip applies to the running bridge (D-09). The stop
// paths (D-03): switching the bridge off stops the speaker, and empties it only with the setting on.
// GPL-3.0-only.
//
// Every test is `= h.run<Unit> { ... }`: JUnit silently skips a test whose expression body returns a value.

import dev.t2u.lounge.Player
import dev.t2u.lounge.Video
import dev.t2u.lounge.Volume
import dev.t2u.upnp.RENDERER_ST
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val VID = "AAAAAAAAAAA"

class ClearOnCastEndTest {
    private val h = BridgeHarness()

    @AfterEach
    fun tearDown() = h.close()

    /** Starts [bridge], connects the sender and casts until the fake speaker holds one of our streams. */
    private suspend fun castUntilLoaded(bridge: Bridge) {
        bridge.start()
        h.launch(bridge)
        h.innerTube.scriptNext(VID, previousId = null, nextId = null)
        h.connectSender()
        h.rpc("cl", "setPlaylist", h.playlist(VID))
        h.awaitUntil("the speaker holding our stream") { h.fakeRenderer.state.currentUri.contains("/stream/") }
        h.awaitUntil("the speaker told to play") { h.speakerCount("Play") >= 1 }
    }

    /** The sender leaves (an explicit disconnect), and the player resets: the speaker gets its Stop. */
    private suspend fun senderLeaves() {
        val stopsBefore = h.speakerCount("Stop")
        h.rpc("cl", "loungeStatus", h.loungeStatus(emptyList(), "dev-1", ui = "true"))
        h.awaitUntil("the speaker told to stop after the sender left") { h.speakerCount("Stop") > stopsBefore }
    }

    private fun emptiedUris() = h.setAvTransportUris().count { it.isEmpty() }

    @Test
    @DisplayName("with the setting off the last sender leaving leaves the track loaded")
    fun offLeavesTheTrackLoaded() = h.run<Unit> {
        val bridge = h.newBridge(h.testConfig(clearOnCastEnd = false))
        castUntilLoaded(bridge)
        val loaded = h.fakeRenderer.state.currentUri

        senderLeaves()
        delay(600) // room for a clear that would follow the Stop

        assertEquals(0, emptiedUris(), "no empty SetAVTransportURI: ${h.setAvTransportUris()}")
        assertEquals(loaded, h.fakeRenderer.state.currentUri, "the speaker keeps the track")
    }

    @Test
    @DisplayName("with the setting on the last sender leaving clears the speaker")
    fun onClearsTheSpeaker() = h.run<Unit> {
        val bridge = h.newBridge(h.testConfig(clearOnCastEnd = true))
        castUntilLoaded(bridge)

        senderLeaves()
        h.awaitUntil("the speaker emptied") { h.fakeRenderer.state.currentUri.isEmpty() }

        assertEquals(1, emptiedUris(), "exactly one empty SetAVTransportURI: ${h.setAvTransportUris()}")
        assertTrue(h.speakerCount("Stop") >= 1)
    }

    @Test
    @DisplayName("the setting applies to the running bridge without a restart")
    fun flipAppliesLive() = h.run<Unit> {
        val setting = AtomicBoolean(false)
        val bridge = h.newBridge(h.testConfig(clearOnCastEnd = false), clearOnCastEnd = { setting.get() })
        castUntilLoaded(bridge)

        setting.set(true) // flipped after the bridge started, before the disconnect
        senderLeaves()
        h.awaitUntil("the speaker emptied") { h.fakeRenderer.state.currentUri.isEmpty() }

        assertEquals(1, emptiedUris(), "exactly one empty SetAVTransportURI: ${h.setAvTransportUris()}")
    }

    // ------------------------------------------------------------------------------------------ bridge stop paths (D-03)

    @Test
    @DisplayName("Bridge.stop with stopSpeakers and no clear stops a playing speaker and leaves its URI")
    fun stopSpeakersWithoutClear() = h.run<Unit> {
        val bridge = h.newBridge(h.testConfig(clearOnCastEnd = false))
        castUntilLoaded(bridge)
        val loaded = h.fakeRenderer.state.currentUri
        val stopsBefore = h.speakerCount("Stop")

        bridge.stop(clearSpeakers = false, stopSpeakers = true)

        assertTrue(h.speakerCount("Stop") > stopsBefore, "the speaker received a Stop: ${h.fakeRenderer.calls.map { it.action }}")
        assertEquals(0, emptiedUris(), "no empty SetAVTransportURI: ${h.setAvTransportUris()}")
        assertEquals(loaded, h.fakeRenderer.state.currentUri)
    }

    @Test
    @DisplayName("Bridge.stop with clearSpeakers empties the speaker as before")
    fun stopWithClearEmptiesTheSpeaker() = h.run<Unit> {
        val bridge = h.newBridge(h.testConfig(clearOnCastEnd = true))
        castUntilLoaded(bridge)
        val stopsBefore = h.speakerCount("Stop")

        bridge.stop(clearSpeakers = true, stopSpeakers = true)

        assertTrue(h.speakerCount("Stop") > stopsBefore, "the speaker received a Stop")
        assertEquals(1, emptiedUris(), "exactly one empty SetAVTransportURI: ${h.setAvTransportUris()}")
        assertEquals("", h.fakeRenderer.state.currentUri)
    }

    @Test
    @DisplayName("Bridge.stop without either flag does not clear the speaker (the reload-config path)")
    fun stopWithoutFlagsDoesNotClear() = h.run<Unit> {
        // Tearing a renderer down already stops its cast receiver, which resets the player and so sends Stop to a speaker that
        // is playing (YouTubeApp.stop, pre-existing). What the flags add is the bounded, ordered Stop (the registry-level tests below)
        // and the clear; with neither flag the speaker is left loaded.
        val bridge = h.newBridge(h.testConfig(clearOnCastEnd = true))
        castUntilLoaded(bridge)
        val loaded = h.fakeRenderer.state.currentUri

        bridge.stop()

        assertEquals(0, emptiedUris(), "no empty SetAVTransportURI: ${h.setAvTransportUris()}")
        assertEquals(loaded, h.fakeRenderer.state.currentUri)
    }

    // ------------------------------------------------------------------------------------------ registry level (stopOne)

    /** A speaker that is a Player as well, recording the Stop the registry sends it. */
    private class PlayingSpeaker(
        override val udn: String,
        override val address: String,
        override val port: Int,
        private val timeline: CopyOnWriteArrayList<String>,
        private val stopTakes: suspend () -> Unit = {},
    ) : Player(), ManagedRenderer {
        override val location = "http://$address:1400/xml/device_description.xml"
        override val name: String? = "Stub $udn"
        @Volatile override var lastSeen: Long = 0
        override fun refresh(maxAge: Int) {}
        override fun isStale(now: Long) = false
        override suspend fun init() = true
        override suspend fun destroy() { timeline.add("destroy:$udn") }
        override suspend fun doPlay(video: Video, position: Double) = true
        override suspend fun doPause() = true
        override suspend fun doResume() = true
        override suspend fun doStop(): Boolean {
            timeline.add("stop-start:$udn")
            stopTakes()
            timeline.add("stop-end:$udn")
            return true
        }
        override suspend fun doSeek(position: Double) = true
        override suspend fun doSetVolume(volume: Volume) = true
        override suspend fun doGetVolume() = Volume(10, false)
        override suspend fun doGetPosition() = 0.0
        override suspend fun doGetDuration() = 0.0
        override suspend fun clearSpeaker() { timeline.add("clear:$udn") }
    }

    private class StopRig(stopBudgetMs: Long = 4000, stopTakes: suspend () -> Unit = {}) {
        val timeline = CopyOnWriteArrayList<String>()
        val speakers = CopyOnWriteArrayList<PlayingSpeaker>()
        private val dir = Files.createTempDirectory("stop-registry-").toFile().also { it.deleteOnExit() }
        val registry = RendererRegistry(
            Config(dataDir = dir),
            PortRegistry(dir, 3000).load(),
            { udn, _, address, _, port -> PlayingSpeaker(udn, address, port, timeline, stopTakes).also { speakers.add(it) } },
            clearBudgetMs = stopBudgetMs,
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

        /** Puts the speaker in PLAYING, as after a cast. */
        suspend fun playing(udn: String) {
            speakers.first { it.udn == udn }.play(Video("dQw4w9WgXcQ"))
        }
    }

    @Test
    @DisplayName("removeAll(stopSpeakers) stops the playing speakers in parallel before they are destroyed, and clears none")
    fun registryStopsPlayingSpeakers() = runBlocking<Unit> {
        val rig = StopRig(stopTakes = { delay(300) })
        rig.alive("a", "192.168.50.41")
        rig.alive("b", "192.168.50.42")
        rig.alive("idle", "192.168.50.43")
        rig.playing("a")
        rig.playing("b")

        val t0 = System.nanoTime()
        rig.registry.removeAll(stopSpeakers = true)
        val ms = (System.nanoTime() - t0) / 1_000_000

        val tl = rig.timeline
        assertTrue(ms < 600, "took $ms ms, so the stops ran one after the other")
        for (u in listOf("a", "b")) {
            assertTrue(tl.indexOf("stop-end:$u") in 0 until tl.indexOf("destroy:$u"), tl.toString())
        }
        assertTrue("stop-start:idle" !in tl, "a speaker that plays nothing gets no Stop: $tl")
        assertTrue(tl.none { it.startsWith("clear:") }, "stopSpeakers alone clears nothing: $tl")
        assertEquals(0, rig.registry.count.value)
    }

    @Test
    @DisplayName("a Stop that never returns is cut at the budget and the speaker is still destroyed")
    fun hangingStopIsCut() = runBlocking<Unit> {
        val rig = StopRig(stopBudgetMs = 200, stopTakes = { delay(Long.MAX_VALUE) })
        rig.alive("a", "192.168.50.41")
        rig.playing("a")

        val t0 = System.nanoTime()
        rig.registry.removeAll(stopSpeakers = true)
        val ms = (System.nanoTime() - t0) / 1_000_000

        assertTrue(ms < 1500, "took $ms ms")
        assertTrue("destroy:a" in rig.timeline, rig.timeline.toString())
    }

    @Test
    @DisplayName("clearSpeakers wins over stopSpeakers: the clear (which stops) runs and no separate Stop is sent first")
    fun clearTakesOverTheStop() = runBlocking<Unit> {
        val rig = StopRig()
        rig.alive("a", "192.168.50.41")
        rig.playing("a")

        rig.registry.removeAll(clearSpeakers = true, stopSpeakers = true)

        assertEquals(listOf("clear:a", "destroy:a"), rig.timeline.filter { !it.startsWith("stop-") }, rig.timeline.toString())
        assertTrue("stop-start:a" !in rig.timeline, rig.timeline.toString())
    }
}
