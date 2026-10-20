package dev.t2u.upnp

import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList

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
    /** Whether a datagram from this source may be answered (SVC-09); refused sources are dropped with reason "access". */
    private val replyPolicy: (InetAddress) -> Boolean = { true },
    /** Whether [announce] may send ssdp:alive (false in phone-only mode); byebye is always sent. */
    private val announcePolicy: () -> Boolean = { true },
    /** Where NOTIFY goes; null means the SSDP group on [groupPort]. Tests point it at a unicast socket. */
    private val notifyTarget: InetSocketAddress? = null,
) {
    private class Listener(val spec: ListenSpec, val socket: MulticastSocket) {
        lateinit var thread: Thread
    }

    private val devices = CopyOnWriteArrayList<SsdpDevice>()
    private val listeners = CopyOnWriteArrayList<Listener>()
    private val bound = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** Reply timestamps per source IP for the reflection cap (T-01-05). */
    private val replyTimes = java.util.concurrent.ConcurrentHashMap<String, java.util.ArrayDeque<Long>>()

    @Volatile private var replySocket: MulticastSocket? = null

    @Volatile private var running = false

    fun register(device: SsdpDevice) {
        devices.removeIf { it.uuid == device.uuid }
        devices.add(device)
    }

    fun unregister(uuid: String) {
        devices.removeIf { it.uuid == uuid }
    }

    fun boundPorts(): Map<String, Int> = bound.toMap()

    fun start() {
        check(!running) { "already started" }
        running = true
        val group = InetAddress.getByName(SsdpConstants.GROUP)

        replySocket = try {
            MulticastSocket(InetSocketAddress(replyBind, 0)).apply {
                timeToLive = 4
                if (replyInterface != null) networkInterface = replyInterface
            }
        } catch (t: Throwable) {
            events.onError("reply-socket", t)
            null
        }

        for (spec in listen) {
            try {
                val sock = MulticastSocket(null as SocketAddress?)
                sock.reuseAddress = true
                // EADDRINUSE here is a gate-relevant finding: surfaced through onBind, never swallowed.
                sock.bind(InetSocketAddress(spec.bindAddress, spec.port))
                sock.soTimeout = 1000
                // The multicast loopback flag is left at its default (setLoopbackMode(true) disables it).
                if (spec.joinInterface != null) {
                    sock.joinGroup(InetSocketAddress(group, groupPort), spec.joinInterface)
                } else if (spec.joinWithoutInterface) {
                    @Suppress("DEPRECATION")
                    sock.joinGroup(group)
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
     * [announcePolicy] says: a byebye only removes cache entries (RESEARCH Pitfall 12).
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

    fun stop() {
        if (!running) return
        sendNotifies("ssdp:byebye", devices)
        running = false
        for (l in listeners) runCatching { l.socket.close() }
        for (l in listeners) runCatching { l.thread.join(3000) }
        listeners.clear()
        bound.clear()
        runCatching { replySocket?.close() }
        replySocket = null
    }

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
        val times = replyTimes.computeIfAbsent(key) { java.util.ArrayDeque() }
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
        const val MAX_REPLIES_PER_SECOND = 32
        const val WINDOW_NANOS = 1_000_000_000L
    }
}
