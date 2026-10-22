package dev.t2u.renderer

// Port of tube2upnp src/renderer.js (Phase 2 slice), GPL-3.0-only.
//
// One UPnP MediaRenderer exposed as one YouTube cast receiver. The renderer is the lounge Player: play,
// pause, stop, seek and volume from the sender become SOAP calls to the speaker. Left for later phases:
// gapless SetNextAVTransportURI and prefetch (Phase 4), SponsorBlock and play history (v2). The format chain
// and per-speaker format learning (Phase 3) live in this class and in FormatChain.kt.

import dev.t2u.lounge.CastReceiver
import dev.t2u.lounge.Player
import dev.t2u.lounge.PlayerStatus
import dev.t2u.lounge.PlaylistRequestHandler
import dev.t2u.lounge.ReceiverFactory
import dev.t2u.lounge.ReceiverOptions
import dev.t2u.lounge.Video
import dev.t2u.lounge.Volume
import dev.t2u.media.FALLBACK_CHAIN
import dev.t2u.media.FILE_FORMATS
import dev.t2u.media.FORMATS
import dev.t2u.media.MediaRegistrar
import dev.t2u.media.ResolvedInfo
import dev.t2u.media.VideoEntry
import dev.t2u.media.VideoResolver
import dev.t2u.media.dlnaFeatures
import dev.t2u.media.mimeOf
import dev.t2u.upnp.DeviceDescription
import dev.t2u.upnp.DidlItem
import dev.t2u.upnp.EventSink
import dev.t2u.upnp.NoEvents
import dev.t2u.upnp.SinkProtocol
import dev.t2u.upnp.SoapClient
import dev.t2u.upnp.UpnpError
import dev.t2u.upnp.buildDidl
import dev.t2u.upnp.fetchDescription
import dev.t2u.upnp.parseSink
import dev.t2u.upnp.summarizeDidl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

private const val AV_TRANSPORT = "AVTransport"
private const val RENDERING_CONTROL = "RenderingControl"
private const val CONNECTION_MANAGER = "ConnectionManager"
private const val SERVICE_ID_PREFIX = "urn:upnp-org:serviceId:"

/** How long a track-start waits for the speaker to report PLAYING before the seek-after-play (renderer.js 8000). */
private const val SEEK_AFTER_PLAY_WAIT_MS = 8000L
private const val TRANSPORT_WAIT_STEP_MS = 300L

/** Step of the wait for a playlist refresh in flight at a track end. */
private const val QUEUE_SETTLE_STEP_MS = 100L

/**
 * Upper bound on how long a Pause waits for a speaker that is still starting. A Pause that lands while the
 * speaker is fetching its first bytes makes it drop that connection and refetch the stream at the next Play
 * (Lamp session B, 19:47:43), so the Pause is held back until the speaker reports PLAYING.
 */
private const val PAUSE_START_WAIT_MS = 3000L

/**
 * Upper bound on one speaker clear (CAST-06): the Stop, the emptied SetAVTransportURI and the read of the
 * current URI together. A speaker that does not answer must not hold up the lounge handler or the service
 * Stop (whose join is 10 s), and speakers are cleared in parallel, so the worst case is one budget.
 */
const val CLEAR_BUDGET_MS = 4000L

/** How long a clear waits for a SetAVTransportURI that a load has in flight, so the URI cannot land after the empty one (plan 03-21). */
const val CLEAR_INFLIGHT_WAIT_MS = CLEAR_BUDGET_MS / 2

/** How long, after a clear gave up on a slow speaker, the renderer keeps reading the speaker for a late-landing stream (plan 03-23). */
const val SLOW_CLEAR_WATCH_MS = 15_000L

/** The wait between two reads of that watch. */
const val SLOW_CLEAR_WATCH_INTERVAL_MS = 1_500L

/**
 * How close to the end of the track (seconds) the last polled position must be for a stop seen on the speaker
 * to count as the track ending. The poll interval is 2 s by default, so this covers one or two missed polls.
 */
const val TRACK_END_TOLERANCE_S = 10.0

/**
 * How long after a native Seek the position told to the sender is held at the seek target while the speaker has not
 * settled. A Sonos can spend 5 to 10 s TRANSITIONING after a Seek and reports a RelTime of 0-1 s meanwhile (device
 * session C, 2026-10-21); past this the speaker's own position is used again, so a speaker that never settles cannot
 * pin the seekbar.
 */
internal const val SEEK_SETTLE_DEADLINE_MS = 15_000L

/**
 * The seekbar of a sender counts on from the last position it was sent. When the speaker settles and its position
 * differs from where that count has got to by more than this (seconds), the sender is sent the speaker's position.
 */
internal const val SEEK_CORRECTION_TOLERANCE_S = 3.0

/**
 * The shortest time between two corrections of the sender's seekbar (plan 03-18, drift or settled Seek): a speaker with a
 * jittery RelTime cannot flood the phone with positions. Measured on the injected clock.
 */
internal const val DRIFT_MIN_GAP_MS = 10_000L

/**
 * How many consecutive polls must show the speaker more than [SEEK_CORRECTION_TOLERANCE_S] away from the sender's seekbar
 * before it is corrected: one odd reading (a stale answer, a buffering stall) is not acted on.
 */
internal const val DRIFT_CONFIRM_POLLS = 2

/**
 * Upper bound on the wait for a speaker's first PLAYING, as a multiple of the start timeout, counted from the load (or
 * the first Play of a stream loaded while paused). A Seek sent before the speaker has played restarts the start timeout
 * clock (a Sonos can spend 13 s seeking near the end of a file, Lamp 2026-10-21 22:01), so this cap is what keeps a speaker
 * that is dead but still takes Seeks from being waited for forever (T-03-47).
 */
internal const val START_HARD_CAP_FACTOR = 4

/** Transport states in which a speaker's RelTime can be believed again after a Seek. */
private val SEEK_SETTLED_STATES = setOf("PLAYING", "PAUSED_PLAYBACK", "STOPPED", "NO_MEDIA_PRESENT")

private val HTTP_URL = Regex("^https?:", RegexOption.IGNORE_CASE)

/**
 * Everything a [Renderer] needs from the outside. [http] must derive from `lanHttpClient()` (SVC-05),
 * [scope] is the service scope the renderer's own child scope hangs under, [clock] is epoch milliseconds.
 */
data class RendererDeps(
    val config: Config,
    val resolver: VideoResolver,
    val media: MediaRegistrar,
    val http: OkHttpClient,
    val createReceiver: ReceiverFactory,
    val scope: CoroutineScope,
    val events: EventSink = NoEvents,
    val clock: () -> Long = System::currentTimeMillis,
    val playlistRequestHandlerFactory: (() -> PlaylistRequestHandler)? = null,
    /** The wait between two reads of the slow-clear watch (plan 03-23); a test replaces it to step the watch by hand. */
    val pause: suspend (Long) -> Unit = { delay(it) },
)

/** What the speaker is playing, for the status UI and the notification. */
data class NowPlaying(
    val videoId: String,
    val title: String,
    val artist: String,
    val duration: Double,
    val format: String,
    val thumbnail: String,
)

/** The track the speaker was last told to play. [startAt] is the offset the stream itself starts at. */
private class Current(val video: Video, val format: String, val startAt: Int, val sourceMime: String) {
    @Volatile var loadedAt: Long = 0

    @Volatile var fetched: Boolean = false

    @Volatile var streamPath: String? = null

    /** False for a stream loaded while paused (SetAVTransportURI only) until the first Play is sent for it. */
    @Volatile var playSent: Boolean = false

    /**
     * When the wait for this stream's first PLAYING began (the load, or the first Play of a stream loaded while paused).
     * Unlike [loadedAt] a Seek does not move it, so it anchors the hard cap on the start wait.
     */
    @Volatile var startedAt: Long = 0

    /**
     * The position (seconds, absolute) this stream is meant to play from: the position it was loaded at, or the target of
     * the last Seek the speaker accepted. A start-failure reload goes back to it when the speaker was never polled
     * playing, so the reload does not restart the song.
     */
    @Volatile var wantedAt: Double = 0.0

    /** A position the post-Play Seek could not be sent to (the speaker refused it while starting); sent once it plays. */
    @Volatile var owedSeek: Double? = null

    /**
     * True after a native Seek the speaker accepted while paused, until the next Play: that Play can leave the speaker
     * TRANSITIONING for seconds (Lamp, device session D), so the sender is told buffering until it plays (plan 03-20).
     */
    @Volatile var seekedWhilePaused: Boolean = false
}

/** A track resolved, registered with the media server and described in DIDL, ready to send. */
private class PreparedItem(
    val current: Current,
    val title: String,
    val artist: String,
    val duration: Double,
    val thumbnail: String,
    val format: String,
    val mime: String,
    val url: String,
    val metadata: String,
)

/**
 * A native Seek the speaker accepted but may not have finished: until the speaker has visibly moved on from it the
 * position handed to the sender is [target]. [shown] and [shownAt] are the last position handed out and when, which
 * is what the sender's seekbar counts on from. [sawTransitioning] is set once the speaker has been seen in a state
 * other than a settled one (TRANSITIONING) after the Seek: a Sonos keeps reporting its OLD state, PLAYING with the old
 * RelTime, for about 0.7 s after it accepted the Seek (plan 03-16), so a bare PLAYING right after a Seek proves nothing.
 * [awaitPlay] marks the hold of a Play that followed a paused Seek (plan 03-20): the speaker is believed only once it is
 * PLAYING (or stopped), because it still reports PAUSED_PLAYBACK at the target for a moment after the Play.
 */
private class PendingSeek(val id: Int, val target: Double, val startedMs: Long, val awaitPlay: Boolean = false) {
    @Volatile var shown: Double = target

    @Volatile var shownAt: Long = startedMs

    @Volatile var sawTransitioning: Boolean = false
}

/** Result of the last poll: [rel] is already shifted by the stream offset. */
private data class PollPosition(val rel: Double, val dur: Double, val uri: String?, val at: Long)

/**
 * One speaker as one cast target (renderer.js `Renderer`). Concurrency: `loadSeq` invalidates a load a
 * newer play, a stop or [destroy] has overtaken; `destroyed` makes a load, a poll or an action launched
 * from a poll leave the speaker alone after removal; `expectStop` silences the poll while this renderer
 * is itself stopping or switching the speaker.
 */
class Renderer(
    override val udn: String,
    override val location: String,
    override val address: String,
    maxAge: Int,
    override val port: Int,
    private val deps: RendererDeps,
) : Player(), ManagedRenderer {
    private val config: Config get() = deps.config

    // Child scope: cancelled by destroy(), parented to the service scope so the service can stop everything.
    private val scope = CoroutineScope(deps.scope.coroutineContext + SupervisorJob(deps.scope.coroutineContext[Job]))

    @Volatile private var maxAgeS: Int = maxAge

    @Volatile override var lastSeen: Long = deps.clock()
        private set

    @Volatile override var name: String? = null
        private set

    @Volatile var description: DeviceDescription? = null
        private set

    @Volatile var nowPlaying: NowPlaying? = null
        private set

    @Volatile var lastError: String? = null
        private set

    private val soap = SoapClient(deps.http)

    @Volatile private var protocols: List<SinkProtocol>? = null

    /** The format that last reached PLAYING on this speaker (loaded from its store, see [confirmFormat]). */
    @Volatile private var learnedFormat: String? = null

    /** Formats that reached PLAYING on this speaker since this renderer started; a start failure never forgets one of these. */
    private val playedFormats: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    @Volatile private var ip: String? = null

    @Volatile private var receiver: CastReceiver? = null

    @Volatile private var current: Current? = null

    @Volatile private var transport: String? = null

    @Volatile private var position: PollPosition? = null

    /** The native Seek whose settling is still being waited for (plan 03-15); null when there is none. */
    @Volatile private var pending: PendingSeek? = null

    /** Numbers the pending Seeks, so a poll can tell that the state it read is from before the Seek it sees. */
    private val seekSeq = AtomicInteger()

    /** Consecutive polls that found the speaker away from the sender's seekbar (plan 03-18); only the poll touches it. */
    private var driftPolls = 0

    /** When the sender's seekbar was last corrected, for the minimum gap; only moved by [correctSender]. */
    @Volatile private var lastCorrectionAt: Long = Long.MIN_VALUE / 2

    private val loadSeq = AtomicInteger()

    /**
     * Counts the Pause and Play commands this app sends. A poll that began before such a command finished read
     * the speaker's state from before it, so its mirror would put the old state back for one poll (and tell the
     * senders); the poll compares the count and leaves the mirror out when it moved (plan 03-09, PLAY-07).
     */
    private val commandEpoch = AtomicInteger()

    @Volatile private var expectStop = false

    @Volatile private var sawPlaying = false

    @Volatile private var destroyed = false

    /**
     * True from the moment a load starts sending a SetAVTransportURI (the speaker may hold one of our streams from then on,
     * whether or not the load finishes) until the speaker was cleared (CAST-06 guard, plan 03-21).
     */
    @Volatile private var loadedThisSession = false

    /** One entry per SetAVTransportURI a load has sent and not yet seen answered; a clear waits for them (plan 03-21). */
    private val uriInFlight: MutableSet<CompletableDeferred<Unit>> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /**
     * Loads that have started and not yet registered their SetAVTransportURI call: still resolving, preparing or sending Stop.
     * A clear that finds one cancels it (bumps [loadSeq]) so it never reaches the speaker (plan 03-22).
     */
    private val loadsBeforeUri = java.util.concurrent.atomic.AtomicInteger()

    /** The speaker's own track metadata is reported once per load (reset in [adopt]). */
    @Volatile private var metaReported = false

    /** This speaker's persistent store, created in [init] and shared with the cast receiver. */
    @Volatile private var store: RendererDataStore? = null

    init {
        events = deps.events
        deps.playlistRequestHandlerFactory?.let { queue.requestHandler = it() }
    }

    override fun clockMs(): Long = deps.clock()

    /** The sender is told buffering while a Seek (or the Play after a paused Seek) is pending, until the poll settles it (plan 03-20). */
    override fun seekHoldActive(): Boolean = pending != null

    override fun refresh(maxAge: Int) {
        maxAgeS = maxAge
        lastSeen = deps.clock()
    }

    /** Many devices re-announce exactly at max-age, hence the 1.5 grace factor (renderer.js `isStale`). */
    override fun isStale(now: Long): Boolean = now - lastSeen > maxAgeS * 1000L * 1.5

    // ------------------------------------------------------------------------------------------ init

    /** Fetch the description, verify AVTransport, start the cast receiver. Returns false to skip this device. */
    override suspend fun init(): Boolean {
        val desc = fetchDescription(deps.http, location)
        description = desc

        if (!desc.services.containsKey(SERVICE_ID_PREFIX + AV_TRANSPORT)) {
            note("warn", "\"${desc.friendlyName}\" has no AVTransport service - skipping")
            return false
        }

        if (!config.nameFilter.accepts(desc.friendlyName)) {
            note("info", "\"${desc.friendlyName}\" does not pass the name filter - skipping")
            return false
        }

        val displayName = receiverName(config.nameTemplate, config.nameSuffix, desc.friendlyName, desc.manufacturer, desc.modelName)
        name = displayName

        safeCall(CONNECTION_MANAGER, "GetProtocolInfo")?.let { protocols = parseSink(it["Sink"]) }

        ip = config.advertiseIp ?: withContext(Dispatchers.IO) { localAddressFor(address) }

        val store = RendererDataStore(config.dataDir, udn) { message, error -> note("warn", "$message: ${error.message}") }
        this.store = store
        // T-03-19: a stored value counts only when it names a chain format.
        learnedFormat = (store.get(LEARNED_FORMAT_KEY) as? JsonPrimitive)
            ?.takeIf { it.isString }?.content?.takeIf { it in FALLBACK_CHAIN }
        val rec = deps.createReceiver.create(
            this,
            ReceiverOptions(
                dialPort = port,
                name = displayName,
                screenName = displayName,
                brand = desc.manufacturer.ifEmpty { "Tube2U[PnP]" },
                model = desc.modelName.ifEmpty { "UPnP Renderer" },
                dataStore = store,
            ),
        )
        receiver = rec
        rec.enableAutoplayOnConnect(config.autoplayOnConnect)
        rec.start()
        if (destroyed) {
            // Removed while the receiver was starting: do not leave it running.
            try {
                rec.stop()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // best effort
            }
            return false
        }
        note("info", "Cast receiver \"$displayName\" ready on $ip:$port")
        return true
    }

    override suspend fun destroy() {
        // A load or poll still in flight must not bring the speaker back to life afterwards.
        destroyed = true
        loadSeq.incrementAndGet()
        releaseStream(current)
        current = null
        pending = null
        stopPolling()
        slowClearWatch?.cancel()
        try {
            val rec = receiver
            if (rec != null && rec.status == "running") rec.stop()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            note("warn", "Error stopping receiver: ${e.message}")
        } finally {
            receiver = null
            scope.cancel()
        }
    }

    // ------------------------------------------------------------------------------------- Player impl

    override suspend fun doPlay(video: Video, position: Double): Boolean {
        if (destroyed) return false
        return load(video, position)
    }

    /** True when a newer play, a stop or destroy() has overtaken the load that took [seq]. */
    private fun superseded(seq: Int): Boolean = destroyed || seq != loadSeq.get()

    /**
     * Stop, SetAVTransportURI and (when [startPlaying]) Play. Deferral is only used by restart-seeks while
     * paused, where `startAt` already equals the target: the seek-after-play block below is for seekable
     * streams, which never come through a restart-seek. Without Play the speaker stays loaded and silent
     * and [doResume] sends the first Play of this stream.
     */
    private suspend fun load(
        video: Video,
        position: Double,
        format: String? = null,
        startPlaying: Boolean = true,
        forceOffset: Boolean = false,
    ): Boolean {
        val seq = loadSeq.incrementAndGet()
        pending = null
        stopPolling()
        loadsBeforeUri.incrementAndGet()
        var beforeUri = true
        try {
            val item = prepareItem(video, position, format, forceOffset)
            if (superseded(seq)) return false

            expectStop = true
            safeCall(AV_TRANSPORT, "Stop", "InstanceID" to 0)
            if (superseded(seq)) return false
            // From here the speaker may hold our stream, so a clear must act on it (the flag) and must wait for this call (the entry).
            val sending = CompletableDeferred<Unit>()
            uriInFlight.add(sending)
            loadedThisSession = true
            // Registered above, then the check: a clear either bumped loadSeq before this check (the load stops here) or it
            // finds the entry and the flag after its own bump and waits for the call, so no ordering lets the song land (plan 03-22).
            beforeUri = false
            loadsBeforeUri.decrementAndGet()
            try {
                if (superseded(seq)) return false
                call(
                    AV_TRANSPORT,
                    "SetAVTransportURI",
                    "InstanceID" to 0,
                    "CurrentURI" to item.url,
                    "CurrentURIMetaData" to item.metadata,
                )
            } finally {
                uriInFlight.remove(sending)
                sending.complete(Unit)
            }
            if (superseded(seq)) return false
            if (startPlaying) {
                call(AV_TRANSPORT, "Play", "InstanceID" to 0, "Speed" to 1)
                item.current.playSent = true
                if (superseded(seq)) return false

                if (position > 1 && item.current.startAt == 0) {
                    waitForState("PLAYING", SEEK_AFTER_PLAY_WAIT_MS)
                    if (superseded(seq)) return false
                    if (ok(AV_TRANSPORT, "Seek", "InstanceID" to 0, "Unit" to "REL_TIME", "Target" to seekHms(position))) {
                        // The speaker took the Seek but may still be seeking: hold the target until it settles (plan 03-15).
                        pending = PendingSeek(seekSeq.incrementAndGet(), position, deps.clock())
                    } else {
                        // A speaker still starting can refuse a Seek; the first PLAYING poll sends it again (plan 03-17).
                        item.current.owedSeek = position
                        note("warn", "Seek to ${Math.floor(position).toInt()}s after Play was refused - sending it again once the speaker plays")
                    }
                    if (superseded(seq)) return false
                }
            }

            adopt(item)
            // Evidence for the missing-artist question (G3): what we sent, as lengths and flags only.
            val sent = summarizeDidl(item.metadata)
            note(
                "info",
                "Sent \"${item.title}\" to renderer as ${item.format} (${item.mime})",
                "playDeferred" to !startPlaying,
                "artistChars" to (sent?.creator?.length ?: 0),
                "albumChars" to (sent?.album?.length ?: 0),
                "hasArt" to (sent?.albumArtUri?.isNotEmpty() == true),
                "didlChars" to item.metadata.length,
            )
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            lastError = e.message ?: e::class.java.simpleName
            note("error", "Play ${video.id} failed: $lastError")
            expectStop = false
            return false
        } finally {
            if (beforeUri) loadsBeforeUri.decrementAndGet()
        }
    }

    /**
     * Resolve a track and get it ready for the speaker. In auto mode a format that cannot be prepared or
     * registered (a failed download, say) hands over to the next accepted format within the same load; with
     * a forced format, or when the chain is used up, the failure goes to the caller.
     */
    private suspend fun prepareItem(video: Video, position: Double, format: String?, forceOffset: Boolean = false): PreparedItem {
        val info = deps.resolver.resolve(video.id)
        val address = checkNotNull(ip) { "renderer was not initialised" }
        var fmt = format ?: chooseFormat(config.outputFormat, learnedFormat, protocols, info.mime)
        while (true) {
            try {
                return buildItem(video, position, info, fmt, address, forceOffset)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val next = if (config.outputFormat == "auto") nextFormat(fmt, protocols, info.mime) else null
                if (next == null) throw e
                note("warn", "Preparing $fmt failed (${e.message}) - trying $next")
                fmt = next
            }
        }
    }

    /** Prepare, register and describe one track in [fmt]. */
    private suspend fun buildItem(
        video: Video,
        position: Double,
        info: ResolvedInfo,
        fmt: String,
        address: String,
        forceOffset: Boolean,
    ): PreparedItem {
        val mime = mimeOf(fmt, info.mime)
        // Seekable formats start at 0 and use AVTransport Seek, unless the speaker rejected it ([forceOffset]);
        // streams start ffmpeg at the offset.
        val seekable = FORMATS[fmt]?.seekable ?: false
        val startAt = restartOffset(position, info.duration, !seekable || forceOffset)
        val cur = Current(video, fmt, startAt, info.mime)
        cur.wantedAt = position
        val entry = VideoEntry(video.id, fmt, startAt, ext = info.ext, sourceMime = info.mime) {
            if (!cur.fetched) note("info", "Renderer started fetching the stream ($fmt)")
            cur.fetched = true
        }
        deps.media.prepare(entry)
        val streamPath = deps.media.registerVideo(entry)
        cur.streamPath = streamPath
        val url = "http://$address:${config.mediaPort}$streamPath"
        val protocolInfo = "http-get:*:$mime:${dlnaFeatures(fmt)}"
        val metadata = buildDidl(
            DidlItem(
                url = url,
                protocolInfo = protocolInfo,
                title = info.title,
                artist = info.artist,
                album = info.album,
                albumArt = info.thumbnail,
                duration = maxOf(0.0, info.duration - startAt),
                isVideo = mime.startsWith("video/"),
            ),
        )
        return PreparedItem(cur, info.title, info.artist, info.duration, info.thumbnail, fmt, mime, url, metadata)
    }

    /**
     * The offset a stream or file starts at: `floor(position)` when [offsetStart] and the position is above 1 s,
     * else 0. When the length is known it is clamped to one second before the end, so a seek past the end still
     * yields a stream with something in it (T-03-30).
     */
    private fun restartOffset(position: Double, duration: Double, offsetStart: Boolean): Int {
        if (!offsetStart || position <= 1) return 0
        val offset = Math.floor(position).toInt()
        return if (duration > 1) offset.coerceIn(0, maxOf(0, Math.floor(duration).toInt() - 1)) else maxOf(0, offset)
    }

    /** Tells the media server the stream of [c] is no longer needed, so it drops what it holds in memory for it (plan 03-20). */
    private fun releaseStream(c: Current?) {
        val path = c?.streamPath ?: return
        try {
            deps.media.release(path)
        } catch (_: Exception) {
            // freeing memory must never break a stop, a load or a removal
        }
    }

    /** Make [item] the current track after the speaker was told to play it. */
    private fun adopt(item: PreparedItem) {
        item.current.loadedAt = deps.clock()
        item.current.startedAt = item.current.loadedAt
        if (current !== item.current) releaseStream(current) // the stream this one replaces
        current = item.current
        nowPlaying = NowPlaying(item.current.video.id, item.title, item.artist, item.duration, item.format, item.thumbnail)
        position = PollPosition(item.current.startAt.toDouble(), item.duration, null, deps.clock())
        lastError = null
        sawPlaying = false
        metaReported = false
        expectStop = false
        loadedThisSession = true
        startPolling()
    }

    /**
     * Pauses the speaker. A Pause during start-up makes the speaker drop its first connection and refetch
     * the stream at the next Play (two ffmpeg launches), so while it is not PLAYING yet the Pause waits,
     * bounded by min(startTimeoutMs, [PAUSE_START_WAIT_MS]) counted from the load, then is sent anyway.
     * A stop, a newer load or removal that overtakes the wait cancels the Pause.
     */
    override suspend fun doPause(): Boolean {
        commandEpoch.incrementAndGet()
        try {
            if (pending?.awaitPlay == true) pending = null // the Play it waited for is cancelled by this Pause
            return pauseNow()
        } finally {
            commandEpoch.incrementAndGet()
        }
    }

    private suspend fun pauseNow(): Boolean {
        val seq = loadSeq.get()
        val c = current
        if (c != null && c.playSent) {
            val budget = minOf(config.startTimeoutMs, PAUSE_START_WAIT_MS)
            val remaining = budget - (deps.clock() - c.loadedAt)
            if (remaining > 0 && transportState() != "PLAYING") {
                waitForState("PLAYING", remaining)
            }
            if (superseded(seq)) return false
        }
        return ok(AV_TRANSPORT, "Pause", "InstanceID" to 0)
    }

    /**
     * Plays. For a stream loaded while paused this is the first Play it gets: the start-failure clock
     * restarts here, so a long wait for the sender does not count as a failed start. The same holds for a
     * Play after a Pause when the speaker was never seen PLAYING for this load (an early Pause, G4): the
     * 12 s check then measures from the Play and not from the load (Node measures from the load only).
     */
    override suspend fun doResume(): Boolean {
        commandEpoch.incrementAndGet()
        try {
            return resumeNow()
        } finally {
            commandEpoch.incrementAndGet()
        }
    }

    private suspend fun resumeNow(): Boolean {
        val c = current
        if (c != null && !c.playSent) {
            c.loadedAt = deps.clock()
            c.startedAt = c.loadedAt
            sawPlaying = false
            note("info", "Play sent for the stream loaded while paused", "startAt" to c.startAt)
            val played = ok(AV_TRANSPORT, "Play", "InstanceID" to 0, "Speed" to 1)
            if (played) c.playSent = true
            if (played) holdForPlayAfterPausedSeek(c)
            return played
        }
        if (c != null && !sawPlaying) {
            c.loadedAt = deps.clock()
            c.startedAt = c.loadedAt
            note("info", "Start clock restarted at Play (speaker not yet seen playing)")
        }
        val played = ok(AV_TRANSPORT, "Play", "InstanceID" to 0, "Speed" to 1)
        if (played && c != null) holdForPlayAfterPausedSeek(c)
        return played
    }

    /**
     * A Play that follows a Seek made while paused can leave the speaker TRANSITIONING for seconds (Lamp, `direct`, 8.6 s,
     * device session D, 2026-10-22). Until the poll sees it PLAYING the sender is told buffering at the wanted position, on the
     * same hold a Seek uses (plan 03-20): it replaces the Seek's own hold, which may still be pending or already settled.
     */
    private fun holdForPlayAfterPausedSeek(c: Current) {
        if (!c.seekedWhilePaused) return
        c.seekedWhilePaused = false
        pending = PendingSeek(seekSeq.incrementAndGet(), c.wantedAt, deps.clock(), awaitPlay = true)
    }

    /**
     * Stops the speaker and forgets the track. A plain Stop deliberately leaves the track loaded on the
     * speaker, as after any stop; emptying it is [doClear] (CAST-06), which only a disconnect, a reset and the
     * service Stop reach.
     */
    override suspend fun doStop(): Boolean {
        loadSeq.incrementAndGet() // cancel any pending load
        position = null
        pending = null
        transport = "STOPPED"
        releaseStream(current)
        current = null
        stopPolling()
        expectStop = true
        val stopped = ok(AV_TRANSPORT, "Stop", "InstanceID" to 0)
        nowPlaying = null
        return stopped
    }

    /** CAST-06: the last sender is gone, so leave the speaker with nothing loaded (see [clearSpeaker]). */
    override suspend fun doClear() {
        clearSpeaker()
    }

    /**
     * Stops the speaker and empties its transport URI, so the Sonos app has no track to resume. Only when this
     * app loaded something this session and the speaker's current URI is still one of this app's own streams
     * (T-03-26: another source's playback is never touched). The prefix check ignores the scheme, so a speaker
     * that rewrites it still matches. Bounded by [CLEAR_BUDGET_MS] (T-03-27), never throws, does nothing once
     * destroyed. The outcome is written to the evidence log (T-03-28): an empty URI the speaker refuses is the
     * evidence plan 03-14 needs to pick a Sonos-specific alternative. Only Stop and SetAVTransportURI are
     * sent, never a volume command.
     */
    override suspend fun clearSpeaker() {
        if (destroyed) return
        // A load still resolving or preparing is cancelled first, whatever the speaker holds: it must never reach the speaker
        // after the sender is gone. The clear does not wait for the resolver (plan 03-22).
        if (loadsBeforeUri.get() > 0) {
            loadSeq.incrementAndGet()
            pending = null
        }
        if (!loadedThisSession) return
        val run = ClearRun()
        try {
            // A load that is sending its URI must not go on to Play once the URI has landed: cancel it now, then wait for the call.
            val sending = uriInFlight.toList()
            if (sending.isNotEmpty()) {
                loadSeq.incrementAndGet()
                pending = null
            }
            val finished = withTimeoutOrNull(CLEAR_BUDGET_MS) {
                awaitUriCalls(sending, run)
                clearNow(run)
            }
            if (finished == null) {
                note("warn", "clear gave up: the speaker did not answer within ${CLEAR_BUDGET_MS / 1000} s")
                // The track is gone from our side either way; the speaker may still hold it.
                forgetTrack()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            note("warn", "clear failed: ${e.message}")
        }
        // The clear has returned (it never waits for this); a URI that was still on its way at the speaker is looked for afterwards.
        if (run.slow && !destroyed) startSlowClearWatch()
    }

    /** What one clear saw: whether a SetAVTransportURI of a cancelled load was still unanswered when it stopped waiting for it. */
    private class ClearRun {
        @Volatile var slow = false
    }

    /**
     * Waits (at most [CLEAR_INFLIGHT_WAIT_MS]) for the SetAVTransportURI calls of loads that were cancelled by a clear, so the
     * speaker has taken or refused the URI before the clear looks at it: the empty URI is then the last word at the speaker.
     * A call still unanswered after that is noted in [run], so the clear can start the watch for its late landing.
     */
    private suspend fun awaitUriCalls(sending: List<CompletableDeferred<Unit>>, run: ClearRun) {
        if (sending.isEmpty()) return
        val answered = withTimeoutOrNull(CLEAR_INFLIGHT_WAIT_MS) { sending.forEach { it.await() } }
        if (answered == null) {
            run.slow = true
            note("warn", "clear: the speaker did not answer SetAVTransportURI within ${CLEAR_INFLIGHT_WAIT_MS / 1000} s - clearing anyway")
        }
    }

    private suspend fun clearNow(run: ClearRun) {
        val uri = safeCall(AV_TRANSPORT, "GetMediaInfo", "InstanceID" to 0)?.get("CurrentURI").orEmpty()
        if (uri.isEmpty() || !isOurStream(uri)) {
            note("info", "clear skipped: the speaker is on another source")
            return
        }
        loadSeq.incrementAndGet() // cancels a pending load
        pending = null
        awaitUriCalls(uriInFlight.toList(), run) // a load that began its URI call after clearSpeaker took its snapshot
        expectStop = true
        stopPolling()
        safeCall(AV_TRANSPORT, "Stop", "InstanceID" to 0)
        val emptied = ok(AV_TRANSPORT, "SetAVTransportURI", "InstanceID" to 0, "CurrentURI" to "", "CurrentURIMetaData" to "")
        forgetTrack()
        if (!emptied) {
            note("warn", "the speaker refused an empty transport URI")
            return
        }
        readBack()
    }

    /** True when [uri] is one of this app's own streams; the scheme is ignored so a speaker that rewrites it still matches (T-03-26). */
    private fun isOurStream(uri: String): Boolean = uri.contains("$ip:${config.mediaPort}/stream/")

    /**
     * After an accepted empty URI: reads what the speaker holds (one GetMediaInfo) and writes the outcome as one evidence line.
     * A speaker that still holds one of our streams gets the empty URI once more and is read once more; a second failure is
     * logged, not retried. A failed read never fails the clear. Stream URIs carry a token and are never logged, only whether they were ours.
     */
    private suspend fun readBack() {
        val first = readCurrentUri()
        when {
            first == null -> note("info", "Cleared the speaker: read-back unavailable (the empty URI was accepted)")
            first.isEmpty() -> note("info", "Cleared the speaker: confirmed empty")
            !isOurStream(first) -> note("info", "Cleared the speaker: confirmed empty of our stream (the speaker is on another source now, left alone)")
            else -> {
                if (!ok(AV_TRANSPORT, "SetAVTransportURI", "InstanceID" to 0, "CurrentURI" to "", "CurrentURIMetaData" to "")) {
                    note("warn", "clear: still ours after retry - the speaker still holds one of our streams and refused the second empty URI")
                    return
                }
                val second = readCurrentUri()
                when {
                    second == null -> note("info", "Cleared the speaker: read-back unavailable (the empty URI was sent twice)")
                    second.isEmpty() || !isOurStream(second) -> note("info", "Cleared the speaker: confirmed empty after a second empty URI")
                    else -> note("warn", "clear: still ours after retry - the speaker still holds one of our streams after two empty URIs")
                }
            }
        }
    }

    /** The URI the speaker holds ("" when empty), or null when it could not be read. */
    private suspend fun readCurrentUri(): String? = safeCall(AV_TRANSPORT, "GetMediaInfo", "InstanceID" to 0)?.let { it["CurrentURI"].orEmpty() }

    /** The running slow-clear watch (plan 03-23), if any; a new clear replaces it and [destroy] cancels it. */
    @Volatile private var slowClearWatch: Job? = null

    /**
     * A clear gave up waiting for a SetAVTransportURI the speaker had not answered, so the stream may still land after the
     * clear. For at most [SLOW_CLEAR_WATCH_MS] the speaker's URI is read every [SLOW_CLEAR_WATCH_INTERVAL_MS]; if it becomes one
     * of our own streams, Stop and the empty URI are sent and the watch ends. It ends at once when a new load or clear moved
     * [loadSeq], when the renderer is destroyed, and when the speaker is on another source (nothing is sent then, T-03-55).
     * Runs in the renderer's own scope after the clear returned; the clear never waits for it (T-03-56).
     */
    private fun startSlowClearWatch() {
        val seq = loadSeq.get()
        slowClearWatch?.cancel()
        note("info", "clear watch started: reading the speaker every ${SLOW_CLEAR_WATCH_INTERVAL_MS} ms for up to ${SLOW_CLEAR_WATCH_MS / 1000} s")
        slowClearWatch = scope.launch {
            val reason = withTimeoutOrNull(SLOW_CLEAR_WATCH_MS + CLEAR_BUDGET_MS) { watchForLateStream(seq) } ?: "time used up"
            note("info", "clear watch ended: $reason")
        }
    }

    /** The loop of [startSlowClearWatch]; returns why it ended. */
    private suspend fun watchForLateStream(seq: Int): String {
        val startedAt = deps.clock()
        while (true) {
            deps.pause(SLOW_CLEAR_WATCH_INTERVAL_MS)
            if (destroyed) return "the renderer was destroyed"
            if (seq != loadSeq.get()) return "a new load or clear started"
            if (deps.clock() - startedAt >= SLOW_CLEAR_WATCH_MS) return "time used up"
            val uri = readCurrentUri()
            if (destroyed) return "the renderer was destroyed"
            if (seq != loadSeq.get()) return "a new load or clear started"
            if (uri.isNullOrEmpty()) continue
            if (!isOurStream(uri)) return "the speaker is on another source, left alone"
            expectStop = true
            safeCall(AV_TRANSPORT, "Stop", "InstanceID" to 0)
            val emptied = ok(AV_TRANSPORT, "SetAVTransportURI", "InstanceID" to 0, "CurrentURI" to "", "CurrentURIMetaData" to "")
            return if (emptied) "emptied a stream that landed after the clear" else "found a stream that landed after the clear, the speaker refused the empty URI"
        }
    }

    private fun forgetTrack() {
        releaseStream(current)
        current = null
        nowPlaying = null
        position = null
        pending = null
        transport = "STOPPED"
        loadedThisSession = false
    }

    /**
     * Seeks the speaker. Three paths, as in renderer.js `doSeek`:
     *  * a format that is not seekable (adts, mp3) restarts the stream at the offset (the Play is deferred while
     *    paused, so a paused seek stays paused);
     *  * a seekable format (direct, mp4, flac) gets a native AVTransport Seek. The target is relative to the file
     *    the speaker plays: a file that was built to start at an offset ([Current.startAt]) is sought at
     *    `position - startAt`;
     *  * when the speaker rejects the Seek, or the target lies before the start of an offset file, mp4 and flac load
     *    a new file that starts at the position (clamped to one second before the end, see [restartOffset]). `direct`
     *    has no such fallback: the seek is reported as failed.
     * Only Seek, Stop, SetAVTransportURI and Play reach the speaker; nothing touches the volume.
     * After a native Seek the position told to the sender is the target until the speaker leaves TRANSITIONING
     * (see [doGetPosition]); the reload and restart paths already return a correct start position.
     */
    override suspend fun doSeek(position: Double): Boolean {
        val c = current ?: return false
        val paused = status == PlayerStatus.PAUSED
        if (FORMATS[c.format]?.seekable != true) {
            // Restart the stream from the new position. Takes a moment longer than a native seek (ffmpeg
            // has to reconnect), but works on every speaker.
            note(
                "info",
                "Seek to ${Math.floor(position).toInt()}s by restarting the ${c.format} stream" +
                    if (paused) ", Play deferred until resume" else "",
            )
            return load(c.video, position, c.format, startPlaying = !paused)
        }
        val base = c.startAt
        val before = position < base
        if (!before) {
            val target = position - base
            if (ok(AV_TRANSPORT, "Seek", "InstanceID" to 0, "Unit" to "REL_TIME", "Target" to seekHms(target))) {
                // The speaker took the Seek but may still be seeking: hold the target until it settles (plan 03-15).
                pending = PendingSeek(seekSeq.incrementAndGet(), position, deps.clock())
                c.wantedAt = position
                if (paused) c.seekedWhilePaused = true
                // A speaker that has not played yet may spend long seeking: the start timeout is measured from the last
                // command that can delay its first PLAYING (plan 03-17). [Current.startedAt] still bounds the whole wait.
                if (!sawPlaying) c.loadedAt = deps.clock()
                return true
            }
        }
        if (c.format !in FILE_FORMATS) {
            note("warn", "Renderer rejected Seek - ${c.format} has no restart fallback")
            return false
        }
        val at = Math.floor(position).toInt()
        val why = if (before) "Seek to ${at}s is before the start of the ${c.format} file (${base}s)" else "Renderer rejected Seek"
        note("info", "$why - restarting ${c.format} at ${at}s" + if (paused) ", Play deferred until resume" else "")
        return load(c.video, position, c.format, startPlaying = !paused, forceOffset = true)
    }

    /**
     * The only place that changes the speaker's volume (PLAY-05, T-02-36): a sender asked for it. Nothing
     * in init, load, play, poll or stop sends SetVolume or SetMute.
     */
    override suspend fun doSetVolume(volume: Volume): Boolean {
        val levelSet = ok(
            RENDERING_CONTROL,
            "SetVolume",
            "InstanceID" to 0,
            "Channel" to "Master",
            "DesiredVolume" to volume.level,
        )
        // SetMute is optional in RenderingControl; do not fail the whole call if it is absent.
        safeCall(RENDERING_CONTROL, "SetMute", "InstanceID" to 0, "Channel" to "Master", "DesiredMute" to if (volume.muted) 1 else 0)
        return levelSet
    }

    override suspend fun doGetVolume(): Volume {
        val level = safeCall(RENDERING_CONTROL, "GetVolume", "InstanceID" to 0, "Channel" to "Master")
        val mute = safeCall(RENDERING_CONTROL, "GetMute", "InstanceID" to 0, "Channel" to "Master")
        return Volume(
            level = if (level != null) level["CurrentVolume"]?.trim()?.toIntOrNull() ?: 0 else 50,
            muted = mute?.get("CurrentMute")?.trim()?.lowercase() in listOf("1", "true"),
        )
    }

    /**
     * The position told to the sender. Normally the speaker's own RelTime plus the offset our ffmpeg started at. While
     * a native Seek is pending it is the seek target instead: the speaker accepted the Seek but may still report the
     * old position (a bare PLAYING for about 0.7 s on the Lamp) or a transient one (TRANSITIONING). The speaker's value
     * is used again once it has been seen leaving TRANSITIONING, or reports a position near the target, or when
     * [SEEK_SETTLE_DEADLINE_MS] has passed (see [seekSettled]). The transport state and the position are read once per
     * call and that pair decides both whether to hold and what to tell. The poll keeps its own raw copy in
     * [position], which the track-end rule reads, so the target never reaches that rule.
     */
    override suspend fun doGetPosition(): Double {
        val offset = current?.startAt ?: 0
        // A stream loaded while paused has not been played: the speaker's RelTime belongs to the previous one.
        if (current?.playSent == false) return offset.toDouble()
        val p = pending ?: return speakerPosition() ?: offset.toDouble()
        val state = transportState()
        val raw = speakerPosition()
        val now = deps.clock()
        val holding = !seekSettled(p, state, raw, now)
        val told = if (holding || raw == null) p.target else raw
        p.shown = told
        p.shownAt = now
        return told
    }

    /** The speaker's position with our offset added; null when it cannot be read or names another stream. */
    private suspend fun speakerPosition(): Double? {
        val offset = current?.startAt ?: 0
        val r = safeCall(AV_TRANSPORT, "GetPositionInfo", "InstanceID" to 0)
        // Right after a track change some speakers still report the previous track for a moment. If the
        // speaker names a different stream than ours, its numbers are not ours.
        if (r == null || isOnOtherStream(r["TrackURI"])) return null
        // The speaker counts from the start of the stream it got; add the offset our ffmpeg started at.
        return parseHms(r["RelTime"]) + offset
    }

    /**
     * Has the speaker finished a pending Seek, or have we waited long enough for it? [state] and [raw] are one reading
     * of the speaker (the transport state and the position with our offset; null when unreadable). Settled when the
     * deadline has passed, or the state is STOPPED or NO_MEDIA_PRESENT, or the state is PLAYING or PAUSED_PLAYBACK and
     * either the speaker has been seen TRANSITIONING since the Seek or its position is near the target (a fast speaker
     * that never shows TRANSITIONING). A PLAYING speaker with its old position is not settled: it has not yet noticed
     * the Seek. Records that TRANSITIONING was seen.
     */
    private fun seekSettled(p: PendingSeek, state: String?, raw: Double?, now: Long): Boolean {
        if (state != null && state !in SEEK_SETTLED_STATES) p.sawTransitioning = true
        if (now - p.startedMs > SEEK_SETTLE_DEADLINE_MS) return true
        // The hold of a Play after a paused Seek: PAUSED_PLAYBACK at the target is still the old state, only PLAYING ends it.
        if (p.awaitPlay) return state == "PLAYING" || state == "STOPPED" || state == "NO_MEDIA_PRESENT"
        if (state == null || state !in SEEK_SETTLED_STATES) return false
        if (state == "STOPPED" || state == "NO_MEDIA_PRESENT") return true
        if (p.sawTransitioning) return true
        return raw != null && nearSeekTarget(p, state, raw, now)
    }

    /**
     * Is [raw] where a speaker that has taken the Seek would be? Playing, it has moved on from the target for at most
     * the time since the Seek; paused, it sits at the target. Both within [SEEK_CORRECTION_TOLERANCE_S]. The old
     * position of a lagging speaker is far from the target, which is what tells the two apart.
     */
    private fun nearSeekTarget(p: PendingSeek, state: String, raw: Double, now: Long): Boolean {
        val tol = SEEK_CORRECTION_TOLERANCE_S
        val sinceSeek = if (state == "PLAYING") (now - p.startedMs) / 1000.0 else 0.0
        return raw >= p.target - tol && raw <= p.target + sinceSeek + tol
    }

    /**
     * Called by the poll with the transport [state] and position [polledRaw] (offset added; null when it did not read
     * one) it just read: drops a pending Seek the speaker has finished (or that outlived the deadline) and, when the
     * sender's seekbar has drifted from the speaker's position, sends the speaker's position once. The sender counts on
     * from the last position it was sent, so after a 5 s TRANSITIONING it is about 5 s ahead of a speaker that has only
     * just started playing.
     */
    private suspend fun settlePendingSeek(state: String, polledRaw: Double?, seekSeqAtStart: Int) {
        val p = pending ?: return
        // The Seek was sent after this poll began reading: the state it holds is from before the Seek.
        if (p.id > seekSeqAtStart) return
        val now = deps.clock()
        if (!seekSettled(p, state, polledRaw, now)) return
        // While the hold lasted a PLAYING player was reported to the sender as buffering (Player.getState, plan 03-20).
        val wasBuffering = status == PlayerStatus.PLAYING
        pending = null
        if (state == "STOPPED" || state == "NO_MEDIA_PRESENT") return // the stop branch of the poll decides what that means
        if (state !in SEEK_SETTLED_STATES) note("warn", "Speaker did not settle ${SEEK_SETTLE_DEADLINE_MS / 1000} s after the Seek - using its own position")
        val raw = polledRaw ?: speakerPosition() ?: return
        // A bar that was buffering did not count on, so it is where it was told; a playing one has counted on since.
        val senderIsAt = p.shown + if (status == PlayerStatus.PLAYING && !wasBuffering) (now - p.shownAt) / 1000.0 else 0.0
        val drifted = Math.abs(raw - senderIsAt) > SEEK_CORRECTION_TOLERANCE_S
        if ((wasBuffering || drifted) && (status == PlayerStatus.PLAYING || status == PlayerStatus.PAUSED)) {
            correctSender() // tells the sender the speaker's position, and playing instead of buffering
        }
    }

    /** Tells the sender the speaker's position again; every such correction counts for the minimum gap and ends a drift streak. */
    private suspend fun correctSender() {
        lastCorrectionAt = deps.clock()
        driftPolls = 0
        notifyPositionCorrection()
    }

    /**
     * Drift check (plan 03-18). The sender's seekbar counts on from the last position it was sent ([senderPosition]) and
     * the app sends a position only when something changes, so anything that moves the speaker without a command from the
     * sender (a seek or scrub in the Sonos app, a stale reading, a stream restart) leaves the bar wrong. When the speaker's
     * polled position [polledRaw] differs from that count by more than [SEEK_CORRECTION_TOLERANCE_S] on [DRIFT_CONFIRM_POLLS]
     * consecutive polls, and [DRIFT_MIN_GAP_MS] has passed since the last correction, the sender is sent the speaker's
     * position once. Nothing is sent while the two agree: an uneventful track costs no messages. Off (and the streak
     * forgotten) unless the player is PLAYING and the speaker is PLAYING our stream with no Seek hold pending.
     */
    private suspend fun checkDrift(state: String, polledRaw: Double?) {
        val c = current
        val expected = senderPosition()
        if (state != "PLAYING" || polledRaw == null || expected == null || status != PlayerStatus.PLAYING ||
            pending != null || c == null || !c.playSent
        ) {
            driftPolls = 0
            return
        }
        if (Math.abs(polledRaw - expected) <= SEEK_CORRECTION_TOLERANCE_S) {
            driftPolls = 0
            return
        }
        if (++driftPolls < DRIFT_CONFIRM_POLLS) return
        if (deps.clock() - lastCorrectionAt < DRIFT_MIN_GAP_MS) return
        note(
            "info",
            "Speaker is at ${polledRaw.toInt()} s, the sender's bar at ${expected.toInt()} s - sending the speaker's position",
        )
        correctSender()
    }

    override suspend fun doGetDuration(): Double {
        // yt-dlp's duration is the video's real length. The speaker's TrackDuration can be the previous
        // track's, the remaining part of an offset stream, or missing, so it is only a fallback.
        nowPlaying?.duration?.takeIf { it != 0.0 }?.let { return it }
        val r = safeCall(AV_TRANSPORT, "GetPositionInfo", "InstanceID" to 0)
        return if (r != null && !isOnOtherStream(r["TrackURI"])) parseHms(r["TrackDuration"]) else 0.0
    }

    /** Does the speaker report a stream other than the one we are playing? */
    private fun isOnOtherStream(trackUri: String?): Boolean {
        val ours = current?.streamPath
        return !trackUri.isNullOrEmpty() && ours != null && HTTP_URL.containsMatchIn(trackUri) && !trackUri.contains(ours)
    }

    // ------------------------------------------------------------------------------------- internals

    private suspend fun call(svc: String, action: String, vararg params: Pair<String, Any?>): Map<String, String> {
        val service = description?.services?.get(SERVICE_ID_PREFIX + svc)
            ?: throw UpnpError("$svc service not provided", "ENOSERVICE")
        return soap.callAction(service, action, params.toList())
    }

    /** A command whose failure the caller reports (false) but that must not throw. */
    private suspend fun ok(svc: String, action: String, vararg params: Pair<String, Any?>): Boolean =
        try {
            call(svc, action, *params)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            note("warn", "$action failed: ${e.message}")
            false
        }

    /** A call whose failure is ignored (null). */
    private suspend fun safeCall(svc: String, action: String, vararg params: Pair<String, Any?>): Map<String, String>? =
        try {
            call(svc, action, *params)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            note("debug", "$action failed (ignored): ${e.message}")
            null
        }

    private suspend fun transportState(): String? =
        safeCall(AV_TRANSPORT, "GetTransportInfo", "InstanceID" to 0)?.get("CurrentTransportState")

    private suspend fun waitForState(state: String, timeoutMs: Long): Boolean {
        val end = deps.clock() + timeoutMs
        while (deps.clock() < end) {
            if (transportState() == state) return true
            delay(TRANSPORT_WAIT_STEP_MS)
        }
        return false
    }

    // Polling instead of GENA eventing: many cheap speakers have broken event subscriptions, and eventing
    // needs an inbound callback port. One sequential loop, so a slow speaker never stacks polls.
    private val pollLock = Any()
    private var pollJob: Job? = null
    private var pollGen = 0

    @Volatile private var polls = 0

    private fun startPolling() {
        stopPolling()
        if (destroyed) return
        synchronized(pollLock) {
            val gen = ++pollGen
            pollJob = scope.launch {
                while (isActive && !destroyed && gen == pollGenSnapshot()) {
                    delay(config.pollIntervalMs)
                    if (gen != pollGenSnapshot()) break
                    try {
                        poll()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        note("debug", "Poll failed: ${e.message}")
                    }
                }
            }
        }
    }

    private fun pollGenSnapshot(): Int = synchronized(pollLock) { pollGen }

    private fun stopPolling() {
        synchronized(pollLock) {
            pollGen++
            pollJob?.cancel()
            pollJob = null
        }
    }

    private suspend fun poll() {
        if (expectStop) return
        polls++
        val epoch = commandEpoch.get()
        val seekSeqAtStart = seekSeq.get()
        val state = transportState()
        if (state == null || destroyed) return
        transport = state
        // The speaker's position as this poll read it, for the pending-Seek rule; null when not read or not our stream.
        var polledRaw: Double? = null
        if (state == "PLAYING" || state == "PAUSED_PLAYBACK") {
            val r = safeCall(AV_TRANSPORT, "GetPositionInfo", "InstanceID" to 0)
            if (r != null) {
                val uri = r["TrackURI"]
                val startAt = current?.startAt ?: 0
                // Do not take position or duration from a speaker still on the previous track.
                val stale = isOnOtherStream(uri)
                if (!stale) polledRaw = parseHms(r["RelTime"]) + startAt
                position = PollPosition(
                    rel = if (stale) position?.rel ?: startAt.toDouble() else parseHms(r["RelTime"]) + startAt,
                    dur = nowPlaying?.duration?.takeIf { it != 0.0 } ?: if (stale) 0.0 else parseHms(r["TrackDuration"]),
                    uri = uri,
                    at = deps.clock(),
                )
                if (!stale && !metaReported) {
                    metaReported = true
                    reportSpeakerMetadata(r["TrackMetaData"])
                }
            }
        }
        if (destroyed) return
        // A Pause or Play of ours finished while this poll was reading: what it read is from before that command.
        if (epoch != commandEpoch.get()) return

        settlePendingSeek(state, polledRaw, seekSeqAtStart)
        // A poll that began before a Seek was sent read the speaker from before it: its position proves nothing.
        checkDrift(state, if (seekSeqAtStart == seekSeq.get()) polledRaw else null)

        // If the speaker has not reached PLAYING well after Play, assume it cannot handle the stream.
        val c = current
        if (!sawPlaying && state != "PLAYING" && c != null && status == PlayerStatus.PLAYING &&
            startTimedOut(c, deps.clock())
        ) {
            handleStartFailure(c)
            return
        }

        when {
            state == "PLAYING" -> {
                if (!sawPlaying) {
                    sawPlaying = true
                    if (c != null) confirmFormat(c)
                }
                if (c != null) c.seekedWhilePaused = false // the speaker plays: nothing is owed to a Play after a paused Seek
                if (c != null && c === current) sendOwedSeek(c)
                // Play pressed in the Sonos app for a stream loaded while paused: stop answering startAt only.
                if (c != null && !c.playSent && !isOnOtherStream(position?.uri)) c.playSent = true
                if (status == PlayerStatus.PAUSED) notifyExternalStateChange(PlayerStatus.PLAYING)
            }
            state == "PAUSED_PLAYBACK" -> {
                if (status == PlayerStatus.PLAYING) notifyExternalStateChange(PlayerStatus.PAUSED)
            }
            (state == "STOPPED" || state == "NO_MEDIA_PRESENT") && sawPlaying && status == PlayerStatus.PLAYING -> {
                // The speaker stopped. Both the launched coroutines below run outside this one, because they
                // stop and restart polling, which would cancel the poll in the middle of the call.
                stopPolling()
                val p = position
                if (trackEnded(p, c?.startAt ?: 0)) {
                    note("info", "Track finished on renderer - advancing queue")
                    advanceAfterTrackEnd()
                } else {
                    // Stop pressed on the speaker, or a stream that died early: mirror it, do not skip the song.
                    // The track stays loaded (clearing is only for a disconnect and the service Stop).
                    note(
                        "info",
                        "Track stopped on the speaker at ${p?.rel?.toInt()} of ${p?.dur?.toInt()} s - mirroring the stop, not advancing",
                    )
                    scope.launch { notifyExternalStateChange(PlayerStatus.STOPPED) }
                }
            }
        }
    }

    /**
     * Has the wait for the speaker's first PLAYING run out? [Current.loadedAt] is the last command that can delay it (the
     * load, a Play, a Seek), so the start timeout counts from the latest of them. A native Seek the speaker accepted and
     * has not finished (a pending Seek, at most [SEEK_SETTLE_DEADLINE_MS]) also holds the verdict back: a speaker that is
     * busy seeking is not a speaker that cannot play the format (Lamp, 13 s near the end of a file, 2026-10-21 22:01).
     * [Current.startedAt] never moves and caps the whole wait at [START_HARD_CAP_FACTOR] times the start timeout, so a
     * dead speaker that keeps taking Seeks is still given up on (T-03-47).
     */
    private fun startTimedOut(c: Current, now: Long): Boolean {
        if (now - c.startedAt > config.startTimeoutMs * START_HARD_CAP_FACTOR) return true
        if (now - c.loadedAt <= config.startTimeoutMs) return false
        val seeking = pending?.let { now - it.startedMs <= SEEK_SETTLE_DEADLINE_MS } == true
        return !seeking
    }

    /**
     * The Seek the load could not send because the speaker refused it while starting (see [Current.owedSeek]). Sent once,
     * when the speaker is seen playing. If it is refused again the speaker plays from the start, and the sender, which was
     * told the requested position, is told the speaker's real one.
     */
    private suspend fun sendOwedSeek(c: Current) {
        val owed = c.owedSeek ?: return
        c.owedSeek = null
        if (ok(AV_TRANSPORT, "Seek", "InstanceID" to 0, "Unit" to "REL_TIME", "Target" to seekHms(owed - c.startAt))) {
            pending = PendingSeek(seekSeq.incrementAndGet(), owed, deps.clock())
            note("info", "Seek to ${Math.floor(owed).toInt()}s sent once the speaker played")
        } else {
            c.wantedAt = 0.0
            note("warn", "Seek to ${Math.floor(owed).toInt()}s refused again - the speaker plays from the start")
            correctSender()
        }
    }

    /**
     * Does a stop seen on the speaker mean the track played to its end? Yes when the end cannot be told apart
     * from a stop (length unknown, no position, the position never moved off the start: the old Node
     * behaviour applies) or when the last polled position is within [TRACK_END_TOLERANCE_S] of the length.
     */
    private fun trackEnded(p: PollPosition?, startAt: Int): Boolean =
        p == null || p.dur <= 1 || p.rel - startAt < 1 || p.rel >= p.dur - TRACK_END_TOLERANCE_S

    /**
     * Moves the queue on after a track end. A playlist refresh in flight makes [next] a no-op, so the move waits
     * for it, bounded by [Config.queueSettleTimeoutMs] on the injected clock; a newer play, a stop or removal
     * that overtakes the wait ends it. When nothing could be advanced the player ends STOPPED, so the senders
     * and the notification do not show a playing track that has silently stopped (T-03-29).
     */
    private fun advanceAfterTrackEnd() {
        val seq = loadSeq.get()
        scope.launch {
            val deadline = deps.clock() + config.queueSettleTimeoutMs
            while (queue.isUpdating && !superseded(seq) && deps.clock() < deadline) delay(QUEUE_SETTLE_STEP_MS)
            if (superseded(seq)) return@launch
            if (queue.isUpdating) note("warn", "Playlist refresh still in flight after ${config.queueSettleTimeoutMs} ms - advancing anyway")
            val advanced = next()
            if (!advanced && !superseded(seq) && status == PlayerStatus.PLAYING) notifyExternalStateChange(PlayerStatus.STOPPED)
        }
    }

    /** What the speaker keeps of the DIDL we sent (G3): lengths and flags only, never the artist, album or title text. */
    private fun reportSpeakerMetadata(trackMetaData: String?) {
        val s = summarizeDidl(trackMetaData)
        note(
            "info",
            "Speaker reports track metadata",
            "reported" to (s != null),
            "titleChars" to (s?.title?.length ?: 0),
            "creatorChars" to (s?.creator?.length ?: 0),
            "artistChars" to (s?.artist?.length ?: 0),
            "albumChars" to (s?.album?.length ?: 0),
            "hasArt" to (s?.albumArtUri?.isNotEmpty() == true),
            "upnpClass" to (s?.upnpClass?.take(80) ?: ""),
            "streamContentChars" to (s?.streamContent?.length ?: 0),
        )
    }

    /**
     * The first PLAYING of a load proves [c]'s format works on this speaker: in auto mode remember it, in the
     * speaker's store, so the next start (and the next run of the app) begins with it. A forced format is
     * the user's choice and is never written.
     */
    private suspend fun confirmFormat(c: Current) {
        playedFormats.add(c.format)
        if (config.outputFormat != "auto" || learnedFormat == c.format) return
        learnedFormat = c.format
        note("info", "Format ${c.format} works on this speaker - remembered")
        store?.set(LEARNED_FORMAT_KEY, JsonPrimitive(c.format))
    }

    /**
     * The speaker accepted the URL but never started playing (a refused upstream host, a format it cannot
     * decode and a firewall all look the same from here). In auto mode the format chain moves on: forget
     * the remembered format, then reload the same track at the same position in the next format the speaker
     * accepts; the 12 s clock of that attempt starts at its own Play. With a forced format, or at the end of
     * the chain, stop and say why.
     */
    private suspend fun handleStartFailure(c: Current) {
        val auto = config.outputFormat == "auto"
        val next = if (auto) nextFormat(c.format, protocols, c.sourceMime) else null
        // A remembered format that has played on this speaker in this session is working: a start failure of one track
        // (or of another format) does not make the app forget it (plan 03-17, T-03-48). One that never played is cleared.
        val remembered = learnedFormat
        if (auto && remembered != null && remembered !in playedFormats) {
            learnedFormat = null
            store?.set(LEARNED_FORMAT_KEY, null)
        }
        if (next != null) {
            note("warn", "Renderer did not start ${c.format} - trying $next")
            stopPolling()
            // Where the speaker was last polled playing, if that is newer than the last load or Seek and off the start;
            // otherwise where this stream was meant to play from (the load position or the last accepted Seek).
            val polled = position?.takeIf { it.at >= c.loadedAt && it.rel - c.startAt >= 1 }?.rel
            val resumeAt = polled ?: maxOf(c.wantedAt, c.startAt.toDouble())
            val before = loadSeq.get()
            scope.launch {
                val started = load(c.video, resumeAt, next, startPlaying = true)
                // Only a failure of this reload counts; a newer play, a stop or removal has moved loadSeq on.
                if (!started && !destroyed && loadSeq.get() == before + 1) notifyExternalStateChange(PlayerStatus.STOPPED)
            }
            return
        }
        val why = if (c.fetched) "fetched the stream but never started playing" else "never requested the stream"
        lastError = "Renderer $why"
        note(
            "error",
            lastError + if (c.fetched) "." else ". Check that it can reach http://$ip:${config.mediaPort} (firewall, advertise address).",
        )
        stopPolling()
        scope.launch { notifyExternalStateChange(PlayerStatus.STOPPED) }
    }

    /** Reports through the evidence sink (kind "renderer"); never carries signed URLs, and never throws. */
    private fun note(level: String, message: String, vararg fields: Pair<String, Any?>) {
        try {
            deps.events.event(
                "renderer",
                mapOf<String, Any?>("level" to level, "speaker" to (description?.friendlyName ?: address), "message" to message) + fields,
            )
        } catch (_: Exception) {
            // an event sink must not break playback
        }
    }
}

/** Parses a UPnP H:MM:SS(.fff) time; 0 for anything else, including NOT_IMPLEMENTED (renderer.js `parseHms`). */
fun parseHms(t: String?): Double {
    if (t.isNullOrEmpty() || t.contains("NOT_IMPLEMENTED", ignoreCase = true)) return 0.0
    var total = 0.0
    for (part in t.split(':')) {
        val v = part.trim().toDoubleOrNull()?.takeIf { it.isFinite() } ?: return 0.0
        total = total * 60 + v
    }
    return total
}

/** HH:MM:SS with zero-padded hours, floored, never negative: the REL_TIME form of Seek (renderer.js `hms`). */
fun seekHms(sec: Double): String {
    val s = maxOf(0L, Math.floor(sec).toLong())
    return String.format(Locale.ROOT, "%02d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
}
