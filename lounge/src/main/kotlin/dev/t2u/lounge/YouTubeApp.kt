package dev.t2u.lounge

// Port of yt-cast-receiver 2.1.0 lib/app/YouTubeApp.js (MIT, Copyright (c) Patrick Kan).
// The lounge app: two sessions (YouTube `cl`, YouTube Music `m`), sender messages turned into player calls, player
// state turned into messages to the sender, sender tracking, session switching, mute and autoplay policy.
//
// Protocol-risk constraint: message names and payload keys the sender uses are handled in this one file.

import dev.t2u.upnp.EventSink
import dev.t2u.upnp.NoEvents
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import java.net.URLDecoder
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

private val SAFE_PID = Regex("^[A-Za-z0-9-]{1,64}$")
internal const val NETWORK_SENDER_RECOVERY_MS = 30_000L
private const val VOLUME_SEND_KEY = "onVolumeChanged"

/**
 * One cast target's lounge app. [player] is what the sender controls; [scope] runs the sessions and the fire-and-forget
 * sends. Incoming messages are handled one batch at a time; replies to the sender never block the player.
 */
class YouTubeApp(
    private val player: Player,
    private val options: AppOptions,
    http: OkHttpClient,
    endpoints: LoungeEndpoints,
    private val sink: EventSink,
    private val scope: CoroutineScope,
    private val monotonicMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    @Volatile
    var state: AppState = AppState.STOPPED
        private set

    /** The DIAL app pid (stored, or generated at [start]). */
    @Volatile
    var pid: String? = null
        private set

    val connectedSenders: List<Sender> get() = connected.toList()

    /** The client whose session the senders are connected through, or null before the first connect. */
    val activeClient: Client? get() = activeSession?.client

    var onSenderConnect: (Sender) -> Unit = {}

    /** The second argument is true when the sender vanished without saying goodbye. */
    var onSenderDisconnect: (Sender, Boolean) -> Unit = { _, _ -> }

    /** Called when the app stopped because a session failed for good. */
    var onTerminate: (Throwable) -> Unit = {}

    private val sessions: Map<Client, Session> = Client.entries.associateWith { client ->
        Session(client, options.screenName, options.brand, options.model, options.dataStore, http, endpoints, sink, scope)
    }

    @Volatile
    private var activeSession: Session? = null

    /** The previous/next availability last sent to the senders; null when nothing was sent since a start or a session switch. */
    @Volatile
    private var lastNav: NavInfo? = null

    private val connected = CopyOnWriteArrayList<Sender>()
    private val implicitlyDisconnected = CopyOnWriteArrayList<Sender>()
    private val networkRecoveryLock = Any()
    private var networkDown = false
    private var networkRecoveryUntil = 0L
    private var networkSenders = emptySet<String>()

    @Volatile
    private var autoplayOnConnect: AutoplayMode = AutoplayMode.ENABLED

    @Volatile
    private var resetPolicy: ResetPlayerOnDisconnectPolicy = options.resetPlayerOnDisconnect

    private var autoplayBeforeUnsupported: AutoplayMode? = null

    /** One message batch at a time, whichever session it came from. */
    private val messageLock = Mutex()

    /** Guards the choice of the active session; a DIAL launch takes only this one. */
    private val switchLock = Mutex()

    private val stateListener = object : PlayerStateListener {
        override suspend fun onState(event: PlayerStateEvent) {
            handlePlayerStateEvent(event)
        }
    }

    private val failureListener = object : PlaybackFailureListener {
        override suspend fun onPlaybackFailure(reason: String) {
            handlePlaybackFailure(reason)
        }
    }

    init {
        if (player.events === NoEvents) player.events = sink
    }

    fun enableAutoplayOnConnect(value: Boolean) {
        autoplayOnConnect = if (value) AutoplayMode.ENABLED else AutoplayMode.DISABLED
    }

    fun setResetPlayerOnDisconnectPolicy(value: ResetPlayerOnDisconnectPolicy) {
        resetPolicy = value
    }

    /** Remember existing senders before lounge reports their network-related disappearance. */
    fun onNetworkLost() {
        synchronized(networkRecoveryLock) {
            if (!networkDown) {
                networkDown = true
                networkSenders = connected.map { it.id }.toSet()
            }
        }
    }

    fun onNetworkReturned() {
        synchronized(networkRecoveryLock) {
            if (networkDown) {
                networkDown = false
                networkRecoveryUntil = monotonicMs() + NETWORK_SENDER_RECOVERY_MS
            }
        }
    }

    private fun networkInterrupted(sender: Sender): Boolean = synchronized(networkRecoveryLock) {
        sender.id in networkSenders && (networkDown || monotonicMs() < networkRecoveryUntil)
    }

    private fun forgetNetworkSender(id: String) {
        synchronized(networkRecoveryLock) { networkSenders = networkSenders - id }
    }

    /** Restores or creates the pid, then begins both sessions at once. On failure both are ended and the error is rethrown. */
    suspend fun start() {
        if (state != AppState.STOPPED) return
        loadPid()
        note("starting")
        state = AppState.STARTING
        player.addStateListener(stateListener)
        player.addFailureListener(failureListener)
        val failures = coroutineScope {
            sessions.values.map { session ->
                session.onMessages = { batch, from -> handleBatch(batch, from) }
                session.onTerminate = { error -> terminate(error) }
                async {
                    try {
                        session.begin()
                        null
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        e
                    }
                }
            }.awaitAll()
        }
        val failure = failures.firstOrNull { it != null }
        if (failure != null) {
            player.removeStateListener(stateListener)
            player.removeFailureListener(failureListener)
            withContext(NonCancellable) {
                for (session in sessions.values) {
                    session.onMessages = { _, _ -> }
                    session.onTerminate = {}
                    try {
                        session.end()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // already failing
                    }
                }
            }
            state = AppState.STOPPED
            throw AppException("Failed to start YouTubeApp", failure)
        }
        state = AppState.RUNNING
    }

    /**
     * The sender's DIAL launch (`pairingCode=...&theme=cl|m`): switches to that theme's session, registers the pairing
     * code with the lounge and returns the pid for the LOCATION header. Never logs the code.
     */
    suspend fun launch(data: String): String {
        val form = parseForm(data)
        val code = form["pairingCode"]
        val theme = form["theme"]
        val client = Client.fromTheme(theme)
        note("launch", "theme" to theme?.take(16), "hasPairingCode" to !code.isNullOrEmpty())
        if (code.isNullOrEmpty()) throw IllegalArgumentException("Invalid launch data: pairingCode is missing")
        if (client == null) throw IllegalArgumentException("Invalid launch data: unknown theme")
        check(state == AppState.RUNNING) { "YouTubeApp is not running" }
        val session = sessions.getValue(client)
        checkAndSwitchActiveSession(session)
        note("connectingSender", "client" to client.displayName)
        session.registerPairingCode(code)
        return checkNotNull(pid) { "no pid" }
    }

    /**
     * Resets the player, ends both sessions (the senders are told) and reports the senders as disconnected.
     * With [error] the app stopped because of it and [onTerminate] is called.
     */
    suspend fun stop(error: Throwable? = null) {
        if (state != AppState.RUNNING && error == null) return
        note("stopping")
        state = AppState.STOPPING
        val senders = connected.toList()
        connected.clear()
        synchronized(networkRecoveryLock) {
            networkDown = false
            networkSenders = emptySet()
            networkRecoveryUntil = 0L
        }
        implicitlyDisconnected.clear()
        player.removeStateListener(stateListener)
        player.removeFailureListener(failureListener)
        lastNav = null
        try {
            player.reset()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            problem("reset", e)
        }
        for (session in sessions.values) {
            session.onMessages = { _, _ -> }
            session.onTerminate = {}
        }
        coroutineScope {
            sessions.values.map { session ->
                async {
                    try {
                        session.end()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        problem("end", e)
                    }
                }
            }.awaitAll()
        }
        activeSession = null
        state = AppState.STOPPED
        for (sender in senders) safely { onSenderDisconnect(sender, false) }
        if (error != null) safely { onTerminate(error) }
    }

    private fun terminate(error: Throwable) {
        // a separate coroutine: the session that failed is still unwinding
        scope.launch {
            try {
                stop(error)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                problem("terminate", e)
            }
        }
    }

    private suspend fun loadPid() {
        val store = options.dataStore
        if (store == null) {
            pid = UUID.randomUUID().toString()
            return
        }
        val stored = (store.get(StoreKeys.APP_PID) as? JsonPrimitive)?.contentOrNull
        if (stored != null && SAFE_PID.matches(stored)) {
            note("pidRestored")
            pid = stored
        } else {
            val fresh = UUID.randomUUID().toString()
            note("pidCreated")
            store.set(StoreKeys.APP_PID, JsonPrimitive(fresh))
            pid = fresh
        }
    }

    // ---- active session

    private suspend fun checkAndSwitchActiveSession(target: Session) {
        switchLock.withLock {
            val active = activeSession
            if (active?.client == target.client) return
            if (active == null) {
                activeSession = target
            } else {
                active.cancelDeferredSend(VOLUME_SEND_KEY)
                // Only one client can be connected at a time: the senders of the old session are dropped.
                note("switching", "from" to active.client.displayName, "to" to target.client.displayName)
                // The new session starts playing at once, so the speaker is reset but not cleared.
                player.reset(clear = false)
                lastNav = null
                val dropped = connected.toList()
                connected.clear()
                implicitlyDisconnected.clear()
                synchronized(networkRecoveryLock) { networkSenders = emptySet() }
                activeSession = target
                post(active, listOf(OutgoingMessage.loungeScreenDisconnected()))
                // yt-cast-receiver clears the list before it walks it, so it never reports these; here they are reported
                for (sender in dropped) safely { onSenderDisconnect(sender, false) }
            }
            note("activeSession", "client" to target.client.displayName)
        }
    }

    /**
     * The speaker cannot play what the sender cast (the player already told the senders state 4): tells the sender the
     * screen is gone, drops the senders and resets the player, which also clears the speaker. The reset policy is
     * not consulted: no sender of this receiver can be served by this speaker now. Protocol-risk: every sender-facing
     * message name stays in this file.
     */
    private suspend fun handlePlaybackFailure(reason: String) {
        switchLock.withLock {
            val session = activeSession
            if (state != AppState.RUNNING || session == null) return
            if (connected.isEmpty()) {
                note("playbackFailed", "reason" to reason, "senders" to 0)
                return
            }
            val dropped = connected.toList()
            note("playbackFailed", "reason" to reason, "senders" to dropped.size)
            connected.clear()
            implicitlyDisconnected.clear()
            lastNav = null
            // Before the reset: the speaker clear can take its whole budget, the phone is told at once.
            post(session, listOf(OutgoingMessage.loungeScreenDisconnected()))
            try {
                player.reset()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                problem("reset", e)
            }
            for (sender in dropped) safely { onSenderDisconnect(sender, false) }
        }
    }

    // ---- autoplay and mute

    private suspend fun setAutoplayMode(aid: Int?, value: AutoplayMode) {
        val session = activeSession ?: return
        val before = player.queue.getState()
        player.queue.setAutoplayMode(value)
        val after = player.queue.getState()
        val messages = arrayListOf(OutgoingMessage.onAutoplayModeChanged(aid, value))
        if (before.autoplay?.id != after.autoplay?.id) messages.add(OutgoingMessage.autoplayUpNext(aid, after.autoplay?.id))
        post(session, messages)
    }

    private suspend fun setAutoplayModeBySenderCapabilities(aid: Int?) {
        val senders = connected.toList()
        if (senders.isEmpty()) return
        val mode: AutoplayMode
        if (!senders.all { it.supportsAutoplay() }) {
            note("autoplay", "support" to "some senders do not support autoplay")
            if (player.autoplayMode != AutoplayMode.UNSUPPORTED) autoplayBeforeUnsupported = player.autoplayMode
            mode = AutoplayMode.UNSUPPORTED
        } else {
            mode = if (player.autoplayMode != AutoplayMode.UNSUPPORTED) {
                player.autoplayMode
            } else {
                autoplayBeforeUnsupported ?: autoplayOnConnect
            }
            note("autoplay", "support" to "all senders support autoplay", "mode" to mode.name)
            autoplayBeforeUnsupported = null
        }
        setAutoplayMode(aid, mode)
    }

    private suspend fun enforceMutePolicy(aid: Int?) {
        val senders = connected.toList()
        if (senders.isEmpty()) return
        val zeroOnMute = when (options.mutePolicy) {
            MutePolicy.ZERO_VOLUME_LEVEL -> true
            MutePolicy.PRESERVE_VOLUME_LEVEL -> false
            MutePolicy.AUTO -> !senders.all { it.supportsMute() }
        }
        note("mutePolicy", "policy" to options.mutePolicy.wire, "zeroVolumeLevelOnMute" to zeroOnMute)
        player.setZeroVolumeLevelOnMute(zeroOnMute, aid)
    }

    // ---- incoming messages

    private suspend fun handleBatch(batch: List<IncomingMessage>, session: Session) {
        messageLock.withLock {
            for (message in batch) {
                try {
                    handleIncomingMessage(message, session)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // one bad message must not cost the rest of its batch
                    problem("message:${message.name.take(40)}", e)
                }
            }
        }
    }

    /**
     * Hands a message to the function of its family and posts the replies it returns, once. A family that has nothing
     * to answer returns no replies; a message ignored on an inactive session answers nothing.
     */
    private suspend fun handleIncomingMessage(message: IncomingMessage, session: Session) {
        val aid = message.aid
        val payload = message.payload as? JsonObject
        val isActive = session === activeSession
        val client = session.client
        val replies = when (message.name) {
            "getNowPlaying", "loungeStatus" -> handleStatusMessage(message.name, aid, payload, session, isActive)
            "setPlaylist", "updatePlaylist" -> handlePlaylistRequest(message.name, aid, payload, session, isActive, client)
            "next", "previous", "pause", "stopVideo", "play" -> handleTransportMessage(message.name, aid, isActive)
            "seekTo" -> handleSeekMessage(message.name, payload, isActive)
            "getVolume" -> handleGetVolume(aid, session)
            "setVolume" -> handleSetVolume(message.name, aid, payload, session, isActive)
            "setAutoplayMode" -> handleAutoplayMessage(message.name, aid, payload, isActive)
            else -> unhandledMessage(message.name, aid, client)
        }
        if (replies.isNotEmpty()) post(session, replies)
    }

    private suspend fun handleStatusMessage(
        name: String,
        aid: Int,
        payload: JsonObject?,
        session: Session,
        isActive: Boolean,
    ): List<OutgoingMessage> =
        if (name == "loungeStatus") {
            handleLoungeStatus(aid, payload, session, isActive)
        } else {
            listOf(OutgoingMessage.nowPlaying(aid, if (isActive) player.getState() else null))
        }

    private suspend fun handlePlaylistRequest(
        name: String,
        aid: Int,
        payload: JsonObject?,
        session: Session,
        isActive: Boolean,
        client: Client,
    ): List<OutgoingMessage> {
        if (!isActive) return emptyList()
        if (payload == null) {
            note("malformed", "name" to name, "reason" to "payload is not an object")
            return emptyList()
        }
        return handlePlaylistMessage(name, aid, payload, session, client)
    }

    private suspend fun handleTransportMessage(name: String, aid: Int, isActive: Boolean): List<OutgoingMessage> {
        if (!isActive) return emptyList()
        when (name) {
            "next" -> player.next(aid)
            "previous" -> player.previous(aid)
            "pause" -> player.pause(aid)
            "stopVideo" -> player.stop(aid)
            "play" -> player.resume(aid)
        }
        return emptyList()
    }

    private suspend fun handleSeekMessage(name: String, payload: JsonObject?, isActive: Boolean): List<OutgoingMessage> {
        if (!isActive) return emptyList()
        val target = jsParseInt(payload.text("newTime"))
        if (target == null) {
            note("malformed", "name" to name, "reason" to "newTime is not a number")
            return emptyList()
        }
        player.seek(target.toDouble())
        return emptyList()
    }

    /** Answered on an inactive session too, as yt-cast-receiver does. */
    private suspend fun handleGetVolume(aid: Int, session: Session): List<OutgoingMessage> {
        session.cancelDeferredSend(VOLUME_SEND_KEY)
        postVolume(session, aid, player.getVolume(), defer = false)
        return emptyList()
    }

    private suspend fun handleSetVolume(
        name: String,
        aid: Int,
        payload: JsonObject?,
        session: Session,
        isActive: Boolean,
    ): List<OutgoingMessage> {
        if (!isActive) return emptyList()
        val level = jsParseInt(payload.text("volume"))
        if (level == null) {
            note("malformed", "name" to name, "reason" to "volume is not a number")
            return emptyList()
        }
        val delta = if (payload?.containsKey("delta") == true) {
            jsParseInt(payload.text("delta")) ?: run {
                note("malformed", "name" to name, "reason" to "delta is not a number")
                return emptyList()
            }
        } else {
            null
        }
        session.cancelDeferredSend(VOLUME_SEND_KEY)
        val current = player.getVolume()
        val directed = directVolume(level, delta, current.level)
        if (directed.conflicted) {
            note("volumeDirectionConflict", "requested" to level, "current" to current.level, "delta" to delta)
        }
        val wanted = Volume(directed.level, requestedMute(payload))
        if (wanted == current) {
            postVolume(session, aid, current, defer = false)
        } else {
            player.applyVolume(wanted)
            postVolume(session, aid, player.getVolume(), defer = false)
        }
        return emptyList()
    }

    /** The `muted` flag of a setVolume payload: a boolean, or the text "true"; anything else is not muted. */
    private fun requestedMute(payload: JsonObject?): Boolean = when (val m = payload?.get("muted")) {
        is JsonPrimitive -> if (m.isString) m.content.lowercase().trim() == "true" else m.booleanOrNull == true
        else -> false
    }

    private suspend fun handleAutoplayMessage(name: String, aid: Int, payload: JsonObject?, isActive: Boolean): List<OutgoingMessage> {
        if (!isActive) return emptyList()
        val mode = AutoplayMode.entries.firstOrNull { it.name == payload.text("autoplayMode") }
        if (mode == null) {
            note("malformed", "name" to name, "reason" to "unknown autoplayMode")
            return emptyList()
        }
        setAutoplayMode(aid, mode)
        return emptyList()
    }

    private fun unhandledMessage(name: String, aid: Int, client: Client): List<OutgoingMessage> {
        note("notHandled", "theme" to client.theme, "aid" to aid, "name" to name.take(64))
        return emptyList()
    }

    private suspend fun handleLoungeStatus(aid: Int, payload: JsonObject?, session: Session, isActive: Boolean): List<OutgoingMessage> {
        val devices = parseLoungeDevices(payload, session.client)
        val senders = parseRemoteControls(devices ?: emptyList())
        if (state == AppState.STARTING) return takeOverSenders(senders, session, aid)

        val out = ArrayList<OutgoingMessage>()
        if (!applyConnectionEvent(payload, senders, session, aid, out)) {
            // The inactive session is answered with a null availability and does not touch lastNav.
            val nav = if (isActive) player.getNavInfo() else null
            out.add(if (nav != null) navMessage(aid, nav) else OutgoingMessage.onHasPreviousNextChanged(aid, null))
            out.add(OutgoingMessage.onAutoplayModeChanged(aid, nav?.autoplayMode))
        }
        if (devices != null && session === activeSession) {
            val listedIds = remoteControls(devices).mapNotNull { it.text("id") }.toSet()
            dropSendersNotListed(listedIds, session, aid)
        }
        return out
    }

    /** The `devices` list of a loungeStatus, or null (after a note) when it is not a JSON array. */
    private fun parseLoungeDevices(payload: JsonObject?, client: Client): List<JsonElement>? = try {
        (Json.parseToJsonElement(payload.text("devices") ?: "") as JsonArray).toList()
    } catch (e: Exception) {
        note("malformed", "name" to "loungeStatus", "reason" to "devices is not a JSON array", "theme" to client.theme)
        null
    }

    private fun remoteControls(devices: List<JsonElement>): List<JsonObject> =
        devices.mapNotNull { it as? JsonObject }.filter { it.text("type") == "REMOTE_CONTROL" }

    private fun parseRemoteControls(devices: List<JsonElement>): List<Sender> = remoteControls(devices).mapNotNull { data ->
        try {
            Sender.parse(data)
        } catch (e: Exception) {
            problem("sender", e)
            null
        }
    }

    /** The session was just opened: senders already connected to this screen are taken over. */
    private suspend fun takeOverSenders(senders: List<Sender>, session: Session, aid: Int): List<OutgoingMessage> {
        val out = ArrayList<OutgoingMessage>()
        if (senders.isNotEmpty()) {
            connected.clear()
            connected.addAll(senders)
            note("sendersRestored", "count" to senders.size, "theme" to session.client.theme)
            activeSession = session
            setAutoplayModeBySenderCapabilities(aid)
            enforceMutePolicy(aid)
            out.add(navMessage(aid))
            out.add(OutgoingMessage.nowPlaying(aid, null))
        }
        return out
    }

    /** Handles the sender that connected or disconnected, if the status names one; true when it did. */
    private suspend fun applyConnectionEvent(
        payload: JsonObject?,
        senders: List<Sender>,
        session: Session,
        aid: Int,
        out: MutableList<OutgoingMessage>,
    ): Boolean {
        val details: JsonObject? = payload.text("connectionEventDetails")?.let {
            try {
                Json.parseToJsonElement(it) as? JsonObject
            } catch (e: Exception) {
                problem("connectionEventDetails", e)
                null
            }
        }
        var handled = false
        val deviceId = details.text("deviceId")
        if (!deviceId.isNullOrEmpty()) {
            val connecting = senders.find { it.id == deviceId }
            val disconnecting = if (connecting == null) connected.find { it.id == deviceId } else null
            if (connecting != null) {
                out.addAll(handleSenderConnected(connecting, session, aid))
                handled = true
            }
            if (disconnecting != null) {
                out.addAll(handleSenderDisconnected(disconnecting, session, details.text("ui") != "true", aid))
                handled = true
            }
        }
        return handled
    }

    /**
     * Adaptation 26: the server's list of remote controls is the truth. A sender that is connected here but no
     * longer listed left without telling anybody (force-stopping an app sends no disconnect), so it is dropped as
     * an implicit disconnect and the autoplay support is worked out again from the senders that remain.
     * yt-cast-receiver changes the connected senders only on connection events.
     */
    private suspend fun dropSendersNotListed(listedIds: Set<String>, session: Session, aid: Int) {
        val ghosts = connected.filter { it.id !in listedIds }
        for (ghost in ghosts) {
            note("senderNotListed", "client" to session.client.displayName, "name" to ghost.name)
            handleSenderDisconnected(ghost, session, true, aid)
        }
    }

    private suspend fun handlePlaylistMessage(
        name: String,
        aid: Int,
        payload: JsonObject,
        session: Session,
        client: Client,
    ): List<OutgoingMessage> {
        val out = ArrayList<OutgoingMessage>()
        // Dismiss the autoplay video first when the queue's last video is about to change.
        val payloadLast = payload.text("videoIds")?.split(',')?.last()
        var autoplayDismissed = false
        if (player.queue.videoIds.lastOrNull() != payloadLast) {
            post(session, listOf(OutgoingMessage.autoplayUpNext(aid, null)))
            autoplayDismissed = true
        }
        var before = player.queue.getState()
        if (autoplayDismissed) before = before.copy(autoplay = null)
        val navBefore = player.getNavInfo()
        player.queue.updateByMessage(name, payload, client)
        val after = player.queue.getState()
        val navAfter = player.getNavInfo()

        if (before.autoplay?.id != after.autoplay?.id) out.add(OutgoingMessage.autoplayUpNext(aid, after.autoplay?.id))

        if (name == "setPlaylist" &&
            (before.current?.id != after.current?.id || before.current?.context?.index != after.current?.context?.index)
        ) {
            if (player.status != PlayerStatus.STOPPED) player.stop(aid)
            val video = after.current
            if (video != null) player.play(video, (jsParseInt(payload.text("currentTime")) ?: 0).toDouble(), aid)
        } else if (name == "updatePlaylist" && after.current == null) {
            player.stop(aid)
        } else {
            out.add(OutgoingMessage.nowPlaying(aid, player.getState()))
            if (navBefore.hasNext != navAfter.hasNext || navBefore.hasPrevious != navAfter.hasPrevious) {
                out.add(navMessage(aid, navAfter))
            }
        }
        return out
    }

    private suspend fun handleSenderConnected(sender: Sender, session: Session, aid: Int): List<OutgoingMessage> {
        checkAndSwitchActiveSession(session)
        val out = ArrayList<OutgoingMessage>()
        if (connected.none { it.id == sender.id }) {
            note("senderConnected", "client" to session.client.displayName, "app" to sender.app, "name" to sender.name)
            implicitlyDisconnected.removeAll { it.id == sender.id }
            connected.add(sender)
            forgetNetworkSender(sender.id)
            setAutoplayModeBySenderCapabilities(aid)
            enforceMutePolicy(aid)
            val nav = player.getNavInfo()
            val playerState = player.getState()
            out.add(navMessage(aid, nav))
            out.add(OutgoingMessage.nowPlaying(aid, playerState))
            out.add(OutgoingMessage.onStateChange(aid, playerState))
            safely { onSenderConnect(sender) }
        } else {
            note("senderAlreadyConnected", "client" to session.client.displayName)
        }
        return out
    }

    private suspend fun handleSenderDisconnected(sender: Sender, session: Session, implicit: Boolean, aid: Int): List<OutgoingMessage> {
        if (session !== activeSession) return emptyList()
        val index = connected.indexOfFirst { it.id == sender.id }
        if (index < 0) {
            note("anomaly", "what" to "disconnected sender is not among the connected senders")
            return emptyList()
        }
        connected.removeAt(index)
        note("senderDisconnected", "client" to session.client.displayName, "implicit" to implicit, "name" to sender.name)
        if (implicit && implicitlyDisconnected.none { it.id == sender.id }) implicitlyDisconnected.add(sender)

        if (connected.isEmpty()) {
            session.cancelDeferredSend(VOLUME_SEND_KEY)
            val preserveNetworkPlayback = implicit && networkInterrupted(sender)
            val reset = !preserveNetworkPlayback && when (resetPolicy) {
                ResetPlayerOnDisconnectPolicy.ALL_DISCONNECTED -> true
                ResetPlayerOnDisconnectPolicy.ALL_EXPLICITLY_DISCONNECTED -> implicitlyDisconnected.isEmpty()
            }
            note("noSendersLeft", "policy" to resetPolicy.wire, "resetPlayer" to reset, "networkRecovery" to preserveNetworkPlayback)
            if (reset) {
                // reset() also clears the speaker (Player.doClear), even when playback had already stopped.
                player.reset()
            }
        } else if (player.autoplayMode == AutoplayMode.UNSUPPORTED) {
            setAutoplayModeBySenderCapabilities(aid)
        }
        forgetNetworkSender(sender.id)
        if (connected.isNotEmpty()) enforceMutePolicy(aid)
        safely { onSenderDisconnect(sender, implicit) }
        return emptyList()
    }

    // ---- player state to the sender

    private fun handlePlayerStateEvent(event: PlayerStateEvent) {
        val session = activeSession ?: return
        if (connected.isEmpty()) {
            note("stateIgnored", "reason" to "no connected sender")
            return
        }
        val current = event.current
        val previous = event.previous
        val aid = event.aid
        var statusChanged = true
        var positionChanged = true
        var volumeChanged = true
        var nowPlayingChanged = true
        var autoplayChanged = true
        if (previous != null) {
            statusChanged = previous.senderCode != current.senderCode
            positionChanged = previous.position != current.position
            volumeChanged = previous.volume != current.volume
            nowPlayingChanged = previous.queue.current?.id != current.queue.current?.id ||
                previous.queue.id != current.queue.id ||
                previous.queue.current?.context?.index != current.queue.current?.context?.index
            autoplayChanged = previous.queue.autoplay?.id != current.queue.autoplay?.id
        }
        val messages = ArrayList<OutgoingMessage>()
        if (statusChanged || positionChanged) messages.add(OutgoingMessage.onStateChange(aid, current))
        if (nowPlayingChanged || (statusChanged && (previous == null || previous.senderCode != PlayerStatus.PLAYING.code))) {
            messages.add(OutgoingMessage.nowPlaying(aid, current))
            messages.add(navMessage(aid))
        }
        if (volumeChanged || event.fromVolumeChange) {
            postVolume(session, aid, current.volume, defer = event.fromVolumeChange && aid != null)
        }
        if (autoplayChanged) messages.add(OutgoingMessage.autoplayUpNext(aid, current.queue.autoplay?.id))
        // Previous/next can change without a now-playing change (the refresh after an autoplay hop): tell the sender.
        if (messages.none { it.name == "onHasPreviousNextChanged" }) {
            val nav = player.getNavInfo()
            val last = lastNav
            if (last == null || last.hasPrevious != nav.hasPrevious || last.hasNext != nav.hasNext) {
                messages.add(navMessage(aid, nav))
            }
        }
        if (messages.isEmpty()) return
        post(session, messages)
    }

    // ---- helpers

    /** The previous/next message for the active session; remembers what was sent in [lastNav]. */
    private fun navMessage(aid: Int?, nav: NavInfo = player.getNavInfo()): OutgoingMessage {
        lastNav = nav
        return OutgoingMessage.onHasPreviousNextChanged(aid, nav)
    }

    private fun postVolume(session: Session, aid: Int?, volume: Volume, defer: Boolean = true) {
        post(
            session,
            listOf(OutgoingMessage.onVolumeChanged(aid, volume)),
            VOLUME_SEND_KEY,
            if (defer) LoungeConstants.VOLUME_DEFER_MS else 0,
        )
    }

    /**
     * Sends without waiting for the lounge. The send is queued before this returns (the coroutine starts undispatched
     * and Session.sendMessage queues before it suspends), so messages leave in the order they were posted.
     */
    private fun post(
        session: Session,
        messages: List<OutgoingMessage>,
        deferKey: String? = null,
        deferMs: Long = LoungeConstants.VOLUME_DEFER_MS,
    ) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                session.sendMessage(messages, deferKey, deferMs)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                problem("send", e)
            }
        }
    }

    private inline fun safely(block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            problem("callback", e)
        }
    }

    private fun note(type: String, vararg fields: Pair<String, Any?>) {
        try {
            sink.event("lounge-app", mapOf<String, Any?>("type" to type) + fields)
        } catch (_: Exception) {
            // an event sink must not break the app
        }
    }

    private fun problem(where: String, e: Throwable) {
        note(
            "error",
            "where" to where,
            "error" to (e::class.simpleName ?: "Throwable"),
            "message" to Redact.text(e.message ?: "").take(300),
        )
    }
}

/** A non-null string or number value of [key] as text; JavaScript would read `payload.key`. */
private fun JsonObject?.text(key: String): String? =
    (this?.get(key) as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content

/** JavaScript `parseInt(text, 10)`: the leading integer of the text, or null (NaN). */
internal fun jsParseInt(text: String?): Int? =
    if (text == null) null else Regex("^\\s*([+-]?\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull()

/** `application/x-www-form-urlencoded` body to a map; the first value of a repeated key wins. */
private fun parseForm(data: String): Map<String, String> {
    val out = LinkedHashMap<String, String>()
    if (data.isEmpty()) return out
    for (pair in data.split('&')) {
        if (pair.isEmpty()) continue
        val key = decode(pair.substringBefore('='))
        val value = decode(pair.substringAfter('=', ""))
        if (key !in out) out[key] = value
    }
    return out
}

private fun decode(s: String): String = try {
    URLDecoder.decode(s, "UTF-8")
} catch (_: IllegalArgumentException) {
    s
}
