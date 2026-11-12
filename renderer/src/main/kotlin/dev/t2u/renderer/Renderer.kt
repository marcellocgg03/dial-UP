package dev.t2u.renderer

// Port of tube2upnp src/renderer.js, GPL-3.0-only.
//
// One UPnP MediaRenderer exposed as one YouTube cast receiver. The renderer is the lounge Player: play,
// pause, stop, seek and volume from the sender become SOAP calls to the speaker. The gapless next-track handover
// (SetNextAVTransportURI) and the prefetch behind it, the format chain and the per-speaker format learning live in this
// class and in FormatChain.kt. SponsorBlock and play history are not implemented.

import dev.t2u.lounge.AutoplayMode
import dev.t2u.lounge.CastReceiver
import dev.t2u.lounge.Player
import dev.t2u.lounge.PlayerStatus
import dev.t2u.lounge.ReceiverOptions
import dev.t2u.lounge.Video
import dev.t2u.lounge.Volume
import dev.t2u.media.FILE_FORMATS
import dev.t2u.media.FORMATS
import dev.t2u.media.ResolvedInfo
import dev.t2u.media.VideoEntry
import dev.t2u.media.dlnaFeatures
import dev.t2u.media.mimeOf
import dev.t2u.upnp.DeviceDescription
import dev.t2u.upnp.DidlItem
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

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

    private val _name = MutableStateFlow<String?>(null)

    override var name: String?
        get() = _name.value
        private set(value) {
            _name.value = value
        }

    @Volatile var description: DeviceDescription? = null
        private set

    /**
     * The name [receiverName] built from the description (template and suffix applied); null until [init] has read it. A user
     * name replaces it on show, never in matching.
     */
    @Volatile private var describedName: String? = null

    // The status UI follows these through [ui]; the property syntax and the private setters stay as they were.
    private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)
    private val _lastError = MutableStateFlow<String?>(null)

    var nowPlaying: NowPlaying?
        get() = _nowPlaying.value
        private set(value) {
            _nowPlaying.value = value
        }

    var lastError: String?
        get() = _lastError.value
        private set(value) {
            _lastError.value = value
        }

    /** What the sender-facing player state says that no other flow carries: the nav buttons, the queue and the position. */
    private data class Live(val hasPrevious: Boolean, val hasNext: Boolean, val queueHasCurrent: Boolean, val position: SpeakerPosition?)

    private val _live = MutableStateFlow(Live(hasPrevious = false, hasNext = false, queueHasCurrent = false, position = null))

    private fun snapshot(status: PlayerStatus, name: String?, track: NowPlaying?, error: String?, live: Live) = SpeakerUi(
        udn = udn,
        name = name ?: address,
        address = address,
        status = status,
        track = track,
        error = error,
        hasPrevious = live.hasPrevious,
        hasNext = live.hasNext,
        queueHasCurrent = live.queueHasCurrent,
        position = live.position,
    )

    /**
     * The UI snapshot of this speaker: one StateFlow that changes with the player status, the track, the
     * last error and the sender-facing state, and ends with [destroy] (it runs on the renderer's own scope). No SOAP call
     * feeds it; the poll and the player events already hold everything it shows.
     */
    private val liveUi = combine(statusFlow, _name, _nowPlaying, _lastError, _live) { status, name, track, error, live ->
        snapshot(status, name, track, error, live)
    }.distinctUntilChanged().stateIn(
        scope,
        SharingStarted.Eagerly,
        snapshot(PlayerStatus.IDLE, null, null, null, _live.value),
    )

    val ui: StateFlow<SpeakerUi> = liveUi

    private val soap = SoapTransport(
        deps.http,
        description = { description },
        destroyed = { destroyed },
        clock = deps.clock,
        note = { level, message -> note(level, message) },
    )

    private val formats = FormatLearner(
        outputFormat = { config.outputFormat },
        store = { store },
        note = { level, message -> note(level, message) },
    )

    @Volatile private var ip: String? = null

    /**
     * The addresses this renderer served streams from before the phone moved, at most [OWN_HOSTS_KEPT], newest last, never the
     * current one. A speaker that still holds a URL with one of them has one of our streams, not another source's. The
     * token path still has to match, so a stranger's URL is not made ours by this list.
     */
    @Volatile var ownHosts: List<String> = emptyList()
        private set

    /** True from [onNetworkLost] until the next [onNetworkChanged]: no poll runs while the phone has no network. */
    @Volatile private var networkDown = false

    @Volatile private var receiver: CastReceiver? = null

    @Volatile private var current: Current? = null

    @Volatile private var transport: String? = null

    @Volatile private var position: PollPosition? = null

    /** The native Seek whose settling is still being waited for; null when there is none. */
    @Volatile private var pending: PendingSeek? = null

    /** Numbers the pending Seeks, so a poll can tell that the state it read is from before the Seek it sees. */
    private val seekSeq = AtomicInteger()

    /** Consecutive polls that found the speaker away from the sender's seekbar; only the poll touches it. */
    private var driftPolls = 0

    /** When the sender's seekbar was last corrected, for the minimum gap; only moved by [correctSender]. */
    @Volatile private var lastCorrectionAt: Long = Long.MIN_VALUE / 2

    private val loadSeq = AtomicInteger()

    /** Tracks that could not be prepared one after another; reset when a play starts or a gapless switch is adopted. */
    private val unplayableStreak = AtomicInteger()

    /**
     * Counts the Pause and Play commands this app sends. A poll that began before such a command finished read
     * the speaker's state from before it, so its mirror would put the old state back for one poll (and tell the
     * senders); the poll compares the count and leaves the mirror out when it moved.
     */
    private val commandEpoch = AtomicInteger()

    @Volatile private var expectStop = false

    @Volatile private var sawPlaying = false

    @Volatile private var destroyed = false

    /**
     * True from the moment a load starts sending a SetAVTransportURI (the speaker may hold one of our streams from then on,
     * whether or not the load finishes) until the speaker was cleared (a clear is only owed after the app loaded something).
     */
    @Volatile private var loadedThisSession = false

    /** One entry per SetAVTransportURI a load has sent and not yet seen answered; a clear waits for them. */
    private val uriInFlight: MutableSet<CompletableDeferred<Unit>> = ConcurrentHashMap.newKeySet()

    /**
     * Loads that have started and not yet registered their SetAVTransportURI call: still resolving, preparing or sending Stop.
     * A clear that finds one cancels it (bumps [loadSeq]) so it never reaches the speaker.
     */
    private val loadsBeforeUri = AtomicInteger()

    /**
     * Consecutive polls that read a non-empty speaker URI that is not one of our own streams while we hold a track.
     * Two of them mean another source took the speaker over. Only the poll moves it, [adopt] resets it.
     */
    private var foreignPolls = 0

    /** The speaker's own track metadata is reported once per load (reset in [adopt]). */
    @Volatile private var metaReported = false

    /**
     * The prepared next track, the prefetch that fills it and what is known about gapless support. Taken by [load] when the
     * slot holds the video asked for; every other fate (replaced, dropped, not adopted) releases its stream.
     */
    private val prefetch = PrefetchCoordinator(
        scope = scope,
        configSource = { config },
        clock = deps.clock,
        loadSeq = { loadSeq.get() },
        currentSource = { current },
        destroyedSource = { destroyed },
        queueUpdating = { queue.isUpdating },
        upcomingVideo = { getUpcomingVideo() },
        prepareItem = { video, position, format -> prepareItem(video, position, format) },
        releaseStream = { releaseStream(it) },
        soap = soap,
        storeSource = { store },
        modelSource = { model },
        emit = { level, message, fields -> note(level, message, *fields) },
    )

    /** Whether the speaker is known to take SetNextAVTransportURI: null while unknown (tests and the cast state read it). */
    internal val gaplessSupport: Boolean? get() = prefetch.gaplessSupported

    /** The identity a stored gapless answer belongs to ([speakerModel] of the description), set in [init]. */
    @Volatile private var model: String = ""

    /**
     * The queued track the speaker played on into by itself, between the poll seeing the switch and [doPlay] adopting it
     * (renderer.js `#advanced`). Set and cleared only on the paths that follow the switch.
     */
    @Volatile private var advanced: Queued? = null

    /**
     * Polls in a row that read STOPPED at the end of a track while the speaker already held the queued stream.
     * Touched only by the poll; reset by [adopt] and by any poll that reads PLAYING.
     */
    private var stopGracePolls = 0

    /** True only while the hand-off's own [next] runs: the one Stop that may be skipped because the speaker already moved on. */
    @Volatile private var handingOff = false

    /** This speaker's persistent store, created in [init] and shared with the cast receiver. */
    @Volatile private var store: RendererDataStore? = null

    /** What [init] gave the cast receiver; a revived receiver is built from the same options (same DIAL port, name and store). */
    @Volatile private var receiverOptions: ReceiverOptions? = null

    /** When the cast receiver stopped itself (epoch ms of [RendererDeps.clock]); null while it runs or never did. */
    @Volatile private var terminatedAt: Long? = null

    /** The earliest [reviveIfDue] may start a new receiver: 10 s after the stop, then 5 minutes after each failed start. */
    @Volatile private var nextReviveAt: Long = Long.MAX_VALUE

    private val reviveLock = Mutex()

    init {
        events = deps.events
        deps.playlistRequestHandlerFactory?.let { queue.requestHandler = it() }
        addStateListener { event ->
            val state = event.current
            val nav = getNavInfo()
            val idle = state.status == PlayerStatus.IDLE || state.status == PlayerStatus.STOPPED
            _live.value = Live(
                hasPrevious = nav.hasPrevious,
                hasNext = nav.hasNext,
                queueHasCurrent = state.queue.current != null,
                position = if (idle) null else SpeakerPosition(state.position, state.duration, deps.clock()),
            )
        }
    }

    override fun clockMs(): Long = deps.clock()

    /** The sender is told buffering while a Seek (or the Play after a paused Seek) is pending, until the poll settles it. */
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

        val described = receiverName(deps.nameTemplate(), desc.friendlyName, desc.manufacturer, desc.modelName)
        describedName = described
        val displayName = shownRendererName(deps.userName(udn), described)
        name = displayName

        soap.safeCall(CONNECTION_MANAGER, "GetProtocolInfo")?.let { formats.setProtocols(parseSink(it["Sink"])) }

        ip = config.advertiseIp ?: withContext(Dispatchers.IO) { localAddressFor(address) }

        val store = RendererDataStore(config.dataDir, udn) { message, error -> note("warn", "$message: ${error.message}") }
        this.store = store
        formats.load(store)
        // What this speaker answered to SetNextAVTransportURI in an earlier run. Another model's answer is dropped.
        model = speakerModel(desc.manufacturer, desc.modelName, desc.modelNumber)
        val storedGapless = store.get(LEARNED_GAPLESS_KEY)
        prefetch.gaplessSupported = readGaplessMemory(storedGapless, model, deps.clock())
        val storedModel = gaplessMemoryModel(storedGapless)
        if (storedModel != null && storedModel != model) store.set(LEARNED_GAPLESS_KEY, null)
        val options = ReceiverOptions(
            dialPort = port,
            name = displayName,
            screenName = displayName,
            brand = desc.manufacturer.ifEmpty { "Tube2U[PnP]" },
            model = desc.modelName.ifEmpty { "UPnP Renderer" },
            dataStore = store,
        )
        receiverOptions = options
        val rec = deps.createReceiver.create(this, options)
        rec.setOnTerminate { onReceiverTerminated(it) }
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

    /**
     * Rename: [userName] is the author's new name for this speaker, or null to go back to the described
     * one. Does nothing before [init] has read the description (init reads the store itself) or after [destroy]. Otherwise the
     * card name changes, the receiver options are copied with the new name and screen name (so a revived receiver keeps it), and
     * the running receiver is told, which serves and advertises the new DIAL friendly name. The card stays renamed when the
     * receiver cannot take the name. The UDN, port, store and lounge sessions are untouched.
     */
    suspend fun rename(userName: String?) {
        val described = describedName ?: return
        if (destroyed) return
        val shown = shownRendererName(userName, described)
        name = shown
        receiverOptions = receiverOptions?.copy(name = shown, screenName = shown)
        val rec = receiver ?: return
        try {
            rec.rename(shown)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            note("warn", "Cast receiver could not take the new name (${e.javaClass.simpleName})")
        }
    }

    /**
     * The name template changed (the author's prefix): the described name is built again from the description with the
     * template [RendererDeps.nameTemplate] now returns, then the rename path runs, so a name the author gave this speaker
     * still wins, the card and the DIAL friendly name change at once, and a running cast is untouched.
     */
    suspend fun refreshName() {
        val desc = description ?: return
        if (describedName == null || destroyed) return
        describedName = receiverName(deps.nameTemplate(), desc.friendlyName, desc.manufacturer, desc.modelName)
        rename(deps.userName(udn))
    }

    /**
     * The cast receiver stopped itself because a lounge session failed for good (renderer.js only records lastError;
     * here the speaker must come back into the cast menu). Schedules the first revival 10 s from now.
     */
    private fun onReceiverTerminated(error: Throwable) {
        if (destroyed) return
        val at = deps.clock()
        terminatedAt = at
        nextReviveAt = at + REVIVE_FIRST_DELAY_MS
        note(
            "warn",
            "Cast receiver stopped itself (${error.javaClass.simpleName}) - starting it again in ${REVIVE_FIRST_DELAY_MS / 1000} s",
        )
    }

    /**
     * Gives the speaker a new cast receiver once the old one stopped itself and its time has come (10 s after the stop,
     * then every [RendererRegistry.RETRY_FAILED_MS] while a start fails). The new receiver is built from the options the
     * first one had, so it listens on the same DIAL port with the same name and per-speaker store (same DIAL uuid and
     * screen IDs). Returns null when nothing is due, true when a new receiver runs, false when the start failed.
     */
    override suspend fun reviveIfDue(now: Long): Boolean? {
        if (destroyed || terminatedAt == null || now < nextReviveAt) return null
        val options = receiverOptions ?: return null
        return reviveLock.withLock {
            if (destroyed || terminatedAt == null || now < nextReviveAt) return@withLock null
            var rec: CastReceiver? = null
            try {
                rec = deps.createReceiver.create(this, options)
                rec.setOnTerminate { onReceiverTerminated(it) }
                rec.enableAutoplayOnConnect(config.autoplayOnConnect)
                rec.start()
                if (destroyed) {
                    // Removed while the receiver was starting: do not leave it running.
                    runCatching { rec.stop() }
                    return@withLock null
                }
                receiver = rec
                terminatedAt = null
                nextReviveAt = Long.MAX_VALUE
                note("info", "Cast receiver \"${options.name}\" started again on $ip:$port")
                true
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                runCatching { rec?.stop() }
                nextReviveAt = now + RendererRegistry.RETRY_FAILED_MS
                note(
                    "warn",
                    "Cast receiver could not start again (${e.javaClass.simpleName}) - trying again in ${RendererRegistry.RETRY_FAILED_MS / 60_000} minutes",
                )
                false
            }
        }
    }

    override suspend fun destroy() {
        // A load or poll still in flight must not bring the speaker back to life afterwards.
        destroyed = true
        terminatedAt = null
        loadSeq.incrementAndGet()
        releaseStream(current)
        prefetch.dropQueued()
        dropAdvanced()
        current = null
        pending = null
        pollLoop.stop()
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
        val adv = advanced
        advanced = null
        if (adv != null && adv.video.id == video.id && position < 2) {
            // The speaker is already playing it (gapless): bring our state in line, send nothing.
            loadSeq.incrementAndGet()
            adopt(adv.item, alreadyPlaying = true)
            unplayableStreak.set(0)
            note("info", "Now playing \"${adv.item.title}\" (continued gaplessly)", "videoId" to video.id)
            return true
        }
        releaseStream(adv?.item?.current)
        return playWithRetries(video, position)
    }

    /**
     * Loads [video] for a play the sender asked for and handles a failed preparation: a failure for a
     * reason that is not clearly permanent is tried again after each of [Config.playRetryDelaysMs] with a fresh lookup (the
     * cached resolve is invalidated, as the download ladder does), then the track is skipped by [skipUnplayable], so one
     * broken track does not end the playlist. A failure of the speaker itself, or a load a newer play overtook, is not a track
     * problem and just ends the play as before. The seek restart and the format fallback call [load] directly and never retry or skip.
     */
    private suspend fun playWithRetries(video: Video, position: Double): Boolean {
        var attempt = 0
        while (true) {
            when (val result = loadResult(video, position)) {
                is LoadResult.Started -> {
                    unplayableStreak.set(0)
                    return true
                }
                is LoadResult.PrepareFailed -> {
                    val delays = config.playRetryDelaysMs
                    if (attempt >= delays.size || isPermanentlyUnavailable(result.error.message)) {
                        skipUnplayable(video, loadSeq.get(), result.error)
                        return false
                    }
                    val seq = loadSeq.get()
                    val why = result.error.message ?: result.error::class.java.simpleName
                    note(
                        "warn",
                        "Could not prepare ${video.id} ($why) - retrying in ${Math.round(delays[attempt] / 1000.0)} s (${attempt + 1}/${delays.size})",
                        "videoId" to video.id,
                    )
                    deps.resolver.invalidate(video.id)
                    if (!waitForRetry(delays[attempt], seq)) return outcomeOfNewerPlay()
                    attempt++
                }
                is LoadResult.SpeakerFailed, is LoadResult.Superseded -> return false
            }
        }
    }

    /**
     * What [playWithRetries] reports when a newer play, a stop or a removal overtook its retry wait. Player.play puts this play's
     * result on the senders when doPlay returns, which after a wait of seconds would be STOPPED over the newer play's PLAYING.
     * So the answer waits for the newer play to settle (bounded by [SUPERSEDED_SETTLE_MS]) and reports what the player shows:
     * true while it plays (the senders see PLAYING once more, which is what they already show), false after a stop or a failure.
     */
    private suspend fun outcomeOfNewerPlay(): Boolean {
        val deadline = deps.clock() + SUPERSEDED_SETTLE_MS
        while (!destroyed && status == PlayerStatus.LOADING && deps.clock() < deadline) delay(RETRY_WAIT_STEP_MS)
        return !destroyed && status == PlayerStatus.PLAYING
    }

    /**
     * Waits [ms] for a retry in small steps, so a newer play, a stop or removal (a change of [loadSeq]) ends the wait at once
     * instead of after the full delay. False when the wait was overtaken.
     */
    private suspend fun waitForRetry(ms: Long, seq: Int): Boolean {
        val deadline = deps.clock() + ms
        while (!superseded(seq)) {
            val left = deadline - deps.clock()
            if (left <= 0) return true
            delay(minOf(left, RETRY_WAIT_STEP_MS))
        }
        return false
    }

    /**
     * Moves the queue on after [video] could not be prepared: the player ends STOPPED for this track (the sender is
     * told through the normal state change), then the queue's next track, or the autoplay video, is played. [MAX_UNPLAYABLE_IN_A_ROW]
     * failures in a row, or the end of the queue, leave the player STOPPED. Only the queue's current video moves the queue:
     * a direct doPlay of another video does not. The note carries the id and a reason class, never the message (it may hold a URL).
     */
    private fun skipUnplayable(video: Video, seq: Int, error: Exception) {
        if (queue.current?.id != video.id) return
        val n = unplayableStreak.incrementAndGet()
        if (n >= MAX_UNPLAYABLE_IN_A_ROW) {
            unplayableStreak.set(0)
            note("error", "$n tracks in a row could not be played - stopping")
            return
        }
        val reason = if (isPermanentlyUnavailable(error.message)) "unavailable" else error::class.java.simpleName
        note("warn", "Skipping ${video.id}: it could not be resolved or prepared", "videoId" to video.id, "reason" to reason)
        scope.launch {
            // Player.play reports STOPPED for the failed track after doPlay returns; the move on waits for that report.
            val deadline = deps.clock() + SKIP_STOPPED_WAIT_MS
            while (status == PlayerStatus.LOADING && !superseded(seq) && deps.clock() < deadline) delay(SKIP_STEP_MS)
            if (superseded(seq) || queue.current?.id != video.id || status != PlayerStatus.STOPPED) return@launch
            // An autoplay video has no playlist index and Player.play only looks around it once it plays, so the video after a
            // failed one is not known yet: ask for it, or the chain would end at the first failure.
            if ((video.context?.index ?: -1) < 0 && queue.autoplayMode == AutoplayMode.ENABLED && queue.autoplay == null) {
                try {
                    queue.refreshAfterHop()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    note("debug", "Looking up the video after ${video.id} failed: ${e.message ?: e::class.java.simpleName}")
                }
                if (superseded(seq) || queue.current?.id != video.id || status != PlayerStatus.STOPPED) return@launch
            }
            advanceAfterTrackEnd()
        }
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
        offsetOnly: Boolean = false,
    ): Boolean = loadResult(video, position, format, startPlaying, forceOffset, offsetOnly) is LoadResult.Started

    /**
     * [load] with the reason it did not start: [LoadResult.PrepareFailed] when taking or preparing the item
     * failed (the lookup, the format chain, the registration), which the sender's own plays retry and skip past;
     * [LoadResult.SpeakerFailed] when a call to the speaker failed; [LoadResult.Superseded] when a newer play, a stop or
     * removal overtook the load.
     */
    private suspend fun loadResult(
        video: Video,
        position: Double,
        format: String? = null,
        startPlaying: Boolean = true,
        forceOffset: Boolean = false,
        offsetOnly: Boolean = false,
    ): LoadResult {
        val seq = loadSeq.incrementAndGet()
        // Taken after the bump: a prefetch that finishes later sees the new loadSeq and releases its item itself. Node re-sends
        // the speaker's next slot after a load; the gapless hand-off does the same through the poll.
        val taken = prefetch.takeQueued()
        pending = null
        pollLoop.stop()
        loadsBeforeUri.incrementAndGet()
        var beforeUri = true
        var prepared: PreparedItem? = null
        var adopted = false
        var preparing = true
        var startingSpeaker = false
        try {
            prepared = takePreparedOrPrepare(taken, video, position, format, forceOffset, offsetOnly)
            val item = prepared
            preparing = false
            if (superseded(seq)) return LoadResult.Superseded
            expectStop = true
            soap.safeCall(AV_TRANSPORT, "Stop", "InstanceID" to 0)
            if (superseded(seq)) return LoadResult.Superseded
            val sending = registerUriSend()
            beforeUri = false
            loadsBeforeUri.decrementAndGet()
            startingSpeaker = true
            if (sendUri(item, seq, sending)) return LoadResult.Superseded
            if (superseded(seq)) return LoadResult.Superseded
            if (startPlaying) {
                if (sendPlay(item, seq)) return LoadResult.Superseded
                startingSpeaker = false
                if (position > 1 && item.current.startAt == 0 && seekAfterPlay(item, position, seq)) return LoadResult.Superseded
            }
            startingSpeaker = false
            adopt(item)
            adopted = true
            noteSent(item, video, startPlaying)
            return LoadResult.Started
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (startingSpeaker && !superseded(seq) && config.outputFormat == "streaming" && prepared?.format == "adts") {
                note("warn", "Renderer rejected adts (${e.message}) - trying mp3")
                releaseStream(prepared.current)
                prepared = null
                return loadResult(video, position, "mp3", startPlaying, forceOffset, offsetOnly)
            }
            return failedLoad(e, video, seq, preparing)
        } finally {
            if (beforeUri) loadsBeforeUri.decrementAndGet()
            // A prepared item this load did not adopt (superseded, or failed after preparing) must not stay pinned.
            if (!adopted) releaseStream(prepared?.current)
        }
    }

    /** The slot's track when it is the video asked for as a plain start, else the slot's stream is freed and the video is prepared. */
    private suspend fun takePreparedOrPrepare(
        taken: Queued?,
        video: Video,
        position: Double,
        format: String?,
        forceOffset: Boolean,
        offsetOnly: Boolean,
    ): PreparedItem {
        if (taken != null && format == null && !forceOffset && !offsetOnly && position < 2 && taken.video.id == video.id) {
            note("info", "Using prepared track \"${taken.item.title}\"", "videoId" to video.id)
            return taken.item
        }
        releaseStream(taken?.item?.current)
        return prepareItem(video, position, format, forceOffset, offsetOnly)
    }

    /**
     * One entry per SetAVTransportURI a load is about to send; [sendUri] completes it. From here the speaker may hold our
     * stream, so a clear must act on it (the flag) and must wait for this call (the entry).
     */
    private fun registerUriSend(): CompletableDeferred<Unit> {
        val sending = CompletableDeferred<Unit>()
        uriInFlight.add(sending)
        loadedThisSession = true
        return sending
    }

    /**
     * SetAVTransportURI for [item]; true when a newer play, a stop or removal overtook the load before the call. The entry was
     * registered before this check: a clear either bumped loadSeq before it (the load stops here) or it finds the entry and the
     * flag after its own bump and waits for the call, so no ordering lets the song land.
     */
    private suspend fun sendUri(item: PreparedItem, seq: Int, sending: CompletableDeferred<Unit>): Boolean {
        try {
            if (superseded(seq)) return true
            soap.call(
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
        return false
    }

    /** Play for [item]; true when the load was overtaken meanwhile. */
    private suspend fun sendPlay(item: PreparedItem, seq: Int): Boolean {
        soap.call(AV_TRANSPORT, "Play", "InstanceID" to 0, "Speed" to 1)
        item.current.playSent = true
        return superseded(seq)
    }

    /** The seek owed to a stream that cannot start at an offset: wait for PLAYING, then Seek; true when the load was overtaken. */
    private suspend fun seekAfterPlay(item: PreparedItem, position: Double, seq: Int): Boolean {
        soap.waitForState("PLAYING", SEEK_AFTER_PLAY_WAIT_MS)
        if (superseded(seq)) return true
        if (soap.ok(AV_TRANSPORT, "Seek", "InstanceID" to 0, "Unit" to "REL_TIME", "Target" to seekHms(position))) {
            // The speaker took the Seek but may still be seeking: hold the target until it settles.
            pending = PendingSeek(seekSeq.incrementAndGet(), position, deps.clock())
        } else {
            // A speaker still starting can refuse a Seek; the first PLAYING poll sends it again.
            item.current.owedSeek = position
            note("warn", "Seek to ${Math.floor(position).toInt()}s after Play was refused - sending it again once the speaker plays")
        }
        return superseded(seq)
    }

    /** Evidence for the missing-artist question (G3): what we sent, as lengths and flags only. */
    private fun noteSent(item: PreparedItem, video: Video, startPlaying: Boolean) {
        val sent = summarizeDidl(item.metadata)
        note(
            "info",
            "Sent \"${item.title}\" to renderer as ${item.format} (${item.mime})",
            "videoId" to video.id,
            "playDeferred" to !startPlaying,
            "artistChars" to (sent?.creator?.length ?: 0),
            "albumChars" to (sent?.album?.length ?: 0),
            "hasArt" to (sent?.albumArtUri?.isNotEmpty() == true),
            "didlChars" to item.metadata.length,
        )
    }

    /** Records the failure of a load and says which kind it was: a speaker call, an overtaken load, or the preparing. */
    private fun failedLoad(e: Exception, video: Video, seq: Int, preparing: Boolean): LoadResult {
        lastError = e.message ?: e::class.java.simpleName
        note("error", "Play ${video.id} failed: $lastError")
        expectStop = false
        return when {
            !preparing -> LoadResult.SpeakerFailed(e)
            superseded(seq) -> LoadResult.Superseded
            else -> LoadResult.PrepareFailed(e)
        }
    }

    // ------------------------------------------------------------------------------------------ prefetch

    /**
     * The video that follows the current one (renderer.js `getUpcomingVideo`): the queue's next, else the autoplay video when
     * autoplay is on. One read of the queue state.
     */
    fun getUpcomingVideo(): Video? {
        val state = queue.getState()
        return state.next ?: if (autoplayMode == AutoplayMode.ENABLED) state.autoplay else null
    }

    /** Forgets the track the speaker played on into and frees its stream. */
    private fun dropAdvanced() {
        val a = advanced
        advanced = null
        releaseStream(a?.item?.current)
    }

    /**
     * Resolve a track and get it ready for the speaker. In auto mode a format that cannot be prepared or
     * registered (a failed download, say) hands over to the next accepted format within the same load; with
     * a forced format, or when the chain is used up, the failure goes to the caller.
     */
    private suspend fun prepareItem(
        video: Video,
        position: Double,
        format: String?,
        forceOffset: Boolean = false,
        offsetOnly: Boolean = false,
    ): PreparedItem {
        val info = deps.resolver.resolve(video.id)
        val address = checkNotNull(ip) { "renderer was not initialised" }
        var fmt = format ?: formats.choose(info.mime)
        if (fmt == "direct" && startsMidTrack(position)) {
            // The relayed YouTube file cannot start at an offset, and a speaker that seeks inside a large fragmented MP4 scans
            // it from the start (minutes on a long track), so the next format starts ffmpeg at the position instead.
            val offsetFormat = formats.offsetFormat(info.mime)
            note("info", "Starting at ${Math.floor(position).toInt()}s: direct cannot start mid-track, using $offsetFormat")
            fmt = offsetFormat
        }
        while (true) {
            try {
                return buildItem(video, position, info, fmt, address, forceOffset, offsetOnly)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val next = formats.nextAfter(fmt, info.mime)
                if (next == null) throw e
                note("warn", "Preparing $fmt failed (${e.message}) - trying $next")
                fmt = next
            }
        }
    }

    /** True when [position] needs an offset start, which only automatic mode handles (a forced format is used as asked). */
    private fun startsMidTrack(position: Double): Boolean = config.outputFormat == "auto" && position > 1

    /** Prepare, register and describe one track in [fmt]. */
    private suspend fun buildItem(
        video: Video,
        position: Double,
        info: ResolvedInfo,
        fmt: String,
        address: String,
        forceOffset: Boolean,
        offsetOnly: Boolean,
    ): PreparedItem {
        val mime = mimeOf(fmt, info.mime)
        // Seekable formats start at 0 and use AVTransport Seek, unless the speaker rejected it ([forceOffset]);
        // streams start ffmpeg at the offset.
        val seekable = FORMATS[fmt]?.seekable ?: false
        val offsetStart = position > 1 && (!seekable || forceOffset)
        val startAt = restartOffset(position, info.duration, offsetStart)
        val cur = Current(video, fmt, startAt, info.mime, offsetOnly || offsetStart)
        cur.wantedAt = position
        val entry = VideoEntry(
            video.id,
            fmt,
            startAt,
            ext = info.ext,
            sourceMime = info.mime,
            onRequest = {
                if (!cur.fetched) note("info", "Renderer started fetching the stream ($fmt)")
                cur.fetched = true
            },
            onFailure = { cur.streamFailed = true },
        )
        val streamPath = deps.media.prepareAndRegister(entry)
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
     * yields a stream with something in it.
     */
    private fun restartOffset(position: Double, duration: Double, offsetStart: Boolean): Int {
        if (!offsetStart || position <= 1) return 0
        val offset = Math.floor(position).toInt()
        return if (duration > 1) offset.coerceIn(0, maxOf(0, Math.floor(duration).toInt() - 1)) else maxOf(0, offset)
    }

    /** Tells the media server the stream of [c] is no longer needed, so it drops what it holds in memory for it. */
    private fun releaseStream(c: Current?) {
        val path = c?.streamPath ?: return
        try {
            deps.media.release(path)
        } catch (_: Exception) {
            // freeing memory must never break a stop, a load or a removal
        }
    }

    /**
     * Make [item] the current track after the speaker was told to play it. With [alreadyPlaying] (a gapless switch) the speaker
     * has been playing it since before we noticed: it counts as played at once, so its position is the speaker's, the drift check
     * is on and the start watchdog has nothing to wait for.
     */
    private fun adopt(item: PreparedItem, alreadyPlaying: Boolean = false) {
        item.current.loadedAt = deps.clock()
        item.current.startedAt = item.current.loadedAt
        if (current !== item.current) releaseStream(current) // the stream this one replaces
        current = item.current
        nowPlaying = NowPlaying(
            item.current.video.id,
            item.title,
            item.artist,
            item.duration,
            item.format,
            item.thumbnail,
            item.current.sourceMime,
        )
        position = PollPosition(item.current.startAt.toDouble(), item.duration, null, deps.clock())
        lastError = null
        sawPlaying = alreadyPlaying
        if (alreadyPlaying) {
            item.current.playSent = true
            formats.markPlayed(item.current.format)
        }
        metaReported = false
        foreignPolls = 0
        stopGracePolls = 0
        expectStop = false
        loadedThisSession = true
        pollLoop.start()
    }

    /**
     * Pauses the speaker. A Pause during start-up makes the speaker drop its first connection and refetch
     * the stream at the next Play (two ffmpeg launches), so while it is not PLAYING yet the Pause waits,
     * bounded by min(startTimeoutMs, [PAUSE_START_WAIT_MS]) counted from the load, then is sent anyway.
     * A stop, a newer load or removal that overtakes the wait cancels the Pause.
     */
    override suspend fun doPause(): Boolean {
        if (destroyed) return false
        commandEpoch.incrementAndGet()
        try {
            if (pending?.awaitPlay == true) pending = null // the Play it waited for is cancelled by this Pause
            return pauseNow()
        } finally {
            commandEpoch.incrementAndGet()
        }
    }

    private suspend fun pauseNow(): Boolean {
        if (onAnotherSource(emptyCounts = true)) return skipped("pause")
        val seq = loadSeq.get()
        val c = current
        if (c != null && c.playSent) {
            val budget = minOf(config.startTimeoutMs, PAUSE_START_WAIT_MS)
            val remaining = budget - (deps.clock() - c.loadedAt)
            if (remaining > 0 && soap.transportState() != "PLAYING") {
                soap.waitForState("PLAYING", remaining)
            }
            if (superseded(seq)) return false
        }
        return soap.ok(AV_TRANSPORT, "Pause", "InstanceID" to 0)
    }

    /**
     * Plays. For a stream loaded while paused this is the first Play it gets: the start-failure clock
     * restarts here, so a long wait for the sender does not count as a failed start. The same holds for a
     * Play after a Pause when the speaker was never seen PLAYING for this load (an early Pause, G4): the
     * 12 s check then measures from the Play and not from the load (Node measures from the load only).
     */
    override suspend fun doResume(): Boolean {
        if (destroyed) return false
        commandEpoch.incrementAndGet()
        try {
            return resumeNow()
        } finally {
            commandEpoch.incrementAndGet()
        }
    }

    private suspend fun resumeNow(): Boolean {
        if (onAnotherSource(emptyCounts = true)) return skipped("resume")
        val c = current
        if (c != null && !c.playSent) {
            c.loadedAt = deps.clock()
            c.startedAt = c.loadedAt
            sawPlaying = false
            note("info", "Play sent for the stream loaded while paused", "startAt" to c.startAt)
            val played = soap.ok(AV_TRANSPORT, "Play", "InstanceID" to 0, "Speed" to 1)
            if (played) c.playSent = true
            if (played) holdForPlayAfterPausedSeek(c)
            return played
        }
        if (c != null && config.outputFormat == "streaming" && FORMATS[c.format]?.seekable == false) {
            val seq = loadSeq.get()
            val at = speakerPosition() ?: position?.rel?.also {
                note("warn", "Could not read the paused position - resuming from the last known position")
            } ?: run {
                note("error", "Could not determine the paused position - cannot resume the stream")
                return false
            }
            if (superseded(seq) || current !== c || status != PlayerStatus.PAUSED) return false
            note("info", "Resuming ${c.format} by restarting the stream at ${Math.floor(at).toInt()}s")
            return load(c.video, at, c.format, offsetOnly = c.offsetOnly)
        }
        if (c != null && !sawPlaying) {
            c.loadedAt = deps.clock()
            c.startedAt = c.loadedAt
            note("info", "Start clock restarted at Play (speaker not yet seen playing)")
        }
        val played = soap.ok(AV_TRANSPORT, "Play", "InstanceID" to 0, "Speed" to 1)
        if (played && c != null) holdForPlayAfterPausedSeek(c)
        return played
    }

    /**
     * A Play that follows a Seek made while paused can leave the speaker TRANSITIONING for seconds (Lamp, `direct`, 8.6 s,
     * device session D, 2026-10-22). Until the poll sees it PLAYING the sender is told buffering at the wanted position, on the
     * same hold a Seek uses: it replaces the Seek's own hold, which may still be pending or already settled.
     */
    private fun holdForPlayAfterPausedSeek(c: Current) {
        if (!c.seekedWhilePaused) return
        c.seekedWhilePaused = false
        pending = PendingSeek(seekSeq.incrementAndGet(), c.wantedAt, deps.clock(), awaitPlay = true)
    }

    /**
     * Stops the speaker and forgets the track. A plain Stop deliberately leaves the track loaded on the
     * speaker, as after any stop; emptying it is [doClear], which only a disconnect, a reset and the
     * service Stop reach.
     */
    override suspend fun doStop(): Boolean {
        // yt-cast-receiver's play() stops a PLAYING player first. When the speaker already moved on to the queued track by itself,
        // that Stop must not reach it: the track would be cut off and reloaded. Only the hand-off's own next() gets this; a Stop
        // from the phone at any other time (including while the hand-off waits for a refresh) still stops the speaker.
        val switching = if (handingOff) advanced else null
        if (switching == null) dropAdvanced()
        loadSeq.incrementAndGet() // cancel any pending load
        position = null
        pending = null
        transport = "STOPPED"
        releaseStream(current)
        current = null
        pollLoop.stop()
        expectStop = true
        advanced = switching
        // The track and the polling are forgotten either way, but a speaker that plays another source gets no Stop.
        // A speaker that holds nothing still gets it (harmless, as before). The switch shortcut comes before any speaker read.
        val stopped = if (destroyed) {
            // The registry's stopOne/clearOne already sent the Stop before the removal; the lounge shutdown that follows
            // must not wait on a speaker that may never answer.
            true
        } else if (switching != null) {
            note("info", "stop not sent: the speaker already moved on to the queued track")
            true
        } else if (onAnotherSource(emptyCounts = false)) {
            note("info", "stop skipped: the speaker is on another source")
            true
        } else {
            soap.ok(AV_TRANSPORT, "Stop", "InstanceID" to 0)
        }
        nowPlaying = null
        return stopped
    }

    /**
     * the last sender is gone. With the "clear the speaker when a cast ends" setting on, leave the speaker
     * with nothing loaded (see [clearSpeaker]); with it off (the default) the player has already stopped and stopped polling,
     * and the speaker keeps the track. Standard cast end only: a card's Clear button calls [clearSpeaker] directly and is not
     * gated by the setting.
     */
    override suspend fun doClear() {
        if (!deps.clearOnCastEnd()) {
            note("info", "cast ended: the speaker keeps its track, clear-when-cast-ends is off")
            return
        }
        clearSpeaker()
    }

    /**
     * Stops the speaker and empties its transport URI, so the Sonos app has no track to resume. Only when this
     * app loaded something this session and the speaker's current URI is still one of this app's own streams
     * (another source's playback is never touched). The prefix check ignores the scheme, so a speaker
     * that rewrites it still matches. Bounded by [CLEAR_BUDGET_MS], never throws, does nothing once
     * destroyed. The outcome is written to the evidence log: an empty URI the speaker refuses is the
     * evidence for choosing a Sonos-specific alternative. Only Stop and SetAVTransportURI are
     * sent, never a volume command.
     */
    override suspend fun clearSpeaker() {
        if (destroyed) return
        // A load still resolving or preparing is cancelled first, whatever the speaker holds: it must never reach the speaker
        // after the sender is gone. The clear does not wait for the resolver.
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
        val uri = soap.safeCall(AV_TRANSPORT, "GetMediaInfo", "InstanceID" to 0)?.get("CurrentURI").orEmpty()
        if (uri.isEmpty() || !isOurStream(uri)) {
            note("info", "clear skipped: the speaker is on another source")
            return
        }
        loadSeq.incrementAndGet() // cancels a pending load
        pending = null
        awaitUriCalls(uriInFlight.toList(), run) // a load that began its URI call after clearSpeaker took its snapshot
        expectStop = true
        pollLoop.stop()
        soap.safeCall(AV_TRANSPORT, "Stop", "InstanceID" to 0)
        val emptied = soap.ok(AV_TRANSPORT, "SetAVTransportURI", "InstanceID" to 0, "CurrentURI" to "", "CurrentURIMetaData" to "")
        forgetTrack()
        if (!emptied) {
            note("warn", "the speaker refused an empty transport URI")
            return
        }
        readBack()
    }

    /**
     * True only when one GetMediaInfo positively reads a URI that is not one of this app's own streams (an empty URI
     * counts as not ours when [emptyCounts]). A speaker whose URI cannot be read is not "on another source": the command goes
     * out as before (fail-open). Adds at most one read per command.
     */
    private suspend fun onAnotherSource(emptyCounts: Boolean): Boolean {
        val uri = readCurrentUri() ?: return false
        if (uri.isEmpty()) return emptyCounts
        return !isOurStream(uri)
    }

    /** Logs the one line for a phone command that was not sent because the speaker is on another source; the command reports failure. */
    private fun skipped(command: String): Boolean {
        note("info", "$command skipped: the speaker is on another source")
        return false
    }

    /**
     * True when [uri] is one of this app's own streams; the scheme is ignored so a speaker that rewrites it still matches.
     * Host, port and path prefix are matched from the start of the string, so a foreign URL that merely mentions
     * ours in a query string is not ours.
     */
    private fun isOurStream(uri: String): Boolean = isOwnStreamUri(uri, listOfNotNull(ip) + ownHosts, config.mediaPort)

    /**
     * After an accepted empty URI: reads what the speaker holds (one GetMediaInfo) and writes the outcome as one evidence line.
     * A speaker that still holds one of our streams gets the empty URI once more and is read once more; a second failure is
     * logged, not retried. A failed read never fails the clear. Stream URIs carry a token and are never logged, only whether
     * they were ours.
     */
    private suspend fun readBack() {
        val first = readCurrentUri()
        when {
            first == null -> note("info", "Cleared the speaker: read-back unavailable (the empty URI was accepted)")
            first.isEmpty() -> note("info", "Cleared the speaker: confirmed empty")
            !isOurStream(first) -> note(
                "info",
                "Cleared the speaker: confirmed empty of our stream (the speaker is on another source now, left alone)",
            )
            else -> {
                if (!soap.ok(AV_TRANSPORT, "SetAVTransportURI", "InstanceID" to 0, "CurrentURI" to "", "CurrentURIMetaData" to "")) {
                    note(
                        "warn",
                        "clear: still ours after retry - the speaker still holds one of our streams and refused the second empty URI",
                    )
                    return
                }
                val second = readCurrentUri()
                when {
                    second == null -> note("info", "Cleared the speaker: read-back unavailable (the empty URI was sent twice)")
                    second.isEmpty() || !isOurStream(second) -> note(
                        "info",
                        "Cleared the speaker: confirmed empty after a second empty URI",
                    )
                    else -> note("warn", "clear: still ours after retry - the speaker still holds one of our streams after two empty URIs")
                }
            }
        }
    }

    /** The URI the speaker holds ("" when empty), or null when it could not be read. */
    private suspend fun readCurrentUri(): String? =
        soap.safeCall(AV_TRANSPORT, "GetMediaInfo", "InstanceID" to 0)?.let { it["CurrentURI"].orEmpty() }

    /** The running slow-clear watch, if any; a new clear replaces it and [destroy] cancels it. */
    @Volatile private var slowClearWatch: Job? = null

    /**
     * A clear gave up waiting for a SetAVTransportURI the speaker had not answered, so the stream may still land after the
     * clear. For at most [SLOW_CLEAR_WATCH_MS] the speaker's URI is read every [SLOW_CLEAR_WATCH_INTERVAL_MS]; if it becomes one
     * of our own streams, Stop and the empty URI are sent and the watch ends. It ends at once when a new load or clear moved
     * [loadSeq], when the renderer is destroyed, and when the speaker is on another source (nothing is sent then).
     * Runs in the renderer's own scope after the clear returned; the clear never waits for it.
     */
    private fun startSlowClearWatch() {
        val seq = loadSeq.get()
        slowClearWatch?.cancel()
        note(
            "info",
            "clear watch started: reading the speaker every ${SLOW_CLEAR_WATCH_INTERVAL_MS} ms for up to ${SLOW_CLEAR_WATCH_MS / 1000} s",
        )
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
            soap.safeCall(AV_TRANSPORT, "Stop", "InstanceID" to 0)
            val emptied = soap.ok(AV_TRANSPORT, "SetAVTransportURI", "InstanceID" to 0, "CurrentURI" to "", "CurrentURIMetaData" to "")
            return if (emptied) "emptied a stream that landed after the clear"
            else "found a stream that landed after the clear, the speaker refused the empty URI"
        }
    }

    private fun forgetTrack() {
        resyncWatch = null
        releaseStream(current)
        prefetch.dropQueued()
        dropAdvanced()
        current = null
        nowPlaying = null
        position = null
        pending = null
        transport = "STOPPED"
        loadedThisSession = false
    }

    /**
     * Seeks the speaker. Four paths (the first is ours, the others as in renderer.js `doSeek`):
     *  * direct in automatic mode never gets a native Seek past the first second: the stream is reloaded in the next
     *    accepted format (adts, else mp3) starting at the position, because a speaker seeks a large fragmented MP4 by
     *    scanning it;
     *  * a format that is not seekable (adts, mp3) restarts the stream at the offset (the Play is deferred while
     *    paused, so a paused seek stays paused);
     *  * a seekable format (direct, mp4) gets a native AVTransport Seek. The target is relative to the file
     *    the speaker plays: a file that was built to start at an offset ([Current.startAt]) is sought at
     *    `position - startAt`;
     *  * when the speaker rejects the Seek, or the target lies before the start of an offset file, mp4 loads
     *    a new file that starts at the position (clamped to one second before the end, see [restartOffset]). `direct`
     *    has no such fallback: the seek is reported as failed.
     * Only Seek, Stop, SetAVTransportURI and Play reach the speaker; nothing touches the volume.
     * After a native Seek the position told to the sender is the target until the speaker leaves TRANSITIONING
     * (see [doGetPosition]); the reload and restart paths already return a correct start position.
     */
    override suspend fun doSeek(position: Double): Boolean {
        val c = current ?: return false
        val paused = status == PlayerStatus.PAUSED
        if (c.format == "direct" && startsMidTrack(position)) {
            // prepareItem swaps direct for a format that can start at the position
            return load(c.video, position, "direct", startPlaying = !paused, offsetOnly = c.offsetOnly)
        }
        if (FORMATS[c.format]?.seekable != true) {
            // Restart the stream from the new position. Takes a moment longer than a native seek (ffmpeg
            // has to reconnect), but works on every speaker.
            note(
                "info",
                "Seek to ${Math.floor(position).toInt()}s by restarting the ${c.format} stream" +
                    if (paused) ", Play deferred until resume" else "",
            )
            return load(c.video, position, c.format, startPlaying = !paused, offsetOnly = c.offsetOnly)
        }
        val base = c.startAt
        val before = position < base
        if (!before) {
            if (onAnotherSource(emptyCounts = true)) return skipped("seek")
            val target = position - base
            if (soap.ok(AV_TRANSPORT, "Seek", "InstanceID" to 0, "Unit" to "REL_TIME", "Target" to seekHms(target))) {
                // The speaker took the Seek but may still be seeking: hold the target until it settles.
                pending = PendingSeek(seekSeq.incrementAndGet(), position, deps.clock())
                c.wantedAt = position
                if (paused) c.seekedWhilePaused = true
                // A speaker that has not played yet may spend long seeking: the start timeout is measured from the last
                // command that can delay its first PLAYING. [Current.startedAt] still bounds the whole wait.
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
        return load(c.video, position, c.format, startPlaying = !paused, forceOffset = true, offsetOnly = c.offsetOnly)
    }

    /**
     * The only place that changes the speaker's volume: a sender asked for it. Nothing
     * in init, load, play, poll or stop sends SetVolume or SetMute.
     */
    override suspend fun doSetVolume(volume: Volume): Boolean {
        if (destroyed) return false
        val levelSet = soap.ok(
            RENDERING_CONTROL,
            "SetVolume",
            "InstanceID" to 0,
            "Channel" to "Master",
            "DesiredVolume" to volume.level,
        )
        // SetMute is optional in RenderingControl; do not fail the whole call if it is absent.
        soap.safeCall(RENDERING_CONTROL, "SetMute", "InstanceID" to 0, "Channel" to "Master", "DesiredMute" to if (volume.muted) 1 else 0)
        return levelSet
    }

    override suspend fun doGetVolume(): Volume {
        val level = soap.safeCall(RENDERING_CONTROL, "GetVolume", "InstanceID" to 0, "Channel" to "Master")
        val mute = soap.safeCall(RENDERING_CONTROL, "GetMute", "InstanceID" to 0, "Channel" to "Master")
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
        val state = soap.transportState()
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
        val r = soap.safeCall(AV_TRANSPORT, "GetPositionInfo", "InstanceID" to 0)
        // Right after a track change some speakers still report the previous track for a moment. If the
        // speaker names a different stream than ours, its numbers are not ours.
        if (r == null || isOnOtherStream(r["TrackURI"])) return null
        // The speaker counts from the start of the stream it got; add the offset our ffmpeg started at.
        return parseHmsOrNull(r["RelTime"])?.plus(offset)
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
        // While the hold lasted a PLAYING player was reported to the sender as buffering (Player.getState).
        val wasBuffering = status == PlayerStatus.PLAYING
        pending = null
        if (state == "STOPPED" || state == "NO_MEDIA_PRESENT") return // the stop branch of the poll decides what that means
        if (state !in SEEK_SETTLED_STATES)
            note("warn", "Speaker did not settle ${SEEK_SETTLE_DEADLINE_MS / 1000} s after the Seek - using its own position")
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
     * Drift check. The sender's seekbar counts on from the last position it was sent ([senderPosition]) and
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
        val r = soap.safeCall(AV_TRANSPORT, "GetPositionInfo", "InstanceID" to 0)
        return if (r != null && !isOnOtherStream(r["TrackURI"])) parseHms(r["TrackDuration"]) else 0.0
    }

    /** Does the speaker report a stream other than the one we are playing? */
    private fun isOnOtherStream(trackUri: String?): Boolean {
        val ours = current?.streamPath
        return !trackUri.isNullOrEmpty() && ours != null && HTTP_URL.containsMatchIn(trackUri) && !trackUri.contains(ours)
    }

    // ------------------------------------------------------------------------------------- internals

    // Polling instead of GENA eventing: many cheap speakers have broken event subscriptions, and eventing
    // needs an inbound callback port. One sequential loop, so a slow speaker never stacks polls.
    private val pollLoop = PollLoop(
        scope = scope,
        interval = { config.pollIntervalMs },
        mayRun = { !(destroyed || networkDown) },
        alive = { !destroyed },
        tick = { poll() },
        onError = { e -> note("debug", "Poll failed: ${e.message}") },
    )

    @Volatile private var polls = 0

    /** A lost connection pauses polling until the address is restored; playback state remains available for re-sync. */
    override suspend fun onNetworkLost() {
        if (destroyed) return
        networkDown = true
        resetJob?.cancel() // a re-sync under way belongs to an address that is gone again
        resyncWatch = null
        pollLoop.stop()
    }

    @Volatile private var resetJob: Job? = null

    /**
     * After a KEEP: the loaded track and the deadline of the watch on it. A mid-track stop that the poll reads
     * for this same track before the deadline is the speaker's buffer running dry, not a real stop, and is re-set once.
     */
    @Volatile private var resyncWatch: Pair<Current, Long>? = null

    /**
     * The phone is on [host] now. Streams are built with it from here on and the old address is remembered as ours. A
     * A renderer paused by [onNetworkLost] is re-synced and polled after the address returns. A direct address change also
     * uses [resyncAfterNetworkReturn].
     */
    override suspend fun onNetworkChanged(host: String) {
        if (destroyed) return
        val old = ip
        val fresh = config.advertiseIp ?: host
        val ipChanged = old != fresh
        ip = fresh
        ownHosts = (ownHosts.filter { it != fresh && it != old } + listOfNotNull(old?.takeIf { it != fresh })).takeLast(OWN_HOSTS_KEPT)
        networkDown = false
        resetJob?.cancel() // a newer change replaces an older re-sync that is still waiting or reading
        resetJob = scope.launch { resyncAfterNetworkReturn(ipChanged) }
    }

    /** A load that has started and not finished: it may still land with a URL on the old address. */
    private fun loadInFlight(): Boolean = loadsBeforeUri.get() > 0 || uriInFlight.isNotEmpty() ||
        (status == PlayerStatus.LOADING && queue.current?.id != current?.video?.id)

    /**
     * Follows what the speaker really does after the network came back. Waits for a load in
     * flight first (bounded by [NETWORK_RESET_WAIT_MS]), then reads the transport state and the TrackURI, at most
     * [RESYNC_READ_ATTEMPTS] times [Config.pollIntervalMs] apart, and acts on [resyncAction]:
     *  * KEEP: the speaker still plays our stream on the same address. Nothing is sent, polling resumes, and a watch of
     *    [RESYNC_WATCH_MS] re-sets a mid-track stop once (a speaker that only drains its buffer stops soon after).
     *  * RESET_AT_SPEAKER: the address changed and the speaker plays or is paused on our stream: re-set at its own position.
     *  * RESET_AT_LAST_POLLED: the speaker ran dry or cannot be read: re-set at the last polled position, never at its own
     *    0:00 and never an extrapolation over the outage.
     *  * RELEASE_FOREIGN: another source took the speaker: nothing is sent, the sender is told the player stopped.
     * A playing track is re-set playing, a paused one paused (a paused `direct` stream owes its Seek until the phone resumes).
     * Nothing here touches the volume.
     */
    private suspend fun resyncAfterNetworkReturn(ipChanged: Boolean) {
        if (resyncWaitedOutLoad()) return
        var attempt = 0
        while (true) {
            if (destroyed || networkDown) return
            val c = current
            val holds = c != null && status in RESET_STATUSES
            val read = readSpeakerForResync(c, holds) ?: return
            if (read.state == "PLAYING") {
                val handedOff = queuedTrackFor(read.reading?.get("TrackURI"))?.let { queuedTrack ->
                    followQueuedTrack(queuedTrack, "rebind")
                } == true
                if (handedOff) return
            }
            attempt++
            if (!actOnResync(resyncAction(holds, ipChanged, read.state, read.kind, attempt), c, read)) return
        }
    }

    /** What the speaker answered to a re-sync read: the transport state, the GetPositionInfo reading and what its TrackURI is. */
    private class ResyncReading(val state: String?, val reading: Map<String, String>?, val kind: UriKind)

    /**
     * Waits for a load in flight (bounded by [NETWORK_RESET_WAIT_MS]). True when the re-sync is over: destroyed, or the load
     * never finished.
     */
    private suspend fun resyncWaitedOutLoad(): Boolean {
        val deadline = deps.clock() + NETWORK_RESET_WAIT_MS
        while (!destroyed && loadInFlight() && deps.clock() < deadline) delay(NETWORK_RESET_STEP_MS)
        if (destroyed) return true
        if (loadInFlight()) {
            note("warn", "A load was still under way ${NETWORK_RESET_WAIT_MS / 1000}s after the address change - the track is not re-set")
            if (current != null) pollLoop.start()
            return true
        }
        return false
    }

    /** Reads the speaker when [holds]; null when the player changed while it was read (the re-sync is over). */
    private suspend fun readSpeakerForResync(c: Current?, holds: Boolean): ResyncReading? {
        var state: String? = null
        var reading: Map<String, String>? = null
        var kind = UriKind.UNREAD
        if (holds) {
            state = soap.transportState()
            if (state != null) {
                reading = soap.safeCall(AV_TRANSPORT, "GetPositionInfo", "InstanceID" to 0)
                val trackUri = reading?.get("TrackURI")
                kind = when {
                    reading == null -> UriKind.UNREAD
                    trackUri.isNullOrEmpty() -> UriKind.EMPTY
                    isOurStream(trackUri) -> UriKind.OURS
                    else -> UriKind.FOREIGN
                }
            }
            // the player may have been stopped, or another track loaded, while the speaker was read
            if (destroyed || networkDown || current !== c) return null
        }
        return ResyncReading(state, reading, kind)
    }

    /** Carries out the decided [action]. True when the re-sync reads the speaker again (RETRY), false when it is over. */
    private suspend fun actOnResync(action: ResyncAction, c: Current?, read: ResyncReading): Boolean {
        when (action) {
            ResyncAction.NOTHING -> {
                if (current != null) pollLoop.start()
            }
            ResyncAction.RETRY -> {
                delay(config.pollIntervalMs)
                return true
            }
            ResyncAction.KEEP -> {
                val keep = c ?: return false
                note(
                    "info",
                    "Kept \"${nowPlaying?.title ?: keep.video.id}\" after the network returned: the speaker still plays it",
                    "videoId" to keep.video.id,
                    "state" to read.state,
                )
                resyncWatch = keep to (deps.clock() + RESYNC_WATCH_MS)
                pollLoop.start()
            }
            ResyncAction.RELEASE_FOREIGN -> releaseTakenOverSpeaker(read.reading?.get("TrackURI"))
            ResyncAction.RESET_AT_SPEAKER -> {
                val keep = c ?: return false
                val at = read.reading?.get("RelTime")?.let { parseHms(it) + keep.startAt } ?: position?.rel ?: keep.startAt.toDouble()
                resetLoaded(keep, at, "speaker")
            }
            ResyncAction.RESET_AT_LAST_POLLED -> {
                val keep = c ?: return false
                resetLoaded(keep, position?.rel ?: keep.startAt.toDouble(), "last-polled")
            }
        }
        return false
    }

    /**
     * Sends the loaded track [c] to the speaker again from the current address at [from] seconds. [why] says where the
     * position came from. The position is clamped inside the track; a playing track is re-set playing, a paused one paused.
     */
    private suspend fun resetLoaded(c: Current, from: Double, why: String) {
        resyncWatch = null
        val duration = nowPlaying?.duration ?: 0.0
        var pos = from
        if (duration > 1) pos = minOf(pos, duration - 1)
        pos = maxOf(0.0, pos)
        // the player may have been stopped, or another track loaded, since the position was decided
        val now = status
        if (destroyed || current !== c || now !in RESET_STATUSES) return
        val paused = now == PlayerStatus.PAUSED
        val started = load(
            c.video, pos, c.format, startPlaying = !paused, forceOffset = paused && c.format in FILE_FORMATS, offsetOnly = c.offsetOnly,
        )
        if (!started) {
            note("warn", "Re-setting \"${c.video.id}\" after the address change failed")
            if (!destroyed && current != null) pollLoop.start() // the load stopped the polls; the mirror needs them
            return
        }
        // A stream that starts at 0 (direct) cannot be sought while paused: the speaker has no Play yet, so the Seek is owed.
        val fresh = current
        if (paused && fresh != null && fresh !== c && fresh.startAt == 0 && pos > 1 && FORMATS[fresh.format]?.seekable == true) {
            fresh.owedSeek = pos
        }
        val title = nowPlaying?.title ?: c.video.id
        note(
            "info",
            "Re-set \"$title\" after the address change at ${Math.floor(pos).toInt()}s",
            "videoId" to c.video.id,
            "format" to c.format,
            "paused" to paused,
            "why" to why,
        )
    }

    private suspend fun poll() {
        if (expectStop) return
        polls++
        val epoch = commandEpoch.get()
        val seekSeqAtStart = seekSeq.get()
        val state = soap.transportState()
        if (state == null || destroyed) return
        transport = state
        val read = readPollPosition(state)
        if (destroyed) return
        // A Pause or Play of ours finished while this poll was reading: what it read is from before that command.
        if (epoch != commandEpoch.get()) return

        if (countForeignPoll(read.foreign) && current != null) {
            releaseTakenOverSpeaker(read.foreignUri)
            return
        }

        settlePendingSeek(state, read.polledRaw, seekSeqAtStart)
        // A poll that began before a Seek was sent read the speaker from before it: its position proves nothing.
        checkDrift(state, if (seekSeqAtStart == seekSeq.get()) read.polledRaw else null)

        val c = current
        if (startFailedByTimeout(state, c)) return

        when {
            state == "PLAYING" -> if (pollPlaying(c)) return
            state == "PAUSED_PLAYBACK" -> {
                if (status == PlayerStatus.PLAYING) notifyExternalStateChange(PlayerStatus.PAUSED)
            }
            (state == "STOPPED" || state == "NO_MEDIA_PRESENT") && sawPlaying && status == PlayerStatus.PLAYING ->
                pollStopped(c, epoch)
        }
    }

    /**
     * What one poll read from GetPositionInfo. [polledRaw] is the speaker's position for the pending-Seek rule (null when not
     * read or not our stream). [foreign] is true when it read a non-empty URI that is not ours while we hold a track
     * (no extra SOAP call); [foreignUri] is that URI.
     */
    private class PollRead(val polledRaw: Double?, val foreign: Boolean, val foreignUri: String?)

    /** Reads the speaker's position while it plays or is paused, and stores it as the poll position. */
    private suspend fun readPollPosition(state: String): PollRead {
        var polledRaw: Double? = null
        var foreign = false
        var foreignUri: String? = null
        if (state == "PLAYING" || state == "PAUSED_PLAYBACK") {
            val r = soap.safeCall(AV_TRANSPORT, "GetPositionInfo", "InstanceID" to 0)
            if (r != null) {
                val uri = r["TrackURI"]
                val startAt = current?.startAt ?: 0
                if (current != null && !uri.isNullOrEmpty() && !isOurStream(uri)) {
                    foreign = true
                    foreignUri = uri
                }
                // Do not take position or duration from a speaker still on the previous track, or on another source.
                val stale = isOnOtherStream(uri) || foreign
                if (!stale) polledRaw = parseHmsOrNull(r["RelTime"])?.plus(startAt)
                position = PollPosition(
                    rel = polledRaw ?: position?.rel ?: startAt.toDouble(),
                    dur = nowPlaying?.duration?.takeIf { it != 0.0 } ?: if (stale) 0.0 else parseHms(r["TrackDuration"]),
                    uri = uri,
                    at = deps.clock(),
                    reported = if (stale) position?.reported ?: true else polledRaw != null,
                )
                if (!stale && !metaReported) {
                    metaReported = true
                    reportSpeakerMetadata(r["TrackMetaData"])
                }
            }
        }
        return PollRead(polledRaw, foreign, foreignUri)
    }

    /**
     * If the speaker has not reached PLAYING well after Play, or the media server already failed to produce the stream
     * (a 5xx on its request), assume it cannot handle the stream. True when it gave up on [c].
     */
    private suspend fun startFailedByTimeout(state: String, c: Current?): Boolean {
        if (!sawPlaying && state != "PLAYING" && c != null && status == PlayerStatus.PLAYING &&
            (c.streamFailed || startTimedOut(c, deps.clock()))
        ) {
            handleStartFailure(c)
            return true
        }
        return false
    }

    /** The speaker reads PLAYING. True when the poll is over (it followed the queued track). */
    private suspend fun pollPlaying(c: Current?): Boolean {
        stopGracePolls = 0
        if (!sawPlaying) {
            sawPlaying = true
            if (c != null) confirmFormat(c)
        }
        if (c != null) c.seekedWhilePaused = false // the speaker plays: nothing is owed to a Play after a paused Seek
        if (c != null && c === current) sendOwedSeek(c)
        // Play pressed in the Sonos app for a stream loaded while paused: stop answering startAt only.
        if (c != null && !c.playSent && !isOnOtherStream(position?.uri)) c.playSent = true
        if (status == PlayerStatus.PAUSED) notifyExternalStateChange(PlayerStatus.PLAYING)
        // The speaker played on into the track it was given as next: follow it.
        val via = rendererAdvancedVia()
        val queuedTrack = prefetch.queued
        if (via != null && queuedTrack != null && followQueuedTrack(queuedTrack, via)) {
            return true
        }
        // Last, and it only launches: the poll must not wait for a prepare.
        if (c != null) prefetch.maintainNext()
        return false
    }

    /**
     * The speaker reads STOPPED while we were playing: wait out a gapless switch, advance the queue, re-set a dry buffer, or
     * mirror the stop.
     */
    private suspend fun pollStopped(c: Current?, epoch: Int) {
        val p = position
        val ended = trackEnded(p, c?.startAt ?: 0)
        val accepted = prefetch.queued?.takeIf { it.sentToRenderer }
        if (ended && accepted != null) {
            // The speaker took the queued track as its next one but did not play on into it.
            // A Sonos may report STOPPED for a moment while it switches: while it already holds the queued stream
            // the poll waits a few polls instead of reloading it (a reload would start the track twice).
            if (stopGracePolls < GAPLESS_STOP_GRACE_POLLS && speakerHolds(accepted)) {
                if (destroyed || epoch != commandEpoch.get()) return
                if (stopGracePolls == 0) note("info", "Speaker stopped at the switch - waiting for it to play the queued track")
                stopGracePolls++
                return
            }
            if (destroyed || epoch != commandEpoch.get()) return
            // It stopped instead of switching, or held the stream and never played it: what it was believed to do is wrong.
            stopGracePolls = 0
            prefetch.gaplessSupported = null
            store?.set(LEARNED_GAPLESS_KEY, null)
            note("info", "Speaker stopped at the end instead of playing the queued track - gapless support forgotten")
        }
        // The speaker stopped. Both the launched coroutines below run outside this one, because they
        // stop and restart polling, which would cancel the poll in the middle of the call.
        pollLoop.stop()
        if (ended) {
            note("info", "Track finished on renderer - advancing queue")
            advanceAfterTrackEnd()
        } else if (c != null && resyncWatch.let { it != null && it.first === c && deps.clock() < it.second }) {
            // The speaker was kept after the network returned and now stops mid-track: its buffer ran dry.
            resyncWatch = null
            note("info", "The speaker stopped after the network returned - re-setting at the last polled position")
            val at = p?.rel ?: c.startAt.toDouble()
            resetJob = scope.launch { resetLoaded(c, at, "stopped-after-keep") }
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

    /**
     * Debounce for [releaseTakenOverSpeaker]: counts a poll that read another source's URI, resets on any other reading (ours,
     * empty, nothing). Left alone while a stop, a load or a clear is in progress (a load's own SetAVTransportURI changes what the
     * speaker holds). True when the second consecutive foreign poll has just been read.
     */
    private fun countForeignPoll(foreign: Boolean): Boolean {
        if (expectStop || destroyed || loadsBeforeUri.get() > 0 || uriInFlight.isNotEmpty()) return false
        if (!foreign) {
            foreignPolls = 0
            return false
        }
        foreignPolls++
        return foreignPolls >= FOREIGN_POLLS_FOR_TAKEOVER
    }

    /**
     * Another source (Sonos radio, a line-in, another app) took the speaker over while a track of ours was loaded.
     * Our side is released without sending anything to the speaker: polling stops, the track is forgotten as
     * a Stop would forget it, and the sender is told the player stopped and the cast ends (the senders are dropped). This
     * is not a track end, so the queue does not advance. Only the URI's scheme is written to the log.
     */
    private fun releaseTakenOverSpeaker(uri: String?) {
        pollLoop.stop()
        foreignPolls = 0
        forgetTrack()
        val scheme = uri?.substringBefore(':', "")
            ?.filter { it.isLetterOrDigit() || it == '-' || it == '+' || it == '.' }
            ?.take(24)
            .orEmpty()
        note("info", "the speaker was taken over by another source - released", "scheme" to scheme)
        // Launched, because the poll that got here runs in the job pollLoop.stop() just cancelled. A playback failure, not a
        // plain state change: the sender is told STOPPED and the receiver also ends the cast (it drops the senders, so the
        // sender's app leaves the cast instead of sitting connected and idle). The player is already STOPPED then, so the
        // reset that follows sends nothing, and the speaker clear leaves another source's URI alone.
        scope.launch { reportPlaybackFailure("the speaker was taken over by another source") }
    }

    /**
     * Has the wait for the speaker's first PLAYING run out? [Current.loadedAt] is the last command that can delay it (the
     * load, a Play, a Seek), so the start timeout counts from the latest of them. A native Seek the speaker accepted and
     * has not finished (a pending Seek, at most [SEEK_SETTLE_DEADLINE_MS]) also holds the verdict back: a speaker that is
     * busy seeking is not a speaker that cannot play the format (Lamp, 13 s near the end of a file, 2026-10-21 22:01).
     * [Current.startedAt] never moves and caps the whole wait at [START_HARD_CAP_FACTOR] times the start timeout, so a
     * dead speaker that keeps taking Seeks is still given up on.
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
        if (soap.ok(AV_TRANSPORT, "Seek", "InstanceID" to 0, "Unit" to "REL_TIME", "Target" to seekHms(owed - c.startAt))) {
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
        p == null || !p.reported || p.dur <= 1 || p.rel - startAt < 1 || p.rel >= p.dur - TRACK_END_TOLERANCE_S

    /**
     * Moves the queue on after a track end. A playlist refresh in flight makes [next] a no-op, so the move waits
     * for it, bounded by [Config.queueSettleTimeoutMs] on the injected clock; a newer play, a stop or removal
     * that overtakes the wait ends it. When nothing could be advanced the player ends STOPPED, so the senders
     * and the notification do not show a playing track that has silently stopped.
     */
    private fun advanceAfterTrackEnd() {
        val seq = loadSeq.get()
        scope.launch {
            val deadline = deps.clock() + config.queueSettleTimeoutMs
            while (queue.isUpdating && !superseded(seq) && deps.clock() < deadline) delay(QUEUE_SETTLE_STEP_MS)
            if (superseded(seq)) return@launch
            if (queue.isUpdating)
                note("warn", "Playlist refresh still in flight after ${config.queueSettleTimeoutMs} ms - advancing anyway")
            val advanced = next()
            if (!advanced && !superseded(seq) && status == PlayerStatus.PLAYING) notifyExternalStateChange(PlayerStatus.STOPPED)
        }
    }

    /**
     * Has the speaker moved on to the track it was given with SetNextAVTransportURI? Returns the field that showed it ("TrackURI",
     * or "GetMediaInfo.CurrentURI" when the position info has none), else null. The speaker's URI is compared with
     * `contains(streamPath)`, never equality: speakers rewrite host or escaping. The URI itself is never returned.
     */
    private suspend fun rendererAdvancedVia(): String? {
        val q = prefetch.queued
        val path = q?.item?.current?.streamPath
        if (q == null || !q.sentToRenderer || path == null) return null
        val fromPosition = position?.uri
        if (!fromPosition.isNullOrEmpty()) return if (fromPosition.contains(path)) "TrackURI" else null
        val fromMediaInfo = readCurrentUri()
        return if (fromMediaInfo != null && fromMediaInfo.contains(path)) "GetMediaInfo.CurrentURI" else null
    }

    private fun queuedTrackFor(uri: String?): Queued? {
        if (uri.isNullOrEmpty()) return null
        val candidate = prefetch.queued ?: return null
        val path = candidate.item.current.streamPath ?: return null
        return candidate.takeIf { it.sentToRenderer && uri.contains(path) }
    }

    /**
     * The speaker may advance while polling is paused for a network outage. Adopt that accepted next stream through the same
     * queue hand-off as a polled transition instead of reloading the previous track during rebind.
     */
    private fun followQueuedTrack(candidate: Queued, via: String): Boolean {
        val taken = prefetch.takeIfQueued(candidate) ?: return false
        advanced = taken
        pollLoop.stop()
        note("info", "Renderer continued into the next track", "videoId" to taken.video.id, "via" to via)
        handOffAfterGaplessSwitch(loadSeq.get())
        return true
    }

    /** Does the speaker hold [q]'s stream as its current one (GetMediaInfo read once)? Compared with `contains`, never equality. */
    private suspend fun speakerHolds(q: Queued): Boolean {
        val path = q.item.current.streamPath ?: return false
        return readCurrentUri()?.contains(path) == true
    }

    /**
     * Moves the lounge queue on after the speaker played into the queued track by itself. Runs in its own coroutine because the
     * poll that found the switch is cancelled by [PollLoop.stop]. Waits (bounded by [Config.queueSettleTimeoutMs]) for a playlist
     * refresh in flight, as [advanceAfterTrackEnd] does, then [next] reaches [doPlay], which adopts [advanced]; the Stop that
     * `play()` sends first is skipped in [doStop] for this call only.
     */
    private fun handOffAfterGaplessSwitch(seq: Int) {
        scope.launch {
            val deadline = deps.clock() + config.queueSettleTimeoutMs
            while (queue.isUpdating && !superseded(seq) && deps.clock() < deadline) delay(QUEUE_SETTLE_STEP_MS)
            if (superseded(seq)) {
                // A Stop, a newer play or a removal overtook the wait: the track the speaker moved on to is not ours to adopt.
                dropAdvanced()
                return@launch
            }
            if (queue.isUpdating)
                note("warn", "Playlist refresh still in flight after ${config.queueSettleTimeoutMs} ms - advancing anyway")
            handingOff = true
            val moved = try {
                next()
            } finally {
                handingOff = false
            }
            if (moved) return@launch
            dropAdvanced()
            if (destroyed) return@launch
            when (status) {
                // Queue over (the stop inside next() skipped the speaker): stop the track it played on into.
                PlayerStatus.STOPPED -> {
                    soap.ok(AV_TRANSPORT, "Stop", "InstanceID" to 0)
                    note("info", "Queue over after a gapless switch - stopped the speaker")
                }
                // next() could not run (a refresh that never finished): the speaker plays something the phone does not know about.
                PlayerStatus.PLAYING -> {
                    note("warn", "Could not advance the queue after a gapless switch - stopping the speaker")
                    stop()
                }
                else -> Unit
            }
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
     * A normal start proves the format works for future tracks. An offset stream may have been chosen
     * only to seek, so it must not replace the speaker's ordinary format preference.
     */
    private suspend fun confirmFormat(c: Current) = formats.confirm(c)

    /**
     * The speaker accepted the URL but never started playing (a refused upstream host, a format it cannot
     * decode and a firewall all look the same from here). In auto mode the format chain moves on: forget
     * the remembered format, then reload the same track at the same position in the next format the speaker
     * accepts; the 12 s clock of that attempt starts at its own Play. With a forced format, or at the end of
     * the chain, stop and say why.
     */
    private suspend fun handleStartFailure(c: Current) {
        val next = formats.nextAfter(c.format, c.sourceMime)
        formats.forgetUnproven(c)
        if (next != null) {
            note("warn", "Renderer did not start ${c.format} - trying $next")
            pollLoop.stop()
            // Where the speaker was last polled playing, if that is newer than the last load or Seek and off the start;
            // otherwise where this stream was meant to play from (the load position or the last accepted Seek).
            val polled = position?.takeIf { it.at >= c.loadedAt && it.rel - c.startAt >= 1 }?.rel
            val resumeAt = polled ?: maxOf(c.wantedAt, c.startAt.toDouble())
            val before = loadSeq.get()
            scope.launch {
                val started = load(c.video, resumeAt, next, startPlaying = true, offsetOnly = c.offsetOnly)
                // Only a failure of this reload counts; a newer play, a stop or removal has moved loadSeq on.
                if (!started && !destroyed && loadSeq.get() == before + 1) reportPlaybackFailure("no format could be loaded on the speaker")
            }
            return
        }
        val why = if (c.fetched) "fetched the stream but never started playing" else "never requested the stream"
        lastError = "Renderer $why"
        note(
            "error",
            lastError + if (c.fetched) "." else ". Check that it can reach http://$ip:${config.mediaPort} (firewall, advertise address).",
        )
        pollLoop.stop()
        // Nothing is left to try: the cast ends visibly. A Stop, a newer play or removal in between wins.
        val seq = loadSeq.get()
        scope.launch {
            if (!superseded(seq)) reportPlaybackFailure("the speaker never started playing")
        }
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
