package dev.t2u.renderer

// Plan 09-24 (D-05): characterization tests for the Bridge.kt paths the split will move. They pin today's behaviour of the manual
// speaker probes, the start, the housekeeping and repair block and the network move, on the loopback rig of BridgeHarness.
// Nothing here changes src/main; anything that looks odd is pinned and named in the plan summary. GPL-3.0-only.
//
// Every test is `= h.run<Unit> { ... }`: JUnit silently skips a test whose expression body returns a value.

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.t2u.dial.DialAccessPolicy
import dev.t2u.upnp.ListenSpec
import dev.t2u.upnp.SsdpEvents
import dev.t2u.upnp.SsdpResponder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.BindException
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BridgeCharacterizationTest {
    private val h = BridgeHarness()
    private val other: InetAddress = InetAddress.getByName("127.0.0.2")
    private val third: InetAddress = InetAddress.getByName("127.0.0.3")
    private val servers = CopyOnWriteArrayList<DescriptionServer>()
    private val blockers = CopyOnWriteArrayList<ServerSocket>()
    private val leaked = CopyOnWriteArrayList<SsdpResponder>()

    @AfterEach
    fun tearDown() {
        h.close()
        servers.forEach { it.close() }
        blockers.forEach { runCatching { it.close() } }
        leaked.forEach { runCatching { it.stop() } }
    }

    private fun after(marker: Int, kind: String) = h.events.drop(marker).filter { it.first == kind }.map { it.second }

    // ------------------------------------------------------------------------------------------ rigs

    /** A tiny HTTP server on loopback that answers every GET through [handler] (request number from 1, exchange). */
    private class DescriptionServer(private val handler: (Int, HttpExchange, CountDownLatch) -> Unit) {
        val requests = AtomicInteger()
        private val release = CountDownLatch(1)
        private val server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        val url: String get() = "http://127.0.0.1:${server.address.port}/desc.xml"
        val localhostUrl: String get() = "http://localhost:${server.address.port}/desc.xml"

        init {
            server.createContext("/") { ex ->
                val n = requests.incrementAndGet()
                try {
                    handler(n, ex, release)
                } finally {
                    ex.close()
                }
            }
            server.start()
        }

        fun close() {
            release.countDown()
            server.stop(0)
        }
    }

    private fun serve(handler: (Int, HttpExchange, CountDownLatch) -> Unit): DescriptionServer =
        DescriptionServer(handler).also { servers.add(it) }

    private fun HttpExchange.reply(body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        responseHeaders.add("Content-Type", "text/xml")
        sendResponseHeaders(200, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    /** A bridge whose multicast discovery goes to a port nobody listens on, so only a manual probe can find the speaker. */
    private fun blindBridge(
        manualAnswerTimeoutMs: Long? = null,
        manualSsdpPort: Int? = null,
        manual: ManualSpeakers? = null,
        manualProbeIntervalMs: Long? = null,
        manualDescriptionUrl: ((String) -> String)? = null,
        manualSsdpGraceMs: Long? = null,
    ): Bridge = h.newBridge(
        searchTarget = InetSocketAddress(h.loopback, h.freePort()),
        manualSsdpPort = manualSsdpPort ?: h.speakerPort,
        manualAnswerTimeoutMs = manualAnswerTimeoutMs,
        manual = manual,
        manualProbeIntervalMs = manualProbeIntervalMs,
        manualDescriptionUrl = manualDescriptionUrl,
        manualSsdpGraceMs = manualSsdpGraceMs,
    )

    private fun blocker(ip: InetAddress, port: Int): ServerSocket = ServerSocket().apply {
        reuseAddress = true
        bind(InetSocketAddress(ip, port))
    }.also { blockers.add(it) }

    private fun mediaPort(bridge: Bridge): Int = bridge.state()["mediaBase"].toString().trim('"').substringAfterLast(':').toInt()

    /** Runs [block] in a coroutine that is already cancelled when it starts, so its first cancellable suspension throws. */
    private suspend fun cancelledRun(block: suspend () -> Unit) {
        val run = h.scope.async {
            coroutineContext.job.cancel()
            block()
        }
        assertFailsWith<CancellationException> { run.await() }
    }

    // ------------------------------------------------------------------------------------------ manual probing

    @Test
    @DisplayName("an add before the start, or after the stop, says the bridge is not running and saves nothing")
    fun anAddOutsideTheLifetimeSaysTheBridgeIsNotRunning() = h.run<Unit> {
        val store = ManualSpeakers(h.dataDir).load()
        val bridge = blindBridge(manual = store)

        val before = bridge.addManual(h.loopback)
        assertEquals(ManualAddResult.NotUsable("The bridge is not running"), before)

        bridge.start()
        bridge.stop()
        val afterStop = bridge.addManual(h.loopback)
        assertEquals(ManualAddResult.NotUsable("The bridge is not running"), afterStop)

        assertTrue(store.entries.value.isEmpty(), "nothing is saved")
        assertEquals(0, h.searchesSeen.get(), "no search went to the speaker")
        val adds = h.events.filter { it.first == "manual-add" }.map { it.second }
        assertEquals(listOf("NotUsable", "NotUsable"), adds.map { it["result"] })
        assertEquals(listOf("ssdp", "ssdp"), adds.map { it["via"] })
    }

    @Test
    @DisplayName("an add that is cancelled before its search ends without a result or a saved speaker")
    fun anAddCancelledBeforeItsSearchEndsWithoutAResult() = h.run<Unit> {
        val store = ManualSpeakers(h.dataDir).load()
        val bridge = blindBridge(manual = store)
        bridge.start()

        cancelledRun { bridge.addManual(h.loopback) }

        assertEquals(0, h.searchesSeen.get(), "the search never left")
        assertTrue(h.events.none { it.first == "manual-add" }, "no manual-add is logged: ${h.events.takeLast(5)}")
        assertTrue(store.entries.value.isEmpty())
    }

    @Test
    @DisplayName("an add that waits when the bridge stops gives NotUsable")
    fun anAddThatWaitsWhenTheBridgeStopsGivesNotUsable() = h.run<Unit> {
        val bridge = blindBridge(manualAnswerTimeoutMs = 10_000, manualSsdpPort = h.freePort())
        bridge.start()

        val add = h.scope.async { bridge.addManual(h.loopback) }
        delay(300)
        assertTrue(add.isActive, "still waiting for an answer")
        bridge.stop()

        assertEquals(ManualAddResult.NotUsable("The bridge stopped"), withTimeout(5_000) { add.await() })
    }

    @Test
    @DisplayName("a description without AVTransport gives NotUsable and logs NotARenderer")
    fun aDescriptionWithoutAvTransportGivesNotUsable() = h.run<Unit> {
        h.speakerSearchDeaf = true
        val server = serve { _, ex, _ ->
            ex.reply(
                "<root><device><deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>" +
                    "<friendlyName>Bare</friendlyName><UDN>uuid:BARE-1</UDN></device></root>",
            )
        }
        val store = ManualSpeakers(h.dataDir).load()
        val bridge = blindBridge(manual = store, manualDescriptionUrl = { server.url }, manualSsdpGraceMs = 200)
        bridge.start()

        val result = bridge.addManual(h.loopback)

        assertEquals(ManualAddResult.NotUsable("It answered, but it is not a renderer that can play audio here: no AVTransport"), result)
        assertTrue(
            h.events.any { it.first == "manual-add-describe" && it.second["result"] == "NotARenderer" },
            "NotARenderer is logged: ${h.events.takeLast(8)}",
        )
        assertTrue(bridge.speakers.value.isEmpty())
        assertTrue(store.entries.value.isEmpty(), "nothing is saved")
    }

    @Test
    @DisplayName("an add whose description fetch is cancelled ends without a result")
    fun anAddWhoseDescriptionFetchIsCancelledEndsWithoutAResult() = h.run<Unit> {
        h.speakerSearchDeaf = true
        val server = serve { _, _, release -> release.await(30, TimeUnit.SECONDS) }
        val bridge = blindBridge(manualAnswerTimeoutMs = 20_000, manualDescriptionUrl = { server.url }, manualSsdpGraceMs = 200)
        bridge.start()

        val add = h.scope.async { bridge.addManual(h.loopback) }
        h.awaitUntil("the description request arrives") { server.requests.get() >= 1 }
        add.cancel()

        assertFailsWith<CancellationException> { withTimeout(5_000) { add.await() } }
        assertTrue(h.events.none { it.first == "manual-add" }, "no manual-add is logged: ${h.events.takeLast(5)}")
        assertTrue(h.events.none { it.first == "bridge-error" }, "a cancelled fetch is not a failure: ${h.events.takeLast(5)}")
    }

    @Test
    @DisplayName("a saved speaker whose description URLs name another host is refused over http and never fetched")
    fun aSavedSpeakerWithDescriptionUrlsOnAnotherHostIsRefused() = h.run<Unit> {
        val wrong = h.fakeRenderer.location.replace("127.0.0.1", "localhost")
        val store = ManualSpeakers(h.dataDir).load()
        assertTrue(store.add(ManualSpeaker("127.0.0.1", HARNESS_RENDERER_UDN, "Kitchen", wrong)))
        val bridge = blindBridge(manual = store, manualDescriptionUrl = { wrong }, manualSsdpPort = h.freePort())

        bridge.start()

        h.awaitUntil("the http probe is refused") {
            h.events.any { it.first == "manual-probe" && it.second["result"] == "Refused" && it.second["via"] == "http" }
        }
        assertEquals(0, h.fakeRenderer.descriptionRequests.get(), "no description was requested")
        val card = bridge.speakers.value.single()
        assertEquals(HARNESS_RENDERER_UDN, card.udn)
        assertFalse(card.online, "a saved speaker nothing reached is a greyed card")
    }

    @Test
    @DisplayName("stopping the bridge while a saved speaker's setup is in flight ends the probe quietly")
    fun stoppingWhileASavedSpeakersSetupIsInFlightEndsTheProbeQuietly() = h.run<Unit> {
        val xml = h.http.send(
            HttpRequest.newBuilder(java.net.URI(h.fakeRenderer.location)).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        ).body()
        // the first GET is the liveness fetch of the probe, the second is the registry's own fetch while it sets the speaker up
        val server = serve { n, ex, release -> if (n == 1) ex.reply(xml) else release.await(30, TimeUnit.SECONDS) }
        val store = ManualSpeakers(h.dataDir).load()
        assertTrue(store.add(ManualSpeaker("127.0.0.1", HARNESS_RENDERER_UDN, "Kitchen", server.url)))
        val bridge = blindBridge(manual = store, manualSsdpPort = h.freePort())
        bridge.start()
        h.awaitUntil("the registry's setup fetch is in flight") { server.requests.get() >= 2 }

        bridge.stop()

        assertTrue(
            h.events.none { it.first == "manual-probe" && it.second["result"] == "Answered" },
            "the probe never counted as answered: ${h.events.takeLast(8)}",
        )
        assertTrue(h.events.none { it.first == "bridge-error" && it.second["where"] == "manual-probe" }, "${h.events.takeLast(8)}")
    }

    @Test
    @DisplayName("a saved speaker's unicast search that cannot be built is logged as a manual-probe failure and its description still answers")
    fun aSavedSpeakersSearchThatCannotBeBuiltIsLoggedAsAFailure() = h.run<Unit> {
        val store = ManualSpeakers(h.dataDir).load()
        assertTrue(store.add(ManualSpeaker("127.0.0.1", HARNESS_RENDERER_UDN, "Kitchen", h.fakeRenderer.location)))
        // UDP port 70000 does not exist: InetSocketAddress refuses it, which is the one way a search send fails on the loopback rig
        val bridge = blindBridge(manual = store, manualSsdpPort = 70_000)

        bridge.start()

        h.awaitUntil("the search failure is logged") {
            h.events.any { it.first == "bridge-error" && it.second["where"] == "manual-probe" }
        }
        val failure = h.events.first { it.first == "bridge-error" && it.second["where"] == "manual-probe" }.second
        assertEquals("java.lang.IllegalArgumentException", failure["error"])
        assertTrue(
            h.events.none { it.first == "manual-probe" && it.second["result"] == "Sent" },
            "no search counted as sent: ${h.events.takeLast(8)}",
        )
        h.awaitUntil("the description answers for the saved speaker") {
            h.events.any { it.first == "manual-probe" && it.second["result"] == "Answered" && it.second["via"] == "http" }
        }
    }

    @Test
    @DisplayName("an add whose unicast search cannot be built logs a manual-add-search failure and falls back to the description")
    fun anAddWhoseSearchCannotBeBuiltLogsAFailureAndFallsBack() = h.run<Unit> {
        h.speakerSearchDeaf = true
        val store = ManualSpeakers(h.dataDir).load()
        val bridge = blindBridge(
            manual = store,
            manualSsdpPort = 70_000,
            manualDescriptionUrl = { h.fakeRenderer.location },
            manualSsdpGraceMs = 200,
        )
        bridge.start()

        val result = bridge.addManual(h.loopback)

        val failure = h.events.first { it.first == "bridge-error" && it.second["where"] == "manual-add-search" }.second
        assertEquals("java.lang.IllegalArgumentException", failure["error"])
        assertTrue(result is ManualAddResult.Added, "the description fetch still adds it: $result")
        assertEquals(0, h.searchesSeen.get(), "the search never left")
        assertEquals("http", h.events.last { it.first == "manual-add" }.second["via"])
    }

    // ------------------------------------------------------------------------------------------ start

    @Test
    @DisplayName("a start whose media port is taken throws, undoes itself and leaves the bridge not running")
    fun aStartWhoseMediaPortIsTakenIsUndone() = h.run<Unit> {
        val busyPort = blocker(h.loopback, h.freePort()).localPort
        val bridge = h.newBridge(config = h.testConfig().copy(mediaPort = busyPort))

        assertFailsWith<Exception> { bridge.start() }

        assertEquals("false", bridge.state()["running"].toString())
        assertTrue(bridge.ssdpPorts().isEmpty(), "no responder was left behind: ${bridge.ssdpPorts()}")
        assertTrue(
            h.events.any { it.first == "bridge-error" && it.second["where"] == "start" },
            "the failure is logged with where = start: ${h.events.takeLast(8)}",
        )
        assertTrue(h.events.any { it.first == "bridge" && it.second["event"] == "stopped" }, "the undo ran the stop")
        assertFailsWith<IllegalStateException> { bridge.start() }
    }

    @Test
    @DisplayName("a start whose responder cannot bind throws after the media server came up, and the undo frees the media port")
    fun aStartWhoseResponderCannotBindFreesTheMediaPort() = h.run<Unit> {
        val mediaPort = h.freePort()
        val bridge = h.newBridge(
            config = h.testConfig().copy(mediaPort = mediaPort),
            responderFactory = { _, _, _ -> throw BindException("injected: address in use") },
        )

        assertFailsWith<BindException> { bridge.start() }

        assertEquals("false", bridge.state()["running"].toString())
        assertTrue(bridge.ssdpPorts().isEmpty())
        val failure = h.events.single { it.first == "bridge-error" && it.second["where"] == "start" }.second
        assertEquals("java.net.BindException", failure["error"])
        assertTrue(h.events.none { it.first == "bridge" && it.second["event"] == "started" }, "it never reported started")
        assertTrue(h.events.any { it.first == "bridge" && it.second["event"] == "stopped" }, "the undo ran the stop")
        // the media server that came up before the responder failed is stopped again: its port can be bound
        ServerSocket(mediaPort, 1, h.loopback).close()
    }

    // ------------------------------------------------------------------------------------------ housekeeping and repair

    /** A reply socket whose send fails while [broken] is set: the send side of the responder is dead. */
    private class BreakableSocket(bind: InetAddress, private val broken: AtomicBoolean) : MulticastSocket(InetSocketAddress(bind, 0)) {
        override fun send(p: DatagramPacket) {
            if (broken.get()) throw IOException("injected: send side is dead")
            super.send(p)
        }
    }

    private fun healthySocket(bind: InetAddress, iface: NetworkInterface?): MulticastSocket =
        MulticastSocket(InetSocketAddress(bind, 0)).apply {
            timeToLive = 4
            if (iface != null) networkInterface = iface
        }

    /**
     * A responderFactory whose first responder has a [BreakableSocket] (deaf once [broken] is set) and whose later ones are
     * healthy. [failFrom] makes every build from that call number on throw a BindException; [hold] runs after a build.
     */
    private fun factory(
        broken: AtomicBoolean,
        calls: AtomicInteger,
        failFrom: Int = Int.MAX_VALUE,
        hold: (Int) -> Unit = {},
    ) = { net: BridgeNetwork, ev: SsdpEvents, policy: DialAccessPolicy ->
        val n = calls.incrementAndGet()
        if (n >= failFrom) throw BindException("injected: address in use")
        val built = SsdpResponder(
            net.ssdpListen,
            net.wifiIp,
            net.iface,
            ev,
            replyPolicy = policy::allowsSsdp,
            announcePolicy = policy::announceAllowed,
            notifyTarget = net.notifyTarget,
            openReplySocket = { bind, iface -> if (n == 1) BreakableSocket(bind, broken) else healthySocket(bind, iface) },
        )
        leaked.add(built)
        hold(n)
        built
    }

    @Test
    @DisplayName("a network supplier that throws counts as Wi-Fi present and as the same address, and a repair goes ahead")
    fun aThrowingNetworkSupplierCountsAsPresentAndUnchanged() = h.run<Unit> {
        val broken = AtomicBoolean(false)
        val calls = AtomicInteger(0)
        val bridge = h.newBridge(
            responderFactory = factory(broken, calls),
            currentNetwork = { throw IllegalStateException("injected: the system will not say") },
        )
        bridge.start()
        val launched = h.launch(bridge)
        broken.set(true)

        val marker = h.events.size
        assertTrue(bridge.housekeeping("adb"), "the pass runs: a supplier that throws is not 'no Wi-Fi'")

        assertTrue(after(marker, "housekeeping").none { it["skipped"] != null }, "${after(marker, "housekeeping")}")
        val repair = after(marker, "ssdp-repair").single()
        assertEquals("responder", repair["what"])
        assertEquals(true, repair["ok"], "the repair was not skipped as 'address changed'")
        assertEquals(2, calls.get())
        val reply = assertNotNull(h.dialSearchAt(bridge, h.loopback), "the rebuilt responder answers")
        assertTrue(assertNotNull(h.header(reply, "USN")).contains(launched.uuid))
    }

    @Test
    @DisplayName("Wi-Fi that vanishes between the first check and the probe step skips the probe and repairs nothing")
    fun wifiThatVanishesBetweenTheChecksSkipsTheProbe() = h.run<Unit> {
        val calls = AtomicInteger(0)
        // the first check of the pass sees Wi-Fi, every later one does not
        val bridge = h.newBridge(currentNetwork = { if (calls.incrementAndGet() == 1) h.network(h.loopback) else null })
        bridge.start()

        val marker = h.events.size
        assertTrue(bridge.housekeeping("adb"), "the pass still counts as run")

        val probe = after(marker, "ssdp-probe").single()
        assertEquals("no-wifi", probe["skipped"])
        assertEquals("adb", probe["trigger"])
        assertTrue(after(marker, "ssdp-repair").isEmpty())
        val done = after(marker, "housekeeping").single()
        assertEquals(true, done["ran"])
        assertEquals(0, done["repaired"])
    }

    @Test
    @DisplayName("a cancelled housekeeping pass propagates the cancellation and does not count as done")
    fun aCancelledHousekeepingPassPropagatesAndDoesNotCountAsDone() = h.run<Unit> {
        val bridge = h.newBridge()
        bridge.start()

        val marker = h.events.size
        cancelledRun { bridge.housekeeping("adb") }

        assertTrue(after(marker, "housekeeping").isEmpty(), "no housekeeping event: ${h.events.drop(marker)}")
        assertTrue(after(marker, "bridge-error").isEmpty(), "a cancelled pass is not a failure: ${h.events.drop(marker)}")
        assertTrue(bridge.housekeeping("adb"), "the next trigger runs, the cancelled pass stamped nothing")
    }

    @Test
    @DisplayName("a pass cancelled inside the responder rebuild leaves no responder installed and the next pass repairs it")
    fun aPassCancelledInsideTheResponderRebuildLeavesNoneInstalled() = h.run<Unit> {
        val broken = AtomicBoolean(false)
        val calls = AtomicInteger(0)
        val entered = AtomicBoolean(false)
        val release = CountDownLatch(1)
        val bridge = h.newBridge(
            responderFactory = factory(broken, calls, hold = { n ->
                // the second build is the repair's: hold it inside the repair step, then cancel the pass
                if (n == 2 && entered.compareAndSet(false, true)) release.await(10, TimeUnit.SECONDS)
            }),
        )
        bridge.start()
        h.launch(bridge)
        broken.set(true)

        val marker = h.events.size
        val pass = h.scope.async { bridge.housekeeping("adb") }
        h.awaitUntil("the repair is under way") { entered.get() }
        pass.cancel()
        release.countDown()
        assertFailsWith<CancellationException> { withTimeout(5_000) { pass.await() } }

        assertTrue(bridge.ssdpPorts().isEmpty(), "the cancelled repair installed nothing: ${bridge.ssdpPorts()}")
        assertTrue(after(marker, "ssdp-repair").isEmpty(), "no ssdp-repair event for the cancelled pass")
        assertTrue(after(marker, "housekeeping").isEmpty())

        val marker2 = h.events.size
        assertTrue(bridge.housekeeping("adb"), "the next pass is not skipped as recent")
        val repair = after(marker2, "ssdp-repair").single()
        assertEquals("responder", repair["what"])
        assertEquals(true, repair["ok"])
        assertNotNull(h.dialSearchAt(bridge, h.loopback), "the responder is back")
    }

    @Test
    @DisplayName("a responder that cannot be rebuilt is logged, counted and tried again by the next pass")
    fun aResponderThatCannotBeRebuiltIsLoggedAndTriedAgain() = h.run<Unit> {
        val broken = AtomicBoolean(false)
        val calls = AtomicInteger(0)
        val bridge = h.newBridge(responderFactory = factory(broken, calls, failFrom = 2))
        bridge.start()
        h.launch(bridge)
        broken.set(true)

        val marker = h.events.size
        assertTrue(bridge.housekeeping("adb"))

        assertTrue(after(marker, "bridge-error").any { it["where"] == "repair:responder" }, "${h.events.drop(marker)}")
        val repair = after(marker, "ssdp-repair").single()
        assertEquals("responder", repair["what"])
        assertEquals(false, repair["ok"])
        assertEquals("BindException", repair["error"])
        assertTrue(after(marker, "ssdp-probe").none { it["repaired"] == true }, "nothing was probed after the failed repair")
        assertEquals(1, after(marker, "housekeeping").single()["repaired"], "the attempt counts as a repair")
        assertTrue(bridge.ssdpPorts().isEmpty(), "no responder is installed: ${bridge.ssdpPorts()}")

        val marker2 = h.events.size
        assertTrue(bridge.housekeeping("adb"))
        assertEquals("responder", after(marker2, "ssdp-repair").single()["what"], "a missing responder is rebuilt again: $calls")
    }

    @Test
    @DisplayName("a client that cannot search is not rebuilt while the system address differs from the bridge's")
    fun aDeadClientIsNotRebuiltWhileTheAddressDiffers() = h.run<Unit> {
        val bridge = h.newBridge(currentNetwork = { h.network(other) })
        bridge.start()
        h.awaitUntil("the start-up search") { h.searchesSeen.get() >= 1 }
        assertNotNull(bridge.ssdpClientForTest()).stop()

        val marker = h.events.size
        assertTrue(bridge.housekeeping("adb"))

        val repair = after(marker, "ssdp-repair").single()
        assertEquals("client", repair["what"])
        assertEquals("address-changed", repair["skipped"])
        assertEquals("adb", repair["trigger"])
        assertFalse(assertNotNull(bridge.ssdpClientForTest()).isBound(), "the dead client was left in place")
        assertEquals(0, after(marker, "housekeeping").single()["repaired"])
    }

    @Test
    @DisplayName("a move to an address nothing can bind leaves no client, no responder, no media listener and no DIAL server, and housekeeping tries each")
    fun aMoveToAnAddressNothingCanBindIsRetriedByHousekeeping() = h.run<Unit> {
        val unbindable = InetAddress.getByName("192.0.2.1")
        val bridge = h.newBridge(mediaRebindBackoffMs = emptyList())
        bridge.start()
        h.launch(bridge)
        // loopback listen spec and search target, but a Wi-Fi address that is not on this machine
        val moved = BridgeNetwork(
            wifiIp = unbindable,
            iface = null,
            ssdpListen = listOf(ListenSpec("loop", h.loopback, 0, null, false)),
            ssdpSearchTarget = InetSocketAddress(h.loopback, h.speakerPort),
            notifyTarget = null,
        )

        bridge.networkLost()
        bridge.rebindNetwork(moved)

        assertNull(bridge.ssdpClientForTest(), "a client that could not bind is not installed")
        val rebind = h.events.filter { it.first == "bridge-error" }.map { it.second["where"] }
        assertTrue("rebind:media" in rebind, "the media step failed and was logged: $rebind")
        assertTrue("rebind:dial" in rebind, "the DIAL step failed and was logged: $rebind")

        val marker = h.events.size
        assertTrue(bridge.housekeeping("adb"))

        val where = after(marker, "bridge-error").map { it["where"] }
        assertTrue("repair:media" in where, "the media server was tried again: $where")
        assertTrue("repair:dial" in where, "the DIAL server was tried again: $where")
        val client = after(marker, "ssdp-repair").single { it["what"] == "client" }
        assertEquals(false, client["ok"], "the new client could not bind either")
        assertEquals(false, after(marker, "media-repair").single()["ok"])
        assertEquals(false, after(marker, "dial-repair").single()["ok"])
        assertNull(bridge.ssdpClientForTest())
    }

    // ------------------------------------------------------------------------------------------ media and DIAL repair

    /** A bridge on loopback with a running receiver, moved to 127.0.0.2 while that address's media and DIAL ports are held. */
    private suspend fun movedWithBusyPorts(
        holdMedia: Boolean = true,
        currentNetwork: () -> BridgeNetwork?,
    ): Bridge {
        val bridge = h.newBridge(mediaRebindBackoffMs = emptyList(), currentNetwork = currentNetwork)
        bridge.start()
        val launched = h.launch(bridge)
        if (holdMedia) blocker(other, mediaPort(bridge))
        blocker(other, launched.port)
        bridge.networkLost()
        bridge.rebindNetwork(h.network(other))
        return bridge
    }

    @Test
    @DisplayName("media and DIAL repairs are skipped while the system address differs from the bridge's")
    fun mediaAndDialRepairsAreSkippedWhileTheAddressDiffers() = h.run<Unit> {
        val system = AtomicReference<BridgeNetwork?>(h.network(h.loopback))
        val bridge = movedWithBusyPorts { system.get() }
        system.set(h.network(third))

        val marker = h.events.size
        assertTrue(bridge.housekeeping("adb"))

        val media = after(marker, "media-repair").single()
        assertEquals("address-changed", media["skipped"])
        assertEquals("adb", media["trigger"])
        val dial = after(marker, "dial-repair").single()
        assertEquals("address-changed", dial["skipped"])
        assertEquals(0, after(marker, "housekeeping").single()["repaired"])
    }

    @Test
    @DisplayName("media and DIAL repairs that fail are logged with the error name and counted, and succeed once the address is free")
    fun failedMediaAndDialRepairsAreLoggedAndCounted() = h.run<Unit> {
        val system = AtomicReference<BridgeNetwork?>(h.network(h.loopback))
        val bridge = movedWithBusyPorts { system.get() }
        system.set(h.network(other))

        val marker = h.events.size
        assertTrue(bridge.housekeeping("adb"))

        val media = after(marker, "media-repair").single()
        assertEquals(false, media["ok"])
        assertEquals("BindException", media["error"])
        val dial = after(marker, "dial-repair").single()
        assertEquals(false, dial["ok"])
        assertEquals("BindException", dial["error"])
        val where = after(marker, "bridge-error").map { it["where"] }
        assertTrue("repair:media" in where && "repair:dial" in where, "$where")
        assertEquals(2, after(marker, "housekeeping").single()["repaired"])

        blockers.forEach { it.close() }
        val marker2 = h.events.size
        assertTrue(bridge.housekeeping("adb"))
        assertEquals(true, after(marker2, "media-repair").single()["ok"])
        assertEquals(true, after(marker2, "dial-repair").single()["ok"])
    }

    /** The pass blocks in the network supplier on its [blockOn]-th call, is cancelled there and is then let go. */
    private suspend fun cancelledInsideRepair(blockOn: Int, holdMedia: Boolean): Pair<Int, Bridge> {
        val system = AtomicReference<BridgeNetwork?>(h.network(h.loopback))
        val armed = AtomicBoolean(false)
        val calls = AtomicInteger(0)
        val blocked = AtomicBoolean(false)
        val release = CountDownLatch(1)
        val bridge = movedWithBusyPorts(
            currentNetwork = {
                if (armed.get() && calls.incrementAndGet() == blockOn) {
                    blocked.set(true)
                    release.await(10, TimeUnit.SECONDS)
                }
                system.get()
            },
            holdMedia = holdMedia,
        )
        system.set(h.network(other))
        val marker = h.events.size
        armed.set(true)
        val pass = h.scope.async { bridge.housekeeping("adb") }
        h.awaitUntil("the pass reaches the repair") { blocked.get() }
        pass.cancel()
        release.countDown()
        assertFailsWith<CancellationException> { withTimeout(5_000) { pass.await() } }
        return marker to bridge
    }

    @Test
    @DisplayName("a pass cancelled inside the media repair logs no media-repair event and does not count as done")
    fun aPassCancelledInsideTheMediaRepairLogsNothing() = h.run<Unit> {
        // supplier calls of a pass: its first check, runHousekeeping's check, the probe step's check, then the media repair's
        val (marker, bridge) = cancelledInsideRepair(blockOn = 4, holdMedia = true)

        assertTrue(after(marker, "media-repair").isEmpty(), "${h.events.drop(marker)}")
        assertTrue(after(marker, "dial-repair").isEmpty())
        assertTrue(after(marker, "housekeeping").isEmpty())
        assertTrue(after(marker, "bridge-error").isEmpty(), "a cancelled repair is not a failure: ${h.events.drop(marker)}")
        assertTrue(bridge.housekeeping("adb"), "the next pass is not skipped as recent")
    }

    @Test
    @DisplayName("a pass cancelled inside the DIAL repair logs no dial-repair event and does not count as done")
    fun aPassCancelledInsideTheDialRepairLogsNothing() = h.run<Unit> {
        // the media server is not held, so the move rebound it and its repair returns before asking the supplier
        val (marker, bridge) = cancelledInsideRepair(blockOn = 4, holdMedia = false)

        assertTrue(after(marker, "dial-repair").isEmpty(), "${h.events.drop(marker)}")
        assertTrue(after(marker, "housekeeping").isEmpty())
        assertTrue(after(marker, "bridge-error").isEmpty(), "a cancelled repair is not a failure: ${h.events.drop(marker)}")
        assertTrue(bridge.housekeeping("adb"), "the next pass is not skipped as recent")
    }

    // ------------------------------------------------------------------------------------------ network move

    @Test
    @DisplayName("a move cancelled at its media step leaves the bridge on its old address and the next move runs")
    fun aMoveCancelledAtItsMediaStepLeavesTheOldAddress() = h.run<Unit> {
        val bridge = h.newBridge()
        bridge.start()
        val port = mediaPort(bridge)
        bridge.networkLost()

        cancelledRun { bridge.rebindNetwork(h.network(other)) }

        assertEquals("127.0.0.1", bridge.state()["wifiIp"].toString().trim('"'), "the bridge did not move")
        assertTrue(h.events.none { it.first == "bridge" && it.second["event"] == "rebound" })
        assertTrue(h.events.none { it.first == "bridge-error" && it.second["where"].toString().startsWith("rebind") }, "${h.events.takeLast(8)}")

        bridge.rebindNetwork(h.network(other))

        assertEquals("127.0.0.2", bridge.state()["wifiIp"].toString().trim('"'), "the network lock was released")
        assertTrue(h.events.any { it.first == "bridge" && it.second["event"] == "rebound" })
        assertEquals(404, h.http.send(
            HttpRequest.newBuilder(java.net.URI("http://127.0.0.2:$port/stream/${"0".repeat(32)}.aac")).GET().build(),
            HttpResponse.BodyHandlers.discarding(),
        ).statusCode(), "the media server answers on the new address")
    }

    @Test
    @DisplayName("a move stopped by the bridge's stop during the media backoff ends without a rebound")
    fun aMoveStoppedDuringTheMediaBackoffEndsWithoutARebound() = h.run<Unit> {
        val bridge = h.newBridge(mediaRebindBackoffMs = listOf(1_500L))
        bridge.start()
        blocker(other, mediaPort(bridge))
        bridge.networkLost()

        val move = h.scope.async { bridge.rebindNetwork(h.network(other)) }
        h.awaitUntil("the first media attempt failed") { h.events.any { it.first == "media-rebind" && it.second["ok"] == false } }
        bridge.stop()

        withTimeout(15_000) { move.await() }
        assertEquals(1, h.events.count { it.first == "media-rebind" }, "no second attempt after the stop: ${h.events.takeLast(8)}")
        assertTrue(h.events.any { it.first == "bridge" && it.second["event"] == "stopped" })
    }
}
