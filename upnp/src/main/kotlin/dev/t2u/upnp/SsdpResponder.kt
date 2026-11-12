package dev.t2u.upnp

import java.io.IOException
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketAddress
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * One multicast listen socket. [bindAddress] null means the wildcard. When [joinInterface] is set the
 * group is joined on that interface, when [joinWithoutInterface] is true it is joined without one
 * (the system default route decides); with neither, no join happens (loopback tests).
 */
data class ListenSpec(
    val id: String,
    val bindAddress: InetAddress?,
    val port: Int,
    val joinInterface: NetworkInterface?,
    val joinWithoutInterface: Boolean,
)

/** Why a self-probe passed or failed. [wire] is the short code the log carries: no address, name or message. */
enum class ProbeResult(val wire: String) {
    /** The responder's own listener heard the probe. */
    OK("ok"),

    /** The responder was never started or has been stopped. */
    NOT_RUNNING("not-running"),

    /** The responder runs but has no reply socket, so it can neither probe, answer nor announce. */
    NO_REPLY_SOCKET("no-reply-socket"),

    /** The reply socket threw when sending the probe: the send side is dead. */
    SEND_FAILED("send-failed"),

    /** The probe was sent but the own listener did not hear it in time: the receive side is deaf. */
    NOT_HEARD("not-heard"),
}

/** The production reply socket: bound to [bind] on an ephemeral port, TTL 4, the interface set when there is one. */
internal fun defaultReplySocket(
    bind: InetAddress,
    iface: NetworkInterface?,
    create: (SocketAddress) -> MulticastSocket = { MulticastSocket(it) },
): MulticastSocket {
    val sock = create(InetSocketAddress(bind, 0))
    sock.setupOrClose {
        timeToLive = 4
        if (iface != null) networkInterface = iface
    }
    return sock
}

/** A DIAL device advertised through the responder. [location] is the full device-description URL. */
data class SsdpDevice(val name: String, val uuid: String, val serverHeader: String, val location: String)

interface SsdpEvents {
    fun onBind(specId: String, ok: Boolean, boundPort: Int, error: String?) {}

    fun onReceive(specId: String, src: InetSocketAddress, msg: SsdpMessage, raw: String) {}

    fun onReply(dest: InetSocketAddress, st: String, usn: String, deviceName: String) {}

    fun onNotify(nts: String, nt: String, deviceName: String) {}

    fun onDrop(src: InetSocketAddress, reason: String) {}

    fun onError(where: String, t: Throwable) {}
}

/**
 * SSDP responder for DIAL devices: listens on one or more multicast sockets, answers M-SEARCH with a
 * unicast reply from an ephemeral-port socket (as peer-ssdp does), and keeps a registry of devices.
 * Pure JVM: interfaces and addresses are chosen by the caller.
 */
class SsdpResponder(
    private val listen: List<ListenSpec>,
    private val replyBind: InetAddress,
    private val replyInterface: NetworkInterface?,
    private val events: SsdpEvents,
    private val groupPort: Int = SsdpConstants.PORT,
    private val clockNanos: () -> Long = System::nanoTime,
    /** Whether a datagram from this source may be answered; refused sources are dropped with reason "access". */
    private val replyPolicy: (InetAddress) -> Boolean = { true },
    /** Whether [announce] may send ssdp:alive (false in phone-only mode); byebye is always sent. */
    private val announcePolicy: () -> Boolean = { true },
    /** Where NOTIFY goes; null means the SSDP group on [groupPort]. Tests point it at a unicast socket. */
    private val notifyTarget: InetSocketAddress? = null,
    /** Where [selfProbe] sends its datagram; null means the first listener on loopback, else the SSDP group on [groupPort]. */
    private val probeTarget: InetSocketAddress? = null,
    /**
     * Creates the reply socket (bound to the address, ephemeral port, interface set when given).
     * Tests inject failing or breakable sockets.
     */
    private val openReplySocket: (InetAddress, NetworkInterface?) -> MulticastSocket = ::defaultReplySocket,
) {
    private class Listener(val spec: ListenSpec, val socket: MulticastSocket) {
        lateinit var thread: Thread
    }

    private val devices = CopyOnWriteArrayList<SsdpDevice>()
    private val listeners = CopyOnWriteArrayList<Listener>()
    private val bound = ConcurrentHashMap<String, Int>()

    /** Reply timestamps per source IP for the reflection cap. */
    private val replyTimes = ConcurrentHashMap<String, ArrayDeque<Long>>()

    @Volatile private var replySocket: MulticastSocket? = null

    @Volatile private var running = false

    /** Serialises start, stop and [restartListeners], so a reopen never races a stop. */
    private val lifecycle = Any()

    /** Probes in flight: nonce -> latch counted down when our own listener hears that probe. */
    private val pendingProbes = ConcurrentHashMap<String, CountDownLatch>()

    private val random = SecureRandom()

    fun register(device: SsdpDevice) {
        devices.removeIf { it.uuid == device.uuid }
        devices.add(device)
    }

    fun unregister(uuid: String) {
        devices.removeIf { it.uuid == uuid }
    }

    fun boundPorts(): Map<String, Int> = bound.toMap()

    fun start() = synchronized(lifecycle) {
        check(!running) { "already started" }
        running = true

        replySocket = createReplySocket()

        openListeners()
    }

    /**
     * Asks the [openReplySocket] factory for a reply socket; a failure is reported through onError and
     * leaves the responder without one.
     */
    private fun createReplySocket(): MulticastSocket? = try {
        openReplySocket(replyBind, replyInterface)
    } catch (t: Throwable) {
        events.onError("reply-socket", t)
        null
    }

    /** Opens one multicast listen socket per [ListenSpec] and starts its receive thread. A failed bind is reported through onBind. */
    private fun openListeners() {
        val group = InetAddress.getByName(SsdpConstants.GROUP)
        for (spec in listen) {
            try {
                val sock = MulticastSocket(null as SocketAddress?)
                sock.setupOrClose {
                    reuseAddress = true
                    // EADDRINUSE here is a gate-relevant finding: surfaced through onBind, never swallowed.
                    bind(InetSocketAddress(spec.bindAddress, spec.port))
                    soTimeout = 1000
                    // The multicast loopback flag is left at its default (setLoopbackMode(true) disables it).
                    if (spec.joinInterface != null) {
                        joinGroup(InetSocketAddress(group, groupPort), spec.joinInterface)
                    } else if (spec.joinWithoutInterface) {
                        // joinGroup(InetAddress) is deprecated since JDK 14; its replacement, joinGroup(SocketAddress, NetworkInterface),
                        // is used above whenever an interface is known. This is the one case with no interface to name.
                        @Suppress("DEPRECATION")
                        joinGroup(group)
                    }
                }
                val listener = Listener(spec, sock)
                listener.thread = Thread({ receiveLoop(listener) }, "t2u-ssdp-${spec.id}").apply { isDaemon = true }
                listeners.add(listener)
                bound[spec.id] = sock.localPort
                events.onBind(spec.id, true, sock.localPort, null)
                listener.thread.start()
            } catch (t: Throwable) {
                bound.remove(spec.id)
                events.onBind(spec.id, false, -1, "${t.javaClass.simpleName}: ${t.message}")
            }
        }
    }

    /**
     * Sends ssdp:alive for every registered device and every service type to the SSDP group, unless
     * [announcePolicy] says no (phone-only mode: a multicast alive would show the receivers to the whole LAN).
     */
    fun announce() {
        if (!announcePolicy()) return
        sendNotifies("ssdp:alive", devices)
    }

    /**
     * Sends ssdp:byebye for one device's five DIAL notification types, then removes it. Sent whatever
     * [announcePolicy] says: a byebye only removes cache entries.
     */
    fun withdraw(uuid: String) {
        val device = devices.firstOrNull { it.uuid == uuid } ?: return
        sendNotifies("ssdp:byebye", listOf(device))
        devices.removeIf { it.uuid == uuid }
    }

    private fun sendNotifies(nts: String, targets: List<SsdpDevice>) {
        val sock = replySocket ?: return
        val group = notifyTarget ?: InetSocketAddress(InetAddress.getByName(SsdpConstants.GROUP), groupPort)
        // JVM tests bind the reply socket to loopback and must never put multicast on a real network
        if (replyBind.isLoopbackAddress && group.address.isMulticastAddress) return
        for (device in targets) {
            for (nt in SsdpConstants.dialServiceTypes(device.uuid)) {
                val bytes = buildNotify(nts, nt, "uuid:${device.uuid}::$nt", device.serverHeader, device.location, httpDate())
                try {
                    sock.send(DatagramPacket(bytes, bytes.size, group))
                    events.onNotify(nts, nt, device.name)
                } catch (t: Throwable) {
                    events.onError("notify", t)
                }
            }
        }
    }

    fun stop() = synchronized(lifecycle) {
        if (!running) return@synchronized
        sendNotifies("ssdp:byebye", devices)
        running = false
        closeListeners()
        runCatching { replySocket?.close() }
        replySocket = null
    }

    private fun closeListeners() {
        for (l in listeners) runCatching { l.socket.close() }
        for (l in listeners) runCatching { l.thread.join(3000) }
        listeners.clear()
        bound.clear()
    }

    /**
     * Replaces the reply socket and reopens every listen socket, on the same specs. The reply socket is recreated too: one the OS
     * invalidated, or one that failed at [start], would otherwise
     * leave the responder unable to answer, announce or probe for good. The registered devices and the running flag are
     * untouched: this is a socket-level repair (a multicast membership the OS dropped, a dead socket), never a rebind.
     * Returns how many listeners are bound now; 0 when the responder is not running.
     */
    fun restartListeners(): Int = synchronized(lifecycle) {
        if (!running) return@synchronized 0
        runCatching { replySocket?.close() }
        replySocket = createReplySocket()
        closeListeners()
        openListeners()
        listeners.size
    }

    /** Closes the listen sockets only, leaving the responder "running" but deaf, so tests can exercise [restartListeners]. */
    internal fun closeListenersForTest() = synchronized(lifecycle) { closeListeners() }

    /**
     * Proves this responder's own listener still receives: sends one M-SEARCH whose ST is a random nonce
     * ([PROBE_ST_PREFIX] + 16 hex characters) and waits up to [timeoutMs] for the listener to see it. The probe carries
     * no device name, UUID or location, is never answered and never reaches the SSDP client (handled first in [handle]).
     * The result names why it failed: not running, no reply socket, the send threw, or nothing was heard in time.
     */
    fun probe(timeoutMs: Long = 2000): ProbeResult {
        if (!running) return ProbeResult.NOT_RUNNING
        val sock = replySocket ?: return ProbeResult.NO_REPLY_SOCKET
        val target: InetSocketAddress = if (probeTarget != null) {
            probeTarget
        } else if (replyBind.isLoopbackAddress) {
            // JVM tests: never multicast; aim at our own first listener
            val l = listeners.firstOrNull() ?: return ProbeResult.NOT_HEARD
            InetSocketAddress(l.spec.bindAddress ?: replyBind, l.socket.localPort)
        } else {
            InetSocketAddress(InetAddress.getByName(SsdpConstants.GROUP), groupPort)
        }
        val nonce = ByteArray(8).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val latch = CountDownLatch(1)
        pendingProbes[nonce] = latch
        try {
            val msg = "M-SEARCH * HTTP/1.1\r\nHOST: ${SsdpConstants.GROUP}:${SsdpConstants.PORT}\r\nMAN: \"ssdp:discover\"\r\nMX: 1\r\n" +
                "ST: $PROBE_ST_PREFIX$nonce\r\n\r\n"
            val bytes = msg.toByteArray(Charsets.UTF_8)
            try {
                sock.send(DatagramPacket(bytes, bytes.size, target))
            } catch (_: IOException) {
                return ProbeResult.SEND_FAILED
            }
            return if (latch.await(timeoutMs, TimeUnit.MILLISECONDS)) ProbeResult.OK else ProbeResult.NOT_HEARD
        } finally {
            pendingProbes.remove(nonce)
        }
    }

    /**
     * Proves this responder's own listener still receives (see [probe]). False when the responder is not running, has no
     * reply socket, could not send, or heard nothing in time.
     */
    fun selfProbe(timeoutMs: Long = 2000): Boolean = probe(timeoutMs) == ProbeResult.OK

    private fun receiveLoop(l: Listener) {
        val buf = ByteArray(8192)
        while (running && !l.socket.isClosed) {
            val packet = DatagramPacket(buf, buf.size)
            try {
                l.socket.receive(packet)
            } catch (_: SocketTimeoutException) {
                continue
            } catch (t: Throwable) {
                if (running && !l.socket.isClosed) events.onError("receive:${l.spec.id}", t)
                break
            }
            try {
                handle(l.spec.id, packet)
            } catch (t: Throwable) {
                events.onError("handle:${l.spec.id}", t)
            }
        }
    }

    private fun handle(specId: String, packet: DatagramPacket) {
        val src = InetSocketAddress(packet.address, packet.port)
        val raw = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
        val msg = parseMessage(packet.data, packet.length)
        events.onReceive(specId, src, msg, raw)

        if (!msg.startLine.uppercase().startsWith("M-SEARCH")) return
        if (!msg.headers["MAN"].orEmpty().lowercase().contains("ssdp:discover")) return
        val st = msg.headers["ST"] ?: return
        // our own liveness probe: counted, then dropped before any device match, rate limit or drop log
        if (st.startsWith(PROBE_ST_PREFIX)) {
            pendingProbes[st.removePrefix(PROBE_ST_PREFIX)]?.countDown()
            return
        }
        // searches for types none of our devices answers are not ours to refuse or log
        if (devices.none { st in SsdpConstants.dialServiceTypes(it.uuid) }) return
        if (!replyPolicy(src.address)) {
            // a refused source never reaches the rate limiter
            events.onDrop(src, "access")
            return
        }
        for (device in devices) {
            if (st !in SsdpConstants.dialServiceTypes(device.uuid)) continue
            if (!allowReply(src)) {
                events.onDrop(src, "rate")
                continue
            }
            sendReply(src, device, st)
        }
    }

    /** At most [MAX_REPLIES_PER_SECOND] replies in any rolling second to one source IP. */
    private fun allowReply(src: InetSocketAddress): Boolean {
        if (replyTimes.size > 4096) replyTimes.clear()
        val key = src.address.hostAddress
        val times = replyTimes.computeIfAbsent(key) { ArrayDeque() }
        val now = clockNanos()
        synchronized(times) {
            while (times.isNotEmpty() && now - times.peekFirst() >= WINDOW_NANOS) times.pollFirst()
            if (times.size >= MAX_REPLIES_PER_SECOND) return false
            times.addLast(now)
            return true
        }
    }

    private fun sendReply(dest: InetSocketAddress, device: SsdpDevice, st: String) {
        val sock = replySocket ?: return
        val usn = "uuid:${device.uuid}::$st"
        val bytes = buildSearchReply(device.location, st, device.serverHeader, usn, httpDate())
        try {
            sock.send(DatagramPacket(bytes, bytes.size, dest))
            events.onReply(dest, st, usn, device.name)
        } catch (t: Throwable) {
            events.onError("reply", t)
        }
    }

    private companion object {
        const val PROBE_ST_PREFIX = "urn:dev-t2u:probe:"
        const val MAX_REPLIES_PER_SECOND = 32
        const val WINDOW_NANOS = 1_000_000_000L
    }
}
