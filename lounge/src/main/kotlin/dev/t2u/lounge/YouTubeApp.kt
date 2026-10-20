package dev.t2u.lounge

// Port of yt-cast-receiver 2.1.0 lib/app/YouTubeApp.js (MIT, Copyright (c) Patrick Kan).
// The lounge app: two sessions (YouTube `cl`, YouTube Music `m`), sender messages turned into player calls, player
// state turned into messages to the sender, sender tracking, session switching, mute and autoplay policy.
//
// CLAUDE.md protocol-risk constraint: message names and payload keys the sender uses are handled in this one file.

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

/**
 * What the app needs to know about its speaker. [dataStore] holds the DIAL pid and the two sessions' identities;
 * without one nothing survives a restart. The policies default to the yt-cast-receiver defaults.
 */
data class AppOptions(
    val screenName: String,
    val brand: String,
    val model: String,
    val dataStore: DataStore?,
    val mutePolicy: MutePolicy = MutePolicy.AUTO,
    val resetPlayerOnDisconnect: ResetPlayerOnDisconnectPolicy = ResetPlayerOnDisconnectPolicy.ALL_DISCONNECTED,
)

/** The app could not start; the cause carries the detail. */
class AppException(message: String, cause: Throwable? = null) : Exception(message, cause)

enum class AppState { STOPPED, STARTING, RUNNING, STOPPING }

private val SAFE_PID = Regex("^[A-Za-z0-9-]{1,64}$")

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

    private val connected = CopyOnWriteArrayList<Sender>()
    private val implicitlyDisconnected = CopyOnWriteArrayList<Sender>()

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

    init {
        if (player.events === NoEvents) player.events = sink
    }

    fun enableAutoplayOnConnect(value: Boolean) {
        autoplayOnConnect = if (value) AutoplayMode.ENABLED else AutoplayMode.DISABLED
    }

    fun setResetPlayerOnDisconnectPolicy(value: ResetPlayerOnDisconnectPolicy) {
        resetPolicy = value
    }

    /** Restores or creates the pid, then begins both sessions at once. On failure both are ended and the error is rethrown. */
    suspend fun start() {
        if (state != AppState.STOPPED) return
        loadPid()
        note("starting")
        state = AppState.STARTING
        player.addStateListener(stateListener)
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
        implicitlyDisconnected.clear()
        player.removeStateListener(stateListener)
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
                // Only one client can be connected at a time: the senders of the old session are dropped.
                note("switching", "from" to active.client.displayName, "to" to target.client.displayName)
                player.reset()
                val dropped = connected.toList()
                connected.clear()
                implicitlyDisconnected.clear()
                activeSession = target
                post(active, listOf(OutgoingMessage.loungeScreenDisconnected()))
                // yt-cast-receiver clears the list before it walks it, so it never reports these; here they are reported
                for (sender in dropped) safely { onSenderDisconnect(sender, false) }
            }
            note("activeSession", "client" to target.client.displayName)
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

    private suspend fun handleIncomingMessage(message: IncomingMessage, session: Session) {
        val aid = message.aid
        val payload = message.payload as? JsonObject
        val isActive = session === activeSession
        val client = session.client
        val send = ArrayList<OutgoingMessage>()
        when (message.name) {
            "getNowPlaying" -> send.add(OutgoingMessage.nowPlaying(aid, if (isActive) player.getState() else null))

            "loungeStatus" -> send.addAll(handleLoungeStatus(aid, payload, session, isActive))

            "setPlaylist", "updatePlaylist" -> {
                if (!isActive) return
                if (payload == null) {
                    note("malformed", "name" to message.name, "reason" to "payload is not an object")
                    return
                }
                send.addAll(handlePlaylistMessage(message.name, aid, payload, session, client))
            }

            "next" -> {
                if (!isActive) return
                player.next(aid)
            }

            "previous" -> {
                if (!isActive) return
                player.previous(aid)
            }

            "pause" -> {
                if (!isActive) return
                player.pause(aid)
            }

            "stopVideo" -> {
                if (!isActive) return
                player.stop(aid)
            }

            "seekTo" -> {
                if (!isActive) return
                val target = jsParseInt(payload.text("newTime"))
                if (target == null) {
                    note("malformed", "name" to message.name, "reason" to "newTime is not a number")
                    return
                }
                player.seek(target.toDouble())
            }

            "getVolume" -> send.add(OutgoingMessage.onVolumeChanged(aid, player.getVolume()))

            "setVolume" -> {
                if (!isActive) return
                val level = jsParseInt(payload.text("volume"))
                if (level == null) {
                    note("malformed", "name" to message.name, "reason" to "volume is not a number")
                    return
                }
                val muted = when (val m = payload?.get("muted")) {
                    is JsonPrimitive -> if (m.isString) m.content.lowercase().trim() == "true" else m.booleanOrNull == true
                    else -> false
                }
                val wanted = Volume(level, muted)
                val current = player.getVolume()
                if (wanted != current) player.setVolume(wanted, aid)
            }

            "play" -> {
                if (!isActive) return
                player.resume(aid)
            }

            "setAutoplayMode" -> {
                if (!isActive) return
                val mode = AutoplayMode.entries.firstOrNull { it.name == payload.text("autoplayMode") }
                if (mode == null) {
                    note("malformed", "name" to message.name, "reason" to "unknown autoplayMode")
                    return
                }
                setAutoplayMode(aid, mode)
            }

            else -> note("notHandled", "theme" to client.theme, "aid" to aid, "name" to message.name.take(64))
        }
        if (send.isNotEmpty()) post(session, send)
    }

    private suspend fun handleLoungeStatus(aid: Int, payload: JsonObject?, session: Session, isActive: Boolean): List<OutgoingMessage> {
        val client = session.client
        val devices: List<JsonElement> = try {
            (Json.parseToJsonElement(payload.text("devices") ?: "") as JsonArray).toList()
        } catch (e: Exception) {
            note("malformed", "name" to "loungeStatus", "reason" to "devices is not a JSON array", "theme" to client.theme)
            emptyList()
        }
        val senders = devices.mapNotNull { it as? JsonObject }
            .filter { it.text("type") == "REMOTE_CONTROL" }
            .mapNotNull { data ->
                try {
                    Sender.parse(data)
                } catch (e: Exception) {
                    problem("sender", e)
                    null
                }
            }
        val out = ArrayList<OutgoingMessage>()

        if (state == AppState.STARTING) {
            // The session was just opened: senders already connected to this screen are taken over.
            if (senders.isNotEmpty()) {
                connected.clear()
                connected.addAll(senders)
                note("sendersRestored", "count" to senders.size, "theme" to client.theme)
                activeSession = session
                setAutoplayModeBySenderCapabilities(aid)
                enforceMutePolicy(aid)
                out.add(OutgoingMessage.onHasPreviousNextChanged(aid, player.getNavInfo()))
                out.add(OutgoingMessage.nowPlaying(aid, null))
            }
            return out
        }

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
        if (!handled) {
            val nav = if (isActive) player.getNavInfo() else null
            out.add(OutgoingMessage.onHasPreviousNextChanged(aid, nav))
            out.add(OutgoingMessage.onAutoplayModeChanged(aid, nav?.autoplayMode))
        }
        return out
    }

    private suspend fun handlePlaylistMessage(name: String, aid: Int, payload: JsonObject, session: Session, client: Client): List<OutgoingMessage> {
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

        if (name == "setPlaylist" && (before.current?.id != after.current?.id || before.current?.context?.index != after.current?.context?.index)) {
            if (player.status != PlayerStatus.STOPPED) player.stop(aid)
            val video = after.current
            if (video != null) player.play(video, (jsParseInt(payload.text("currentTime")) ?: 0).toDouble(), aid)
        } else if (name == "updatePlaylist" && after.current == null) {
            player.stop(aid)
        } else {
            out.add(OutgoingMessage.nowPlaying(aid, player.getState()))
            if (navBefore.hasNext != navAfter.hasNext || navBefore.hasPrevious != navAfter.hasPrevious) {
                out.add(OutgoingMessage.onHasPreviousNextChanged(aid, navAfter))
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
            setAutoplayModeBySenderCapabilities(aid)
            enforceMutePolicy(aid)
            val nav = player.getNavInfo()
            val playerState = player.getState()
            out.add(OutgoingMessage.onHasPreviousNextChanged(aid, nav))
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
            val reset = when (resetPolicy) {
                ResetPlayerOnDisconnectPolicy.ALL_DISCONNECTED -> true
                ResetPlayerOnDisconnectPolicy.ALL_EXPLICITLY_DISCONNECTED -> implicitlyDisconnected.isEmpty()
            }
            note("noSendersLeft", "policy" to resetPolicy.wire, "resetPlayer" to reset)
            if (reset) {
                // CAST-06 seam (Phase 3): the speaker's current track must be cleared here (stop plus an emptied
                // transport URI), not only the player state. See the KDoc on Renderer.doStop; deliberately not done in Phase 2.
                player.reset()
            }
        } else if (player.autoplayMode == AutoplayMode.UNSUPPORTED) {
            setAutoplayModeBySenderCapabilities(aid)
        }
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
            statusChanged = previous.status != current.status
            positionChanged = previous.position != current.position
            volumeChanged = previous.volume != current.volume
            nowPlayingChanged = previous.queue.current?.id != current.queue.current?.id ||
                previous.queue.id != current.queue.id ||
                previous.queue.current?.context?.index != current.queue.current?.context?.index
            autoplayChanged = previous.queue.autoplay?.id != current.queue.autoplay?.id
        }
        val messages = ArrayList<OutgoingMessage>()
        if (statusChanged || positionChanged) messages.add(OutgoingMessage.onStateChange(aid, current))
        if (nowPlayingChanged || (statusChanged && (previous == null || previous.status != PlayerStatus.PLAYING))) {
            messages.add(OutgoingMessage.nowPlaying(aid, current))
            messages.add(OutgoingMessage.onHasPreviousNextChanged(aid, player.getNavInfo()))
        }
        if (volumeChanged) messages.add(OutgoingMessage.onVolumeChanged(aid, current.volume))
        if (autoplayChanged) messages.add(OutgoingMessage.autoplayUpNext(aid, current.queue.autoplay?.id))
        if (messages.isEmpty()) return
        if (messages.all { it.name == "onVolumeChanged" && it.aid != null }) {
            // a slider drag sends many setVolume messages: only the latest volume goes out, after a short quiet interval
            post(session, messages, "onVolumeChanged")
        } else {
            post(session, messages)
        }
    }

    // ---- helpers

    /**
     * Sends without waiting for the lounge. The send is queued before this returns (the coroutine starts undispatched
     * and Session.sendMessage queues before it suspends), so messages leave in the order they were posted.
     */
    private fun post(session: Session, messages: List<OutgoingMessage>, deferKey: String? = null) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                session.sendMessage(messages, deferKey)
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
        note("error", "where" to where, "error" to (e::class.simpleName ?: "Throwable"), "message" to Redact.text(e.message ?: "").take(300))
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
