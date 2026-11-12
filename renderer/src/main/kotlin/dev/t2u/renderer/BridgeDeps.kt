package dev.t2u.renderer

// What a Bridge is given: where it listens and what it needs from the app around it, GPL-3.0-only.

import dev.t2u.dial.DialAccessMode
import dev.t2u.dial.DialAccessPolicy
import dev.t2u.dial.DialRequest
import dev.t2u.lounge.InnerTubeEndpoints
import dev.t2u.lounge.LoungeEndpoints
import dev.t2u.media.MediaRequest
import dev.t2u.media.PipeLauncher
import dev.t2u.media.SourceDownloader
import dev.t2u.media.VideoResolver
import dev.t2u.upnp.EventSink
import dev.t2u.upnp.ListenSpec
import dev.t2u.upnp.SsdpConstants
import dev.t2u.upnp.SsdpEvents
import dev.t2u.upnp.SsdpResponder
import kotlinx.coroutines.CoroutineScope
import okhttp3.OkHttpClient
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface

/**
 * Where the bridge listens and searches. [wifiIp] is the only address anything binds to; [iface] is its
 * interface (null in JVM tests); [ssdpListen] are the responder's multicast listen sockets (port 1900 on the phone).
 * [ssdpSearchTarget] and [notifyTarget] default to the SSDP group; tests point them at unicast sockets.
 */
data class BridgeNetwork(
    val wifiIp: InetAddress,
    val iface: NetworkInterface?,
    val ssdpListen: List<ListenSpec>,
    val ssdpSearchTarget: InetSocketAddress = InetSocketAddress(SsdpConstants.GROUP, SsdpConstants.PORT),
    val notifyTarget: InetSocketAddress? = null,
)

/** How many speakers have a player that is loading, playing or paused, out of all speakers with a receiver. */
data class BridgeCount(val casting: Int, val total: Int)

/**
 * Everything the bridge needs from the app around it. [accessMode] is read per request. [http] must come from
 * `lanHttpClient()`; every client the bridge hands on is derived from it. [scope] is the service scope.
 * [launcher] runs ffmpeg for the media server (null: streams answer 501, as in JVM tests).
 *
 * The last three are injectable so tests can stand in for YouTube and googlevideo, and default to production: [innerTubeEndpoints]
 * is www.youtube.com, [upstreamPolicy] proxies googlevideo hosts over https only, and [sourceDownloader] (wired on
 * Android) is what the file format mp4 downloads their source with; without it, or without [launcher]
 * or [bufferDir], file builds are unavailable.
 */
data class BridgeDeps(
    val config: Config,
    val resolver: VideoResolver,
    val launcher: PipeLauncher?,
    val http: OkHttpClient,
    val accessMode: () -> DialAccessMode,
    val serverHeader: String,
    val events: EventSink,
    val scope: CoroutineScope,
    val bufferDir: File?,
    val loungeEndpoints: LoungeEndpoints = LoungeEndpoints(),
    val onMediaRequest: (MediaRequest) -> Unit = {},
    val onDialRequest: (DialRequest) -> Unit = {},
    /** Another device on the LAN started a cast launch on the speaker named first, from the address named second. */
    val onGuestLaunch: (speaker: String, remote: String) -> Unit = { _, _ -> },
    val innerTubeEndpoints: InnerTubeEndpoints = InnerTubeEndpoints(),
    val sourceDownloader: SourceDownloader? = null,
    val upstreamPolicy: UpstreamPolicy = UpstreamPolicy.GOOGLEVIDEO,
    /** Wall clock in ms: it keeps counting while the CPU sleeps, unlike delay and nanoTime. Tests inject one. */
    val clock: () -> Long = System::currentTimeMillis,
    /** How often the bridge runs [Bridge.housekeeping] by itself while the CPU is awake. */
    val housekeepingIntervalMs: Long = HOUSEKEEPING_INTERVAL_MS,
    /**
     * What the phone's Wi-Fi is right now; a null result means there is no Wi-Fi at this moment. No supplier means the bridge
     * assumes it is present, as in JVM tests. The app passes a function that reads the system.
     */
    val currentNetwork: (() -> BridgeNetwork?)? = null,
    /** Tests only: builds the SSDP responder for a network. Null builds the production responder. */
    val responderFactory: ((BridgeNetwork, SsdpEvents, DialAccessPolicy) -> SsdpResponder)? = null,
    /**
     * Whether a cast that ends empties the speaker, read at clear time so the setting applies live. Null
     * means the fixed [Config.clearOnCastEnd] decides; the app passes a supplier over its settings.
     */
    val clearOnCastEnd: (() -> Boolean)? = null,
    /**
     * The prefix of the names shown in the cast menu, read when a renderer is set up and when [Bridge.refreshNames] runs.
     * Null means the fixed [Config.nameTemplate] decides; the app passes a supplier over its settings.
     */
    val namePrefix: (() -> String)? = null,
    /** Test seam, passed to every renderer: see [RendererDeps.allowLoopbackSpeakers]. */
    val allowLoopbackSpeakers: Boolean = false,
    /**
     * The cache cap in MiB, read by the file cache at every prune so a size changed in the app applies without a
     * restart. Null, or a supplier that throws, means [Config.cacheMb] decides.
     */
    val cacheMb: (() -> Int)? = null,
    /**
     * The speakers the author hid. Null means nothing is ever hidden (and [Bridge.hide] does nothing); the app passes
     * its process-level store, which also lives while the bridge is off.
     */
    val hidden: HiddenSpeakers? = null,
    /** The UDP port a speaker added by address is searched at. 1900 on a real network; tests point it at the fake speaker. */
    val manualSsdpPort: Int = MANUAL_SSDP_PORT,
    /** How long [Bridge.addManual] waits for the speaker's answer and its description. */
    val manualAnswerTimeoutMs: Long = MANUAL_ANSWER_TIMEOUT_MS,
    /**
     * The speakers added by address. A successful [Bridge.addManual] saves the speaker here and [Bridge.start]
     * probes every saved entry, so its card returns at every start. Null saves nothing and probes nothing.
     */
    val manual: ManualSpeakers? = null,
    /**
     * How often every saved manual speaker is asked again. A speaker that missed two rounds in a row is taken out of
     * the registry (no longer a cast target) and comes back when it answers. Tests shorten it.
     */
    val manualProbeIntervalMs: Long = MANUAL_PROBE_INTERVAL_MS,
    /**
     * Where [Bridge.addManual] fetches the device description of the typed address when the unicast
     * search got no reply (a Sonos speaker ignores it). The argument is the typed IPv4 literal; the result must keep that host
     * ([descriptionUrlAllowed]) or nothing is requested. Tests point it at the fake speaker.
     */
    val manualDescriptionUrl: (String) -> String = ::sonosDescriptionUrl,
    /** How long [Bridge.addManual] waits for a reply to the unicast search before it falls back to the description. */
    val manualSsdpGraceMs: Long = MANUAL_SSDP_GRACE_MS,
    /**
     * The names the author gave their renderers, by UDN. Process-level in the app, so it also lives
     * while the bridge is off. Null renames nothing ([Bridge.rename] answers false).
     */
    val names: RendererNames? = null,
    /**
     * The waits between the attempts to rebind the media server on a new address. A busy address is tried again after
     * each delay (so [Bridge.rebindNetwork] makes up to one more attempt than there are delays); an empty list tries once.
     */
    val mediaRebindBackoffMs: List<Long> = MEDIA_REBIND_BACKOFF_MS,
)
