package dev.t2u.upnp

// Port of tube2upnp src/ssdp.js, GPL-3.0-only.
//
// The SSDP client: M-SEARCH from one search socket per LAN address, plus NOTIFY dispatch. ssdp.js owns
// a second socket on port 1900 for NOTIFY; here the responder's existing 1900 listener already sees
// every NOTIFY, so the app feeds those into [Ssdp.onNotify] (RESEARCH Pattern 7: no second 1900 socket).
// parseMessage lives in SsdpMessage.kt, shared with the responder.

import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList

/** The device type the app searches for (index.js RENDERER_ST). */
const val RENDERER_ST = "urn:schemas-upnp-org:device:MediaRenderer:1"

private val UDN_RE = Regex("uuid:([^:]+)", RegexOption.IGNORE_CASE)
private val MAX_AGE_RE = Regex("max-age\\s*=\\s*(\\d+)", RegexOption.IGNORE_CASE)

/** "uuid:abcd::urn:..." gives "abcd" (lower case), null when there is no uuid. */
fun udnFromUsn(usn: String?): String? = UDN_RE.find(usn.orEmpty())?.groupValues?.get(1)?.lowercase()

/** "max-age=1800, no-cache" gives 1800; [def] when absent or not a number. */
fun maxAge(cacheControl: String?, def: Int): Int =
    MAX_AGE_RE.find(cacheControl.orEmpty())?.groupValues?.get(1)?.toIntOrNull() ?: def

/** The ssdp.js M-SEARCH text, CRLF line ends and a blank line at the end. */
fun buildSearch(st: String, mx: Int = 3): ByteArray =
    (
        "M-SEARCH * HTTP/1.1\r\n" +
            "HOST: ${SsdpConstants.GROUP}:${SsdpConstants.PORT}\r\n" +
            "MAN: \"ssdp:discover\"\r\n" +
            "MX: $mx\r\n" +
            "ST: $st\r\n" +
            "\r\n"
        ).toByteArray(Charsets.UTF_8)

/**
 * What the client reports. Headers are keyed as [parseMessage] keys them (UPPERCASE, letters only).
 * Handler exceptions are caught by the client and passed to [onError]; they never end a receive loop.
 */
interface SsdpClientEvents {
    fun onAlive(headers: Map<String, String>, src: InetSocketAddress) {}

    fun onByebye(headers: Map<String, String>, src: InetSocketAddress) {}

    fun onError(where: String, t: Throwable) {}
}

class Ssdp(
    private val searchBinds: List<InetAddress>,
    private val events: SsdpClientEvents,
    private val searchTarget: InetSocketAddress = InetSocketAddress(SsdpConstants.GROUP, SsdpConstants.PORT),
) {
    private class SearchSocket(val bind: InetAddress, val socket: MulticastSocket) {
        lateinit var thread: Thread
    }

    private val sockets = CopyOnWriteArrayList<SearchSocket>()

    @Volatile private var running = false

    /** Binds one search socket per address in [searchBinds] (ephemeral source port, TTL 4) and starts reading replies. */
    fun start() {
        check(!running) { "already started" }
        running = true
        for (bind in searchBinds) {
            try {
                val sock = MulticastSocket(InetSocketAddress(bind, 0))
                sock.timeToLive = 4
                sock.soTimeout = 1000
                if (!bind.isLoopbackAddress) {
                    // send the multicast out of the interface this address belongs to
                    runCatching { NetworkInterface.getByInetAddress(bind) }.getOrNull()?.let { sock.networkInterface = it }
                }
                val entry = SearchSocket(bind, sock)
                entry.thread = Thread({ receiveLoop(entry) }, "t2u-ssdp-search-${bind.hostAddress}").apply { isDaemon = true }
                sockets.add(entry)
                entry.thread.start()
            } catch (t: Throwable) {
                events.onError("bind:${bind.hostAddress}", t)
            }
        }
    }

    /**
     * True while the client is started and at least one search socket is open. [start] reports a failed bind through
     * `onError` and does not throw, so a client whose every bind failed is running but deaf: this tells them apart.
     */
    fun isBound(): Boolean = running && sockets.any { !it.socket.isClosed }

    /** Sends an M-SEARCH from every search socket. A loopback socket never sends to a multicast target (JVM tests). */
    fun search(st: String, mx: Int = 3) {
        val bytes = buildSearch(st, mx)
        for (s in sockets) {
            if (s.bind.isLoopbackAddress && searchTarget.address.isMulticastAddress) continue
            try {
                s.socket.send(DatagramPacket(bytes, bytes.size, searchTarget))
            } catch (t: Throwable) {
                events.onError("search:${s.bind.hostAddress}", t)
            }
        }
    }

    /**
     * A datagram the app received on the responder's port-1900 listener: NOTIFY with NTS ssdp:alive
     * becomes onAlive, ssdp:byebye becomes onByebye, anything else is ignored.
     */
    fun onNotify(src: InetSocketAddress, msg: SsdpMessage) {
        if (!msg.startLine.uppercase().startsWith("NOTIFY")) return
        val nts = msg.headers["NTS"].orEmpty().lowercase()
        try {
            if (nts.contains("ssdp:alive")) {
                events.onAlive(msg.headers, src)
            } else if (nts.contains("ssdp:byebye")) {
                events.onByebye(msg.headers, src)
            }
        } catch (t: Throwable) {
            events.onError("notify", t)
        }
    }

    fun stop() {
        if (!running) return
        running = false
        for (s in sockets) runCatching { s.socket.close() }
        for (s in sockets) runCatching { s.thread.join(3000) }
        sockets.clear()
    }

    private fun receiveLoop(s: SearchSocket) {
        val buf = ByteArray(8192)
        while (running && !s.socket.isClosed) {
            val packet = DatagramPacket(buf, buf.size)
            try {
                s.socket.receive(packet)
            } catch (_: SocketTimeoutException) {
                continue
            } catch (t: Throwable) {
                if (running && !s.socket.isClosed) events.onError("receive:${s.bind.hostAddress}", t)
                break
            }
            try {
                val src = InetSocketAddress(packet.address, packet.port)
                val msg = parseMessage(packet.data, packet.length)
                // ssdp.js onResponse: only a 200 is a search reply; the status is the second token of the start line
                val status = msg.startLine.split(' ').getOrNull(1)?.toIntOrNull()
                if (status == 200) events.onAlive(msg.headers, src)
            } catch (t: Throwable) {
                events.onError("handle:${s.bind.hostAddress}", t)
            }
        }
    }
}
