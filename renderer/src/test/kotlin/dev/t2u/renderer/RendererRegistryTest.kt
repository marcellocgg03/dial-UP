package dev.t2u.renderer

// DISC-01, CAST-01: the discovery lifecycle of index.js (onAlive, onByebye, prune, schedules) over the
// ManagedRenderer contract, tested with fakes. GPL-3.0-only.

import dev.t2u.upnp.EventSink
import dev.t2u.upnp.RENDERER_ST
import dev.t2u.upnp.Ssdp
import dev.t2u.upnp.SsdpClientEvents
import kotlinx.coroutines.runBlocking
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
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What the registry needs of a speaker, recording the calls it receives. */
private class FakeManagedRenderer(
    override val udn: String,
    override val location: String,
    override val address: String,
    maxAge: Int,
    override val port: Int,
    private val behaviour: () -> Boolean,
) : ManagedRenderer {
    @Volatile var maxAgeS = maxAge
    @Volatile override var lastSeen: Long = 0
    override val name: String? = "Fake $udn"
    val initCalls = java.util.concurrent.atomic.AtomicInteger()
    val destroyCalls = java.util.concurrent.atomic.AtomicInteger()
    val refreshCalls = java.util.concurrent.atomic.AtomicInteger()

    override fun refresh(maxAge: Int) {
        refreshCalls.incrementAndGet()
        maxAgeS = maxAge
    }

    override fun isStale(now: Long): Boolean = now - lastSeen > maxAgeS * 1000L * 1.5

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

        override fun event(kind: String, fields: Map<String, Any?>) {
            if (kind == "discovery") list.add(fields)
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
                FakeManagedRenderer(udn, location, address, maxAge, port) { true }.also { created.add(it) }
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
}
