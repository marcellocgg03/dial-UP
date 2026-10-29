package dev.t2u.renderer

// DISC-01, CAST-01: the discovery lifecycle of index.js (onAlive, onByebye, prune, schedules) over the
// ManagedRenderer contract, tested with fakes. GPL-3.0-only.

import dev.t2u.upnp.EventSink
import dev.t2u.upnp.RENDERER_ST
import dev.t2u.upnp.Ssdp
import dev.t2u.upnp.SsdpClientEvents
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What the registry needs of a speaker, recording the calls it receives. */
private class FakeManagedRenderer(
    override val udn: String,
    override val location: String,
    override val address: String,
    maxAge: Int,
    override val port: Int,
    override val name: String?,
    private val behaviour: suspend () -> Boolean,
) : ManagedRenderer {
    @Volatile var maxAgeS = maxAge
    @Volatile override var lastSeen: Long = 0
    val staleChecks = java.util.concurrent.atomic.AtomicInteger()
    val initCalls = java.util.concurrent.atomic.AtomicInteger()
    val destroyCalls = java.util.concurrent.atomic.AtomicInteger()
    val refreshCalls = java.util.concurrent.atomic.AtomicInteger()
    val reviveCalls = java.util.concurrent.atomic.AtomicInteger()
    @Volatile var reviveResult: suspend (Long) -> Boolean? = { null }

    override suspend fun reviveIfDue(now: Long): Boolean? {
        reviveCalls.incrementAndGet()
        return reviveResult(now)
    }

    override fun refresh(maxAge: Int) {
        refreshCalls.incrementAndGet()
        maxAgeS = maxAge
    }

    override fun isStale(now: Long): Boolean {
        staleChecks.incrementAndGet()
        return now - lastSeen > maxAgeS * 1000L * 1.5
    }

    override suspend fun init(): Boolean {
        initCalls.incrementAndGet()
        return behaviour()
    }

    override suspend fun destroy() {
        destroyCalls.incrementAndGet()
    }
}

class RendererRegistryTest {
    private val loopback: InetAddress = InetAddress.getByName("127.0.0.1")

    private fun tempDir(): File = Files.createTempDirectory("registry-").toFile().also { it.deleteOnExit() }

    private class Events : EventSink {
        val list = CopyOnWriteArrayList<Map<String, Any?>>()
        val retries = CopyOnWriteArrayList<Map<String, Any?>>()

        override fun event(kind: String, fields: Map<String, Any?>) {
            if (kind == "discovery") list.add(fields)
            if (kind == "retry") retries.add(fields)
        }

        fun named(event: String) = list.filter { it["event"] == event }
    }

    @Test
    @DisplayName("a search reply from a speaker on loopback creates one cast target with a claimed port")
    fun tracerSearchReplyCreatesOneRenderer() = runBlocking {
        val dataDir = tempDir()
        val ports = PortRegistry(dataDir, 3000).load()
        val created = CopyOnWriteArrayList<FakeManagedRenderer>()
        val registry = RendererRegistry(
            Config(dataDir = dataDir),
            ports,
            { udn, location, address, maxAge, port ->
                FakeManagedRenderer(udn, location, address, maxAge, port, "Fake $udn") { true }.also { created.add(it) }
            },
        )

        // the fake speaker: any M-SEARCH is answered with a MediaRenderer 200 reply
        val speakerSock = DatagramSocket(0, loopback).apply { soTimeout = 200 }
        val running = java.util.concurrent.atomic.AtomicBoolean(true)
        val speakerThread = Thread {
            val buf = ByteArray(2048)
            while (running.get()) {
                val p = DatagramPacket(buf, buf.size)
                try {
                    speakerSock.receive(p)
                } catch (_: SocketTimeoutException) {
                    continue
                } catch (_: Exception) {
                    break
                }
                val reply = (
                    "HTTP/1.1 200 OK\r\nST: $RENDERER_ST\r\nUSN: uuid:Fake-Renderer::$RENDERER_ST\r\n" +
                        "LOCATION: http://127.0.0.1:1400/xml/device_description.xml\r\nCACHE-CONTROL: max-age=1800\r\n\r\n"
                    ).toByteArray()
                speakerSock.send(DatagramPacket(reply, reply.size, p.address, p.port))
            }
        }.apply { isDaemon = true; start() }

        val ssdp = Ssdp(
            listOf(loopback),
            object : SsdpClientEvents {
                override fun onAlive(headers: Map<String, String>, src: InetSocketAddress) {
                    runBlocking { registry.onAlive(headers, src) }
                }
            },
            InetSocketAddress(speakerSock.localAddress, speakerSock.localPort),
        )
        try {
            ssdp.start()
            ssdp.search(RENDERER_ST)
            val end = System.currentTimeMillis() + 3000
            while (registry.count.value == 0 && System.currentTimeMillis() < end) Thread.sleep(10)
            Thread.sleep(100)

            assertEquals(1, registry.count.value)
            assertEquals(1, created.size)
            val r = created.single()
            assertEquals("fake-renderer", r.udn)
            assertEquals(3000, r.port)
            assertEquals(1, r.initCalls.get())
            assertEquals(listOf<ManagedRenderer>(r), registry.renderers())
            assertEquals(mapOf("fake-renderer" to 3000), ports.saved())
            assertTrue(File(dataDir, "ports.json").readText().contains("fake-renderer"))
        } finally {
            ssdp.stop()
            running.set(false)
            speakerSock.close()
            speakerThread.join(2000)
        }
    }

    // ---- Task 2: the lifecycle of index.js onAlive / onByebye / prune and the schedules ----

    private val kitchenLocation = "http://192.168.50.42:1400/xml/device_description.xml"

    private inner class Harness(
        val dataDir: File = tempDir(),
        val now: AtomicLong = AtomicLong(1_000_000),
        val friendlyName: String? = null,
        var initBehaviour: suspend (String) -> Boolean = { true },
    ) {
        val events = Events()
        val ports = PortRegistry(dataDir, 3000).load()
        val created = CopyOnWriteArrayList<FakeManagedRenderer>()
        val registry = RendererRegistry(
            Config(dataDir = dataDir),
            ports,
            { udn, location, address, maxAge, port ->
                FakeManagedRenderer(udn, location, address, maxAge, port, friendlyName ?: "Fake $udn") { initBehaviour(udn) }
                    .also {
                        it.lastSeen = now.get()
                        created.add(it)
                    }
            },
            events,
            { now.get() },
        )

        suspend fun alive(
            udn: String = "Kitchen",
            location: String = kitchenLocation,
            src: String = "192.168.50.42",
            cacheControl: String? = "max-age=1800",
            st: String = RENDERER_ST,
            extra: Map<String, String> = emptyMap(),
            usn: String? = "uuid:$udn::$RENDERER_ST",
        ) {
            val h = LinkedHashMap<String, String>()
            h["ST"] = st
            if (usn != null) h["USN"] = usn
            if (location.isNotEmpty()) h["LOCATION"] = location
            if (cacheControl != null) h["CACHECONTROL"] = cacheControl
            h.putAll(extra)
            registry.onAlive(h, InetSocketAddress(InetAddress.getByName(src), 1900))
        }

        suspend fun byebye(udn: String) = registry.onByebye(mapOf("USN" to "uuid:$udn::$RENDERER_ST", "NTS" to "ssdp:byebye"))
    }

    @Test
    @DisplayName("100 alives for one UDN and LOCATION create one renderer, init once and log one added event")
    fun repeatedAlivesRefreshOnly() = runBlocking {
        val h = Harness()
        repeat(100) { h.alive() }
        assertEquals(1, h.created.size)
        val r = h.created.single()
        assertEquals(1, r.initCalls.get())
        assertEquals(99, r.refreshCalls.get())
        assertEquals(1, h.registry.count.value)
        assertEquals(1, h.events.list.size)
        assertEquals(1, h.events.named("added").size)
    }

    @Test
    @DisplayName("the same UDN with a new LOCATION destroys the old renderer and recreates it on the same remembered port")
    fun movedLocationRecreates() = runBlocking {
        val h = Harness()
        h.alive()
        val first = h.created.single()
        h.alive(location = "http://192.168.50.42:51161/other.xml")
        assertEquals(2, h.created.size)
        val second = h.created[1]
        assertEquals(1, first.destroyCalls.get())
        assertEquals(3000, second.port)
        assertEquals(3000, first.port)
        assertEquals(1, h.registry.count.value)
        assertEquals(listOf<ManagedRenderer>(second), h.registry.renderers())
        assertEquals(1, h.events.named("moved").size)
        assertEquals(2, h.events.named("added").size)
    }

    @Test
    @DisplayName("a byebye for a known UDN destroys it and releases its port; for an unknown UDN it does nothing")
    fun byebyeRemoves() = runBlocking {
        val h = Harness()
        h.alive()
        h.byebye("Nobody")
        assertEquals(1, h.registry.count.value)
        assertEquals(1, h.events.list.size)

        h.byebye("Kitchen")
        assertEquals(0, h.registry.count.value)
        assertEquals(1, h.created.single().destroyCalls.get())
        assertEquals(1, h.events.named("removed").size)
        // released: the speaker's remembered port is free to be claimed again
        assertEquals(3000, h.ports.claim("kitchen"))
        // and a second byebye finds nothing
        h.byebye("Kitchen")
        assertEquals(1, h.created.single().destroyCalls.get())
    }

    @Test
    @DisplayName("a renderer last seen exactly maxAge x 1.5 ago is kept and one more millisecond prunes it")
    fun pruneBoundary() = runBlocking {
        val h = Harness()
        h.alive()
        h.now.addAndGet(1800L * 1500L)
        h.registry.prune()
        assertEquals(1, h.registry.count.value)
        h.now.addAndGet(1)
        h.registry.prune()
        assertEquals(0, h.registry.count.value)
        assertEquals(1, h.created.single().destroyCalls.get())
        assertEquals(1, h.events.named("stale").size)
    }

    @Test
    @DisplayName("an init that throws releases the port, is ignored for 5 minutes and then retried")
    fun failedInitRetriesAfterFiveMinutes() = runBlocking {
        val h = Harness(initBehaviour = { throw java.io.IOException("description fetch failed") })
        h.alive()
        assertEquals(1, h.created.size)
        assertEquals(1, h.created[0].destroyCalls.get())
        assertEquals(0, h.registry.count.value)
        assertEquals(1, h.events.named("failed").size)
        assertTrue(h.ports.saved().isEmpty())

        h.now.addAndGet(RendererRegistry.RETRY_FAILED_MS - 60_000)
        h.alive()
        assertEquals(1, h.created.size)

        h.now.addAndGet(60_000)
        // port 3000 was released by the failure, so the retry claims it again
        h.initBehaviour = { true }
        h.alive()
        assertEquals(2, h.created.size)
        assertEquals(3000, h.created[1].port)
        assertEquals(1, h.registry.count.value)
    }

    @Test
    @DisplayName("an init that returns false releases the port, remembers nothing and is never retried for that LOCATION")
    fun rejectedInitIsNeverRetried() = runBlocking {
        val h = Harness(initBehaviour = { false })
        h.alive()
        assertEquals(1, h.created.size)
        assertEquals(0, h.registry.count.value)
        assertTrue(h.ports.saved().isEmpty())
        assertFalse(File(h.dataDir, "ports.json").exists())
        assertEquals(3000, h.ports.claim("probe"))
        h.ports.release(3000)

        repeat(5) {
            h.now.addAndGet(60L * 60 * 1000)
            h.alive()
        }
        assertEquals(1, h.created.size)
        // a different LOCATION for the same UDN is a new device description and is tried
        h.alive(location = "http://192.168.50.42:1401/xml/device_description.xml")
        assertEquals(2, h.created.size)
    }

    @Test
    @DisplayName("two concurrent alives for a new UDN create one renderer")
    fun concurrentAlivesCreateOne() = runBlocking {
        val gate = CompletableDeferred<Boolean>()
        val h = Harness(initBehaviour = { gate.await() })
        coroutineScope {
            val jobs = List(2) { async(Dispatchers.Default) { h.alive() } }
            val end = System.currentTimeMillis() + 3000
            while (h.created.isEmpty() && System.currentTimeMillis() < end) delay(5)
            delay(150)
            gate.complete(true)
            jobs.awaitAll()
        }
        assertEquals(1, h.created.size)
        assertEquals(1, h.created.single().initCalls.get())
        assertEquals(1, h.registry.count.value)
    }

    @Test
    @DisplayName("two speakers with the same friendly name get two receivers on ports 3000 and 3001")
    fun sameNameTwoReceivers() = runBlocking {
        val h = Harness(friendlyName = "Living Room")
        h.alive(udn = "AAA")
        h.alive(udn = "BBB", location = "http://192.168.50.43:1400/xml/device_description.xml", src = "192.168.50.43")
        assertEquals(2, h.registry.count.value)
        assertEquals(listOf("aaa" to 3000, "bbb" to 3001), h.registry.renderers().map { it.udn to it.port })
        assertEquals(listOf("Living Room", "Living Room"), h.registry.renderers().map { it.name })
    }

    @Test
    @DisplayName("after a restart speakers alive in reverse order keep their earlier ports")
    fun portsStableAcrossRestart() = runBlocking {
        val dataDir = tempDir()
        val first = Harness(dataDir = dataDir)
        val names = listOf("Kitchen" to 42, "Lamp" to 43, "Deck" to 44)
        for ((n, host) in names) first.alive(udn = n, location = "http://192.168.50.$host:1400/d.xml", src = "192.168.50.$host")
        assertEquals(mapOf("kitchen" to 3000, "lamp" to 3001, "deck" to 3002), first.ports.saved())

        val second = Harness(dataDir = dataDir)
        for ((n, host) in names.reversed()) second.alive(udn = n, location = "http://192.168.50.$host:1400/d.xml", src = "192.168.50.$host")
        assertEquals(
            mapOf("deck" to 3002, "lamp" to 3001, "kitchen" to 3000),
            second.registry.renderers().associate { it.udn to it.port },
        )
    }

    @Test
    @DisplayName("an alive whose LOCATION host is not its sender, or is not a private address, never reaches the factory")
    fun locationGuards() = runBlocking {
        val h = Harness()
        repeat(5) {
            h.alive(location = "http://192.168.50.99:1400/x.xml", src = "192.168.50.42")
            h.alive(udn = "Evil", location = "http://8.8.8.8/x.xml", src = "8.8.8.8")
            h.alive(udn = "Named", location = "http://speaker.example/x.xml", src = "192.168.50.42")
        }
        assertEquals(0, h.created.size)
        assertEquals(0, h.registry.count.value)
        // one event per UDN and reason, not per datagram
        assertEquals(3, h.events.named("skipped").size)
    }

    @Test
    @DisplayName("an alive from a QuickShareUPnP device never reaches the factory and is logged once")
    fun quickShareExcluded() = runBlocking {
        val h = Harness()
        repeat(20) { h.alive(udn = "Phone", extra = mapOf("SERVER" to "Android/14 QuickShareUPnP/1.0")) }
        h.alive(udn = "Phone", extra = mapOf("USERAGENT" to "quicksHAREupnp"))
        assertEquals(0, h.created.size)
        assertEquals(1, h.events.named("skipped").size)
        // a real speaker with another SERVER header is unaffected
        h.alive(extra = mapOf("SERVER" to "Linux UPnP/1.0 Sonos/80.1 (ZPS9)"))
        assertEquals(1, h.created.size)
    }

    @Test
    @DisplayName("an alive whose ST or NT is not a MediaRenderer, or that has no USN or LOCATION, is ignored")
    fun malformedAlivesIgnored() = runBlocking {
        val h = Harness()
        h.alive(st = "urn:schemas-upnp-org:device:ZonePlayer:1")
        h.alive(usn = null)
        h.alive(location = "")
        h.alive(usn = "no-uuid-here")
        assertEquals(0, h.created.size)
        assertTrue(h.events.list.isEmpty())
        // a NOTIFY carries the type in NT, not ST
        h.registry.onAlive(
            mapOf("NT" to RENDERER_ST, "USN" to "uuid:Kitchen::$RENDERER_ST", "LOCATION" to kitchenLocation),
            InetSocketAddress(InetAddress.getByName("192.168.50.42"), 1900),
        )
        assertEquals(1, h.created.size)
    }

    @Test
    @DisplayName("removeAll destroys every renderer and empties the list")
    fun removeAllDestroysEverything() = runBlocking {
        val h = Harness()
        h.alive(udn = "A")
        h.alive(udn = "B", location = "http://192.168.50.43:1400/d.xml", src = "192.168.50.43")
        assertEquals(2, h.registry.renderersFlow.value.size)
        h.registry.removeAll()
        assertEquals(0, h.registry.count.value)
        assertTrue(h.registry.renderersFlow.value.isEmpty())
        assertEquals(listOf(1, 1), h.created.map { it.destroyCalls.get() })
    }

    @Test
    @DisplayName("launchSchedules searches at 0 s, 5 s, 300 s and 600 s and prunes every 60 s")
    fun schedules() = runTest {
        val h = Harness()
        h.alive()
        val renderer = h.created.single()
        val searchTimes = CopyOnWriteArrayList<Long>()
        val job = h.registry.launchSchedules(this) { searchTimes.add(testScheduler.currentTime / 1000) }
        runCurrent()
        assertEquals(listOf(0L), searchTimes.toList())
        assertEquals(0, renderer.staleChecks.get())

        advanceTimeBy(59_999)
        assertEquals(0, renderer.staleChecks.get())
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, renderer.staleChecks.get())

        advanceTimeBy(600_000 - 60_000 + 1)
        runCurrent()
        assertEquals(listOf(0L, 5L, 300L, 600L), searchTimes.toList())
        assertEquals(10, renderer.staleChecks.get())
        job.cancel()
    }

    // ---- Plan 05-04 Task 1: the 5-minute retry of a failed setup (D-05) ----

    @Test
    @DisplayName("a setup that failed is retried by the timer every 5 minutes without a new alive until it works")
    fun retryTimerRetriesFailedSetupForever() = runBlocking {
        val failures = java.util.concurrent.atomic.AtomicInteger()
        val h = Harness(initBehaviour = { if (failures.incrementAndGet() <= 3) throw java.io.IOException("boom") else true })
        h.alive()
        assertEquals(1, failures.get())
        assertEquals(0, h.registry.count.value)

        h.now.addAndGet(4 * 60_000L)
        assertEquals(0, h.registry.retryDue())
        assertEquals(1, h.created.size)
        assertTrue(h.events.retries.isEmpty())

        h.now.addAndGet(60_000L)
        assertEquals(1, h.registry.retryDue())
        assertEquals(2, h.created.size)
        assertEquals(1, h.events.retries.size)
        val first = h.events.retries[0]
        assertEquals("failed", first["reason"])
        assertEquals("Kitchen".lowercase(), (first["udn"] as String).lowercase())
        assertEquals(kitchenLocation, first["location"])
        assertEquals(1, first["attempt"])
        assertEquals("failed", first["outcome"])
        assertEquals("boom", first["error"])

        // not due again until 5 more minutes have passed
        h.now.addAndGet(4 * 60_000L)
        assertEquals(0, h.registry.retryDue())
        h.now.addAndGet(60_000L)
        assertEquals(1, h.registry.retryDue())
        assertEquals(2, h.events.retries[1]["attempt"])
        assertEquals("failed", h.events.retries[1]["outcome"])

        h.now.addAndGet(RendererRegistry.RETRY_FAILED_MS)
        assertEquals(1, h.registry.retryDue())
        assertEquals(3, h.events.retries[2]["attempt"])
        assertEquals("added", h.events.retries[2]["outcome"])
        assertEquals(1, h.registry.count.value)
        // remembered port is claimed again and the skip is gone
        assertEquals(3000, h.created.last().port)
        h.now.addAndGet(RendererRegistry.RETRY_FAILED_MS * 3)
        assertEquals(0, h.registry.retryDue())
        assertEquals(4, h.created.size)
        assertEquals(1, h.events.named("added").size)
        assertEquals(3, h.events.named("failed").size)
    }

    @Test
    @DisplayName("a device whose setup returned false is never attempted by the retry timer")
    fun retryNeverTouchesRejectedDevice() = runBlocking {
        val h = Harness(initBehaviour = { false })
        h.alive()
        assertEquals(1, h.created.size)
        repeat(5) {
            h.now.addAndGet(60L * 60 * 1000)
            assertEquals(0, h.registry.retryDue())
        }
        assertEquals(1, h.created.size)
        assertTrue(h.events.retries.isEmpty())
    }

    @Test
    @DisplayName("the retry timer never attempts a UDN that an alive is setting up right now or that already runs")
    fun retrySkipsPendingAndRunning() = runBlocking {
        val gate = CompletableDeferred<Boolean>()
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val h = Harness(initBehaviour = {
            when (calls.incrementAndGet()) {
                1 -> throw java.io.IOException("first")
                else -> gate.await()
            }
        })
        h.alive()
        h.now.addAndGet(RendererRegistry.RETRY_FAILED_MS)
        coroutineScope {
            // a new alive after the skip ran out starts a setup that waits on the gate
            val pendingAlive = async(Dispatchers.Default) { h.alive() }
            val end = System.currentTimeMillis() + 3000
            while (calls.get() < 2 && System.currentTimeMillis() < end) delay(5)
            assertEquals(2, calls.get())
            assertEquals(0, h.registry.retryDue())
            assertEquals(2, h.created.size)
            gate.complete(true)
            pendingAlive.await()
        }
        assertEquals(1, h.registry.count.value)
        h.now.addAndGet(RendererRegistry.RETRY_FAILED_MS * 2)
        assertEquals(0, h.registry.retryDue())
        assertEquals(2, h.created.size)
        assertTrue(h.events.retries.isEmpty())
    }

    @Test
    @DisplayName("retryDue asks every running renderer to revive and logs a retry event only when one answered")
    fun retryDueRevivesRunningRenderers() = runBlocking {
        val h = Harness()
        h.alive(udn = "A")
        h.alive(udn = "B", location = "http://192.168.50.43:1400/d.xml", src = "192.168.50.43")
        h.alive(udn = "C", location = "http://192.168.50.44:1400/d.xml", src = "192.168.50.44")
        h.created[0].reviveResult = { true }
        h.created[1].reviveResult = { false }
        // created[2] answers null: nothing due
        assertEquals(0, h.registry.retryDue())
        assertEquals(listOf(1, 1, 1), h.created.map { it.reviveCalls.get() })
        assertEquals(2, h.events.retries.size)
        val ok = h.events.retries.single { it["outcome"] == "running" }
        assertEquals("terminated", ok["reason"])
        assertEquals(h.created[0].udn, ok["udn"])
        assertEquals(3000, ok["port"])
        val bad = h.events.retries.single { it["outcome"] == "failed" }
        assertEquals("terminated", bad["reason"])
        assertEquals(h.created[1].udn, bad["udn"])
    }

    @Test
    @DisplayName("a revive that throws is logged as a failed retry and does not stop the others")
    fun reviveThrowIsLogged() = runBlocking {
        val h = Harness()
        h.alive(udn = "A")
        h.alive(udn = "B", location = "http://192.168.50.43:1400/d.xml", src = "192.168.50.43")
        h.created[0].reviveResult = { throw java.io.IOException("socket closed") }
        h.created[1].reviveResult = { true }
        h.registry.retryDue()
        assertEquals(2, h.events.retries.size)
        assertEquals("failed", h.events.retries.single { it["udn"] == h.created[0].udn }["outcome"])
        assertEquals("socket closed", h.events.retries.single { it["udn"] == h.created[0].udn }["error"])
        assertEquals("running", h.events.retries.single { it["udn"] == h.created[1].udn }["outcome"])
    }

    @Test
    @DisplayName("launchSchedules checks for due retries every 10 s")
    fun schedulesRunRetryDueEveryTenSeconds() = runTest {
        val h = Harness(initBehaviour = { throw java.io.IOException("down") })
        h.alive()
        val job = h.registry.launchSchedules(this) { }
        runCurrent()
        h.now.addAndGet(RendererRegistry.RETRY_FAILED_MS)
        advanceTimeBy(RendererRegistry.RETRY_CHECK_MS - 1)
        assertEquals(1, h.created.size)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(2, h.created.size)
        assertEquals(1, h.events.retries.size)
        // the next attempt is 5 minutes after the failed one on the fake clock
        advanceTimeBy(60_000)
        assertEquals(2, h.created.size)
        h.now.addAndGet(RendererRegistry.RETRY_FAILED_MS)
        advanceTimeBy(RendererRegistry.RETRY_CHECK_MS)
        runCurrent()
        assertEquals(3, h.created.size)
        job.cancel()
    }

    @Test
    @DisplayName("at most 256 failed speakers are remembered and the earliest retry time goes first")
    fun skippedMemoryIsCapped() = runBlocking {
        val h = Harness(initBehaviour = { throw java.io.IOException("down") })
        repeat(RendererRegistry.MAX_SKIPPED + 1) { i ->
            h.now.addAndGet(1)
            h.alive(udn = "U$i")
        }
        h.initBehaviour = { true }
        h.now.addAndGet(RendererRegistry.RETRY_FAILED_MS + 1000)
        val attempted = h.registry.retryDue()
        assertEquals(RendererRegistry.MAX_SKIPPED, attempted)
        assertEquals(RendererRegistry.MAX_SKIPPED, h.registry.count.value)
        assertFalse(h.registry.renderers().any { it.udn == "u0" })
        assertTrue(h.registry.renderers().any { it.udn == "u${RendererRegistry.MAX_SKIPPED}" })
    }

    // ---- Plan 05-04 Task 3: the first prune after a long sleep waits for a fresh search ----

    @Test
    @DisplayName("after a sleep longer than twice the discovery interval the first prune is deferred behind a search")
    fun pruneIsDeferredAfterLongSleep() = runTest {
        val h = Harness()
        h.alive()
        val searchTimes = CopyOnWriteArrayList<Long>()
        val job = h.registry.launchSchedules(this) { searchTimes.add(testScheduler.currentTime / 1000) }
        runCurrent()
        advanceTimeBy(6_000)
        assertEquals(listOf(0L, 5L), searchTimes.toList())

        // the phone slept for two hours: the wall clock moved, the timers did not
        h.now.addAndGet(2L * 60 * 60 * 1000)
        advanceTimeBy(54_000)
        runCurrent()
        assertEquals(1, h.registry.count.value)
        assertEquals(1, h.events.named("prune-deferred").size)
        assertEquals(0, h.events.named("stale").size)
        assertEquals(listOf(0L, 5L, 60L), searchTimes.toList())

        // nothing refreshed the renderer in that minute, so the next round prunes it as before
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(0, h.registry.count.value)
        assertEquals(1, h.events.named("stale").size)
        assertEquals(1, h.events.named("prune-deferred").size)
        job.cancel()
    }

    @Test
    @DisplayName("a search reported through searched() counts as fresh, so the prune right after it is not deferred")
    fun searchedMakesPruneNormal() = runTest {
        val h = Harness()
        h.alive()
        val job = h.registry.launchSchedules(this) { }
        runCurrent()
        advanceTimeBy(6_000)
        h.now.addAndGet(2L * 60 * 60 * 1000)
        // what Bridge.housekeeping does after its own search
        h.registry.searched()
        advanceTimeBy(54_000)
        runCurrent()
        assertEquals(0, h.events.named("prune-deferred").size)
        assertEquals(1, h.events.named("stale").size)
        assertEquals(0, h.registry.count.value)
        job.cancel()
    }

    // ---- Plan 05-14: a cancelled retry pass (WR-02) ----

    /**
     * Ten speakers fail their first setup, then a retry pass runs while every init blocks, and is cancelled once
     * the registry's parallel limit of inits is blocked (the others wait for a permit). Afterwards inits succeed.
     */
    private inner class CancelledPass {
        val phase = java.util.concurrent.atomic.AtomicInteger(0)
        val entered = java.util.concurrent.atomic.AtomicInteger(0)
        val release = CompletableDeferred<Boolean>()
        val h = Harness(initBehaviour = {
            when (phase.get()) {
                0 -> throw java.io.IOException("boom")
                1 -> {
                    entered.incrementAndGet()
                    release.await()
                }
                else -> true
            }
        })

        suspend fun run() {
            repeat(10) { i ->
                h.alive(udn = "S$i", location = "http://192.168.50.${50 + i}:1400/xml/device_description.xml", src = "192.168.50.${50 + i}")
            }
            assertEquals(10, h.created.size)
            assertEquals(0, h.registry.count.value)
            h.now.addAndGet(RendererRegistry.RETRY_FAILED_MS)
            phase.set(1)
            coroutineScope {
                val pass = launch(Dispatchers.Default) { h.registry.retryDue() }
                val end = System.currentTimeMillis() + 3000
                while (entered.get() < RendererRegistry.RETRY_PARALLEL && System.currentTimeMillis() < end) delay(5)
                delay(50)
                assertEquals(RendererRegistry.RETRY_PARALLEL, entered.get())
                pass.cancelAndJoin()
            }
            phase.set(2)
        }
    }

    @Test
    @DisplayName("a retry pass cancelled while some setups wait for a permit leaves no speaker pending, so the next pass retries all of them")
    fun aCancelledRetryPassLeavesNoSpeakerPending() = runBlocking {
        val c = CancelledPass()
        c.run()
        assertEquals(10, c.h.registry.retryDue())
        assertEquals(10, c.h.registry.count.value)
    }

    @Test
    @DisplayName("a cancelled retry pass does not count as a failed attempt: the next pass reports attempt 1 for every speaker")
    fun aCancelledRetryPassDoesNotCountAsAFailedAttempt() = runBlocking {
        val c = CancelledPass()
        c.run()
        c.h.registry.retryDue()
        val retries = c.h.events.retries.filter { it["reason"] == "failed" }
        assertEquals(10, retries.size)
        assertTrue(retries.all { it["attempt"] == 1 && it["outcome"] == "added" })
    }

    @Test
    @DisplayName("a setup cancelled before its renderer was registered destroys it and frees its port")
    fun aCancelledSetupDestroysItsHalfBuiltRendererAndFreesItsPort() = runBlocking {
        val c = CancelledPass()
        c.run()
        val cancelled = c.h.created.subList(10, 10 + RendererRegistry.RETRY_PARALLEL)
        assertEquals(RendererRegistry.RETRY_PARALLEL, cancelled.size)
        assertTrue(cancelled.all { it.destroyCalls.get() == 1 })
        c.h.registry.retryDue()
        assertEquals((3000..3009).toList(), c.h.registry.renderers().map { it.port }.sorted())
    }
}
