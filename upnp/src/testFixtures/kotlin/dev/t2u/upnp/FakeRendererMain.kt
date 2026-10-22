package dev.t2u.upnp

// Plan 03-26: a standalone fake speaker on the LAN, started by tools/fake-renderer.sh through the Gradle task
// :upnp:runFakeRenderer. It is the FakeRenderer of the JVM tests behind a private LAN address and a fixed port, so the
// phone app can discover and cast to it. It never fetches the stream and never plays a sound; it only answers SOAP
// and says (one line per control call, no URL, no DIDL) what it was asked to do.

import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketAddress
import java.net.SocketTimeoutException
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

const val FAKE_DEFAULT_NAME = "Fake Test Speaker"

/** 49200 is the one TCP port the author's Hyper-V firewall rule T2U-fake-http-in lets into WSL. */
const val FAKE_DEFAULT_PORT = 49200
const val FAKE_DEFAULT_START_DELAY_MS = 800L

/** What GetProtocolInfo answers unless --sink says otherwise: mpeg (mp3), mp4, flac and adts (audio/aac). */
const val FAKE_DEFAULT_SINK =
    "http-get:*:audio/mpeg:*,http-get:*:audio/mp4:*,http-get:*:audio/flac:*,http-get:*:audio/aac:*"

val FAKE_RENDERER_USAGE: String = """
Usage: tools/fake-renderer.sh [options]

A fake UPnP speaker on the LAN for the phone app to discover and cast to. It accepts the cast, never fetches the
stream and plays no sound; it prints one line per control call (mime, never a URL) so the app's 12 s start clock and
format walk can be watched.

Options:
  --name TEXT            friendly name the app lists (default: $FAKE_DEFAULT_NAME)
  --port N               TCP port of the description and SOAP server (default: $FAKE_DEFAULT_PORT)
  --bind IP              private IPv4 address to serve and advertise on (default: the host's first private address;
                         only 10/8, 172.16/12, 192.168/16 and 169.254/16 are accepted)
  --sink LIST            what GetProtocolInfo reports, comma separated: full http-get:*:mime:* entries or bare mimes
                         (default: audio/mpeg, audio/mp4, audio/flac, audio/aac)
  --play-mimes LIST      comma separated mimes for which Play reaches PLAYING (audio/flac and audio/x-flac count as
                         the same); for any other mime the cast is accepted and Play is accepted, but the speaker
                         never leaves TRANSITIONING, like a speaker that cannot decode the stream. Without this
                         option every stream plays.
  --start-delay-ms N     how long Play takes to reach PLAYING (default: $FAKE_DEFAULT_START_DELAY_MS)
  -h, --help             this text

Example: bash tools/fake-renderer.sh --play-mimes audio/x-flac

Stop it with Ctrl-C: it sends ssdp:byebye first. A runner that is killed hard can stay in the cast menu for up to
30 minutes (max-age 1800). Needs the Windows Hyper-V firewall rules for UDP 1900 and the TCP port (default 49200).
""".trimIndent()

data class FakeRendererOptions(
    val name: String,
    val port: Int,
    val bind: String,
    /** The Sink string GetProtocolInfo answers. */
    val sink: String,
    /** Null: every stream plays. */
    val playMimes: Set<String>?,
    val startDelayMs: Long,
)

sealed interface FakeRendererParse {
    data class Run(val options: FakeRendererOptions) : FakeRendererParse

    data object Help : FakeRendererParse

    data class Invalid(val message: String) : FakeRendererParse
}

/** 10/8, 172.16/12, 192.168/16 and 169.254/16, written as four decimal numbers; loopback, 0.0.0.0 and names are not. */
fun isPrivateIpv4(ip: String): Boolean {
    val m = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$").matchEntire(ip) ?: return false
    val o = m.groupValues.drop(1).map { it.toInt() }
    if (o.any { it > 255 }) return false
    return o[0] == 10 || (o[0] == 172 && o[1] in 16..31) || (o[0] == 192 && o[1] == 168) || (o[0] == 169 && o[1] == 254)
}

/** A stable UUID per friendly name, so the phone sees the same speaker after a restart. */
fun fakeRendererUdn(name: String): String = UUID.nameUUIDFromBytes("t2u-fake-renderer:$name".toByteArray(Charsets.UTF_8)).toString()

/** "audio/flac,audio/mpeg" becomes "http-get:*:audio/flac:*,http-get:*:audio/mpeg:*"; an entry that already has colons is kept. */
fun expandSink(list: String): String =
    list.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        .joinToString(",") { if (':' in it) it else "http-get:*:${it.lowercase()}:*" }

private val MIME_TYPE = Regex("^[a-z0-9][a-z0-9.+_-]*/[a-z0-9][a-z0-9.+_-]*$")

private val VALUE_OPTIONS = setOf("--name", "--port", "--bind", "--sink", "--play-mimes", "--start-delay-ms")

/**
 * Pure: reads the command line, touches no socket. [defaultBind] is asked only when --bind is absent.
 * Help wins over everything else; any other problem is one [FakeRendererParse.Invalid] with a message that names the option.
 */
fun parseFakeRendererOptions(args: List<String>, defaultBind: () -> String?): FakeRendererParse {
    if (args.any { it == "--help" || it == "-h" }) return FakeRendererParse.Help
    val values = LinkedHashMap<String, String>()
    var i = 0
    while (i < args.size) {
        val arg = args[i]
        val key = arg.substringBefore('=')
        if (key !in VALUE_OPTIONS) return FakeRendererParse.Invalid("unknown option $arg")
        if ('=' in arg) {
            values[key] = arg.substringAfter('=')
            i += 1
        } else {
            if (i + 1 >= args.size) return FakeRendererParse.Invalid("$key needs a value")
            values[key] = args[i + 1]
            i += 2
        }
    }

    val name = values["--name"]?.trim() ?: FAKE_DEFAULT_NAME
    if (name.isEmpty()) return FakeRendererParse.Invalid("--name must not be empty")

    val port = if ("--port" in values) values.getValue("--port").trim().toIntOrNull() else FAKE_DEFAULT_PORT
    if (port == null || port !in 1..65535) return FakeRendererParse.Invalid("--port must be a number from 1 to 65535")

    val delay = if ("--start-delay-ms" in values) values.getValue("--start-delay-ms").trim().toLongOrNull() else FAKE_DEFAULT_START_DELAY_MS
    if (delay == null || delay < 0) return FakeRendererParse.Invalid("--start-delay-ms must be a number of milliseconds, 0 or more")

    val bind = values["--bind"]?.trim() ?: defaultBind()
        ?: return FakeRendererParse.Invalid("no private IPv4 address found on this host; pass --bind IP")
    if (!isPrivateIpv4(bind)) {
        return FakeRendererParse.Invalid(
            "refusing bind address '$bind' (only 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16 and 169.254.0.0/16 are allowed)",
        )
    }

    val sink = values["--sink"]?.let { expandSink(it) } ?: FAKE_DEFAULT_SINK
    if (sink.isEmpty()) return FakeRendererParse.Invalid("--sink must name at least one mime or protocolInfo entry")

    var playMimes: Set<String>? = null
    values["--play-mimes"]?.let { text ->
        val mimes = text.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        val bad = mimes.firstOrNull { !MIME_TYPE.matches(it) }
        if (bad != null) return FakeRendererParse.Invalid("--play-mimes: '$bad' is not a mime type like audio/flac")
        if (mimes.isEmpty()) return FakeRendererParse.Invalid("--play-mimes must name at least one mime type")
        playMimes = mimes.toSet()
    }

    return FakeRendererParse.Run(FakeRendererOptions(name, port, bind, sink, playMimes, delay))
}

/** The host's first private IPv4 on an interface that is up and not loopback; 192.168/16 before 10/8 before 172.16/12. */
fun defaultPrivateAddress(): String? {
    val found = ArrayList<String>()
    for (nif in NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()) {
        if (!nif.isUp || nif.isLoopback || nif.isVirtual) continue
        for (a in nif.inetAddresses) {
            if (a is Inet4Address && a.isSiteLocalAddress) found.add(a.hostAddress)
        }
    }
    fun rank(ip: String) = when {
        ip.startsWith("192.168.") -> 0
        ip.startsWith("10.") -> 1
        else -> 2
    }
    return found.minByOrNull { rank(it) }
}

private const val AVTRANSPORT_ST = "urn:schemas-upnp-org:service:AVTransport:1"

/** The four notification types the fake advertises: root device, its uuid, MediaRenderer:1 and AVTransport:1. */
fun fakeRendererSsdpTypes(udn: String): List<String> = listOf("upnp:rootdevice", "uuid:$udn", RENDERER_ST, AVTRANSPORT_ST)

/** Does an M-SEARCH for [st] concern the fake? ssdp:all and each advertised type do; DIAL, ZonePlayer and other versions do not. */
fun fakeRendererAnswers(st: String, udn: String): Boolean = st == "ssdp:all" || st in fakeRendererSsdpTypes(udn)

/** USN of one notification type: the bare uuid for the uuid type, otherwise uuid:<udn>::<type>. */
fun fakeRendererUsn(udn: String, type: String): String = if (type.startsWith("uuid:")) type else "uuid:$udn::$type"

/**
 * SSDP for the fake speaker: ssdp:alive on start and every [aliveEveryMs] (max-age 1800), ssdp:byebye on [stop], and unicast
 * replies to M-SEARCH. It cannot be a plain [SsdpResponder]: that class answers and announces the DIAL types only, and a DIAL
 * advertisement would put a second, broken "YouTube receiver" in the cast menu. The datagram texts come from the main code
 * ([buildNotify], [buildSearchReply], [parseMessage]) so they are the ones the app is tested against.
 *
 * [bindAddress] is the source of every datagram this sends (the app only accepts a LOCATION whose host is the sender),
 * [listenPort] 1900 in production and 0 in tests, [notifyTarget] null for the SSDP group (tests point it at a unicast socket).
 * A loopback [bindAddress] never sends to a multicast group. Only private sources are answered.
 */
class FakeRendererSsdp(
    private val udn: String,
    private val location: String,
    private val bindAddress: InetAddress,
    private val listenPort: Int = SsdpConstants.PORT,
    private val listenAddress: InetAddress? = null,
    private val joinGroup: Boolean = true,
    private val notifyTarget: InetSocketAddress? = null,
    private val aliveEveryMs: Long = 30_000,
    private val serverHeader: String = "Linux/5.15 UPnP/1.0 t2u-fake-renderer/1",
    private val onEvent: (String) -> Unit = {},
) {
    @Volatile private var listenSocket: MulticastSocket? = null

    @Volatile private var sendSocket: MulticastSocket? = null

    @Volatile private var running = false
    private var listener: Thread? = null
    private var timer: ScheduledExecutorService? = null

    /** The port the listener is bound to (the real one when [listenPort] is 0), -1 before [start] or when the bind failed. */
    fun boundPort(): Int = listenSocket?.localPort ?: -1

    /**
     * Starts the send socket, the listener and the alive timer. A listener that cannot bind (port 1900 busy) is reported
     * through [onEvent] and returned as the message; alive and byebye still work then, only searches are not answered.
     */
    fun start(): String? {
        check(!running) { "already started" }
        running = true
        val nif = runCatching { NetworkInterface.getByInetAddress(bindAddress) }.getOrNull()
        sendSocket = MulticastSocket(InetSocketAddress(bindAddress, 0)).apply {
            timeToLive = 4
            if (!bindAddress.isLoopbackAddress && nif != null) networkInterface = nif
        }
        var problem: String? = null
        try {
            val sock = MulticastSocket(null as SocketAddress?)
            sock.reuseAddress = true
            sock.bind(InetSocketAddress(listenAddress, listenPort))
            sock.soTimeout = 1000
            if (joinGroup && nif != null) {
                sock.joinGroup(InetSocketAddress(InetAddress.getByName(SsdpConstants.GROUP), SsdpConstants.PORT), nif)
            }
            listenSocket = sock
            listener = Thread({ receiveLoop(sock) }, "fake-renderer-ssdp").apply { isDaemon = true }.also { it.start() }
        } catch (t: Throwable) {
            problem = "${t.javaClass.simpleName}: ${t.message}"
            onEvent("SSDP listener on UDP $listenPort failed ($problem): searches will not be answered, alive messages still go out")
        }
        timer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "fake-renderer-alive").apply { isDaemon = true } }
            .also { it.scheduleWithFixedDelay({ runCatching { notify("ssdp:alive") } }, 0, aliveEveryMs, TimeUnit.MILLISECONDS) }
        return problem
    }

    /** Sends ssdp:byebye for the four types, then closes everything. Safe to call twice. */
    fun stop() {
        if (!running) return
        timer?.shutdownNow()
        runCatching { notify("ssdp:byebye") }
        running = false
        runCatching { listenSocket?.close() }
        runCatching { listener?.join(2000) }
        runCatching { sendSocket?.close() }
    }

    private fun target(): InetSocketAddress = notifyTarget ?: InetSocketAddress(InetAddress.getByName(SsdpConstants.GROUP), SsdpConstants.PORT)

    private fun notify(nts: String) {
        val sock = sendSocket ?: return
        val group = target()
        if (bindAddress.isLoopbackAddress && group.address.isMulticastAddress) return
        for (type in fakeRendererSsdpTypes(udn)) {
            val bytes = buildNotify(nts, type, fakeRendererUsn(udn, type), serverHeader, location, httpDate())
            sock.send(DatagramPacket(bytes, bytes.size, group))
        }
        onEvent("SSDP $nts sent for ${fakeRendererSsdpTypes(udn).size} types")
    }

    private fun receiveLoop(sock: MulticastSocket) {
        val buf = ByteArray(8192)
        while (running && !sock.isClosed) {
            val packet = DatagramPacket(buf, buf.size)
            try {
                sock.receive(packet)
            } catch (_: SocketTimeoutException) {
                continue
            } catch (_: Throwable) {
                break
            }
            runCatching { handle(packet) }
        }
    }

    private fun handle(packet: DatagramPacket) {
        val msg = parseMessage(packet.data, packet.length)
        if (!msg.startLine.uppercase().startsWith("M-SEARCH")) return
        if (!msg.headers["MAN"].orEmpty().lowercase().contains("ssdp:discover")) return
        val st = msg.headers["ST"] ?: return
        if (!fakeRendererAnswers(st, udn)) return
        if (!isPrivateLanAddress(packet.address)) return
        val sock = sendSocket ?: return
        val types = if (st == "ssdp:all") fakeRendererSsdpTypes(udn) else listOf(st)
        for (type in types) {
            val bytes = buildSearchReply(location, type, serverHeader, fakeRendererUsn(udn, type), httpDate())
            sock.send(DatagramPacket(bytes, bytes.size, packet.address, packet.port))
        }
        onEvent("SSDP M-SEARCH from ${packet.address.hostAddress} for $st answered")
    }
}

internal fun stamped(line: String) {
    println("${LocalTime.now().truncatedTo(ChronoUnit.MILLIS)} $line")
}

fun main(args: Array<String>) {
    val options = when (val parsed = parseFakeRendererOptions(args.toList(), ::defaultPrivateAddress)) {
        FakeRendererParse.Help -> {
            println(FAKE_RENDERER_USAGE)
            exitProcess(0)
        }
        is FakeRendererParse.Invalid -> {
            System.err.println("fake-renderer: ${parsed.message}")
            System.err.println("Try: bash tools/fake-renderer.sh --help")
            exitProcess(2)
        }
        is FakeRendererParse.Run -> parsed.options
    }
    val udn = fakeRendererUdn(options.name)
    val fake = FakeRenderer(
        sink = options.sink,
        bindHost = options.bind,
        bindPort = options.port,
        friendlyName = options.name,
        udn = udn,
        playMimes = options.playMimes,
        startDelayMs = options.startDelayMs,
        log = ::stamped,
    )
    try {
        fake.start()
    } catch (t: Throwable) {
        System.err.println("fake-renderer: cannot serve on ${options.bind}:${options.port}: ${t.javaClass.simpleName}: ${t.message}")
        exitProcess(1)
    }
    val ssdp = FakeRendererSsdp(
        udn = udn,
        location = fake.location,
        bindAddress = InetAddress.getByName(options.bind),
        onEvent = ::stamped,
    )
    stamped("fake speaker '${options.name}' (uuid:$udn) serving ${fake.location}")
    stamped(
        "plays: ${options.playMimes?.sorted()?.joinToString(", ") ?: "every stream"}; start delay ${options.startDelayMs} ms; " +
            "sink: ${options.sink}",
    )
    val done = CountDownLatch(1)
    Runtime.getRuntime().addShutdownHook(
        Thread {
            stamped("stopping: sending ssdp:byebye")
            runCatching { ssdp.stop() }
            runCatching { fake.close() }
            stamped("stopped")
            done.countDown()
        },
    )
    ssdp.start()
    done.await()
}
