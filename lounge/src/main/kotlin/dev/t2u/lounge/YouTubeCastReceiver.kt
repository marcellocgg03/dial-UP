package dev.t2u.lounge

// Port of yt-cast-receiver 2.1.0 lib/YouTubeCastReceiver.js and its DIAL glue lib/dial/DialServer.js
// (MIT, Copyright (c) Patrick Kan). One cast target per speaker: a DIAL server, the two lounge sessions and one SSDP
// advertisement, with an identity (DIAL uuid, app pid, lounge screen and device IDs) that survives restarts.
//
// Kotlin addition: yt-cast-receiver picks a random uuid per start, which would make the phone see a
// second cast target after every restart; the uuid is stored next to app.pid and mdxContext.

import dev.t2u.dial.DialConfig
import dev.t2u.dial.DialRequest
import dev.t2u.dial.DialServer
import dev.t2u.upnp.EventSink
import dev.t2u.upnp.NoEvents
import dev.t2u.upnp.SsdpDevice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import java.util.UUID

/**
 * Announces a receiver's DIAL device on the network. One shared SSDP responder serves every receiver (one per app),
 * so the receiver only hands its device over; the real implementation lives with the responder.
 */
interface DialAdvertiser {
    fun advertise(device: SsdpDevice)

    /** Says byebye for [uuid]; must not throw for an unknown uuid. */
    fun withdraw(uuid: String)
}

/**
 * Everything a receiver needs from the app around it. [bindHost] is the address the DIAL server listens on and the host
 * in the SSDP LOCATION. [accessPolicy] is handed to the DIAL server, which refuses other callers with 403.
 */
data class ReceiverDeps(
    val bindHost: String,
    val accessPolicy: (String) -> Boolean,
    val advertiser: DialAdvertiser,
    val serverHeader: String,
    val http: OkHttpClient,
    val endpoints: LoungeEndpoints = LoungeEndpoints(),
    val sink: EventSink = NoEvents,
    val scope: CoroutineScope,
    val onDialRequest: (DialRequest) -> Unit = {},
    /** True for a caller served only because the LAN is allowed. */
    val isGuest: (String) -> Boolean = { false },
    /** A guest started a cast launch: the speaker's name and the caller's address. */
    val onGuestLaunch: (speaker: String, remote: String) -> Unit = { _, _ -> },
)

private val UUID_SHAPE = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

class YouTubeCastReceiver(
    private val player: Player,
    private val options: ReceiverOptions,
    private val deps: ReceiverDeps,
) : CastReceiver {
    private val app = YouTubeApp(
        player,
        AppOptions(options.screenName, options.brand, options.model, options.dataStore),
        deps.http,
        deps.endpoints,
        deps.sink,
        deps.scope,
    )

    @Volatile
    override var status: String = "stopped"
        private set

    private val lifecycle = Mutex()

    @Volatile
    private var dial: DialServer? = null
    private var uuid: String? = null

    /** The address the DIAL server listens on and the LOCATION names; [rebindDial] moves it. */
    @Volatile private var bindHost: String = deps.bindHost

    /** The DIAL friendly name: [ReceiverOptions.name] until [rename] changes it; read at start and at a rebind. */
    @Volatile private var friendlyName: String = options.name

    /** Starts a DIAL server; a seam for the tests that cancel the caller while the server binds. */
    internal var dialStarter: suspend (DialServer) -> Unit = { it.startSuspending() }

    /** The config the running DIAL server was built from; the port in it is the one a rebind keeps. */
    private var dialConfig: DialConfig? = null

    /** Called when a sender connects. */
    var onSenderConnect: (Sender) -> Unit
        get() = app.onSenderConnect
        set(value) {
            app.onSenderConnect = value
        }

    /** Called when a sender leaves; the second argument is true when it vanished without saying goodbye. */
    var onSenderDisconnect: (Sender, Boolean) -> Unit
        get() = app.onSenderDisconnect
        set(value) {
            app.onSenderDisconnect = value
        }

    /** Called after the receiver stopped itself because a lounge session failed for good. */
    @Volatile
    @set:JvmName("assignOnTerminate") // the property setter would clash with setOnTerminate below
    var onTerminate: (Throwable) -> Unit = {}

    init {
        app.onTerminate = { error ->
            deps.scope.launch {
                try {
                    stop()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    note("error", "where" to "terminate-stop", "error" to (e::class.simpleName ?: "Throwable"))
                }
                note("terminated", "error" to (error::class.simpleName ?: "Throwable"))
                try {
                    onTerminate(error)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // the owner's callback must not break the receiver
                }
            }
        }
    }

    override fun connectedSenders(): List<Sender> = app.connectedSenders

    fun onNetworkLost() = app.onNetworkLost()

    fun onNetworkReturned() = app.onNetworkReturned()

    override fun enableAutoplayOnConnect(value: Boolean) {
        app.enableAutoplayOnConnect(value)
    }

    override fun setOnTerminate(callback: (Throwable) -> Unit) {
        onTerminate = callback
    }

    /**
     * Reads or creates the uuid, starts the lounge app, then the DIAL server, then advertises. A failure undoes whatever
     * had started and is rethrown, so a failed start leaves nothing listening and nothing advertised.
     */
    override suspend fun start() {
        lifecycle.withLock {
            if (status != "stopped") {
                note("startIgnored", "status" to status)
                return
            }
            status = "starting"
            var appStarted = false
            var server: DialServer? = null
            var advertisedUuid: String? = null
            try {
                val id = loadUuid()
                app.start()
                appStarted = true
                val config = DialConfig(
                    friendlyName = friendlyName,
                    uuid = id,
                    bindHost = bindHost,
                    port = options.dialPort,
                    manufacturer = options.brand,
                    modelName = options.model,
                    pid = app.pid,
                    accessPolicy = deps.accessPolicy,
                    isGuest = deps.isGuest,
                    onGuestLaunch = { remote -> deps.onGuestLaunch(friendlyName, remote) },
                )
                val created = DialServer(config, deps.onDialRequest) { _, body -> handleLaunch(body) }
                // Owned before it starts: withContext drops its result when the caller is cancelled as it returns, and a server
                // that was bound by then must still be reachable by the cleanup below.
                server = created
                withContext(Dispatchers.IO) { dialStarter(created) }
                val location = "http://${config.bindHost}:${created.resolvedPort()}${config.prefix}/ssdp/device-desc.xml"
                deps.advertiser.advertise(SsdpDevice(friendlyName, id, deps.serverHeader, location))
                advertisedUuid = id
                dial = created
                dialConfig = config
                uuid = id
                status = "running"
                note("started", "port" to created.resolvedPort())
            } catch (e: Throwable) {
                withContext(NonCancellable) {
                    advertisedUuid?.let { runQuietly { deps.advertiser.withdraw(it) } }
                    server?.let { s -> runQuietly { withContext(Dispatchers.IO) { s.stop() } } }
                    if (appStarted) runQuietly { app.stop() }
                }
                dial = null
                dialConfig = null
                uuid = null
                status = "stopped"
                note("startFailed", "error" to (e::class.simpleName ?: "Throwable"))
                throw e
            }
        }
    }

    /**
     * Withdraws the SSDP device first (byebye while the DIAL port still answers), then closes the DIAL port, then ends
     * the lounge sessions (the senders are told). Every step is tried even if an earlier one failed; the first failure
     * is rethrown at the end.
     */
    override suspend fun stop() {
        lifecycle.withLock {
            if (status != "running") {
                note("stopIgnored", "status" to status)
                return
            }
            status = "stopping"
            var failure: Throwable? = null
            withContext(NonCancellable) {
                try {
                    uuid?.let { deps.advertiser.withdraw(it) }
                } catch (e: Exception) {
                    failure = failure ?: e
                }
                try {
                    withContext(Dispatchers.IO) { dial?.stop() }
                } catch (e: Exception) {
                    failure = failure ?: e
                }
                try {
                    app.stop()
                } catch (e: Exception) {
                    failure = failure ?: e
                }
            }
            dial = null
            dialConfig = null
            uuid = null
            status = "stopped"
            note("stopped")
            failure?.let { throw it }
        }
    }

    /**
     * True when the receiver runs but has no DIAL listener: a move whose bind failed (rebindDial) leaves none, and the
     * speaker is then missing from the phone's cast menu. The bridge's housekeeping rebinds it ([rebindDial]).
     */
    fun dialMissing(): Boolean = status == "running" && dial == null

    /**
     * Moves the DIAL server to [newHost] on the port it already has and advertises the new LOCATION.
     * Only the HTTP listener is replaced: the uuid, the app pid and the lounge sessions are untouched, so the sender stays
     * connected and the phone's cast menu keeps the same entry. A receiver that is not running only remembers [newHost]
     * for its next start. A failure leaves no DIAL server, is noted as rebindFailed and rethrown.
     */
    suspend fun rebindDial(newHost: String) {
        lifecycle.withLock {
            val old = dial
            val config = dialConfig
            val id = uuid
            if (status != "running" || config == null || id == null) {
                bindHost = newHost
                note("rebindIgnored", "status" to status)
                return
            }
            // a server a failed earlier move left behind is gone (old == null): start again on the port the config names
            val port = old?.resolvedPort() ?: config.port
            var created: DialServer? = null
            try {
                withContext(Dispatchers.IO) { old?.stop() }
                dial = null
                val moved = config.copy(bindHost = newHost, port = port)
                val next = DialServer(moved, deps.onDialRequest) { _, body -> handleLaunch(body) }
                created = next
                // owned before it starts, as in start: a cancellation at the boundary must not strand a bound port
                dial = next
                withContext(Dispatchers.IO) { dialStarter(next) }
                dialConfig = moved
                bindHost = newHost
                deps.advertiser.advertise(
                    SsdpDevice(friendlyName, id, deps.serverHeader, "http://$newHost:$port${moved.prefix}/ssdp/device-desc.xml"),
                )
                note("rebound", "port" to port)
            } catch (e: CancellationException) {
                // the old server is gone and the new one may be bound: close it, so the port is free for the next attempt
                withContext(NonCancellable) { created?.let { s -> runQuietly { withContext(Dispatchers.IO) { s.stop() } } } }
                dial = null
                throw e
            } catch (e: Throwable) {
                dial = null
                note("rebindFailed", "error" to (e::class.simpleName ?: "Throwable"))
                throw e
            }
        }
    }

    /**
     * Renaming from the settings screen: [name] becomes the DIAL friendly name from the next device-desc.xml answer, and the device is
     * advertised again with it (the bridge's advertiser decides whether a NOTIFY goes out: phone-only mode sends none). No byebye
     * is sent, so a sender that is casting keeps its entry. The lounge screen name keeps its start-time value until the next
     * start. A receiver that is not running only remembers the name for its next start.
     */
    override suspend fun rename(name: String) {
        lifecycle.withLock {
            friendlyName = name
            val server = dial
            val config = dialConfig
            val id = uuid
            if (status != "running" || server == null || config == null || id == null) {
                note("renameIgnored", "status" to status)
                return
            }
            server.rename(name)
            dialConfig = config.copy(friendlyName = name)
            val location = "http://$bindHost:${server.resolvedPort()}${config.prefix}/ssdp/device-desc.xml"
            deps.advertiser.advertise(SsdpDevice(name, id, deps.serverHeader, location))
            note("renamed")
        }
    }

    /** The DIAL launch: null (no LOCATION, status 200) when the launch data is bad or the lounge refused it. */
    private suspend fun handleLaunch(body: String): String? = try {
        // which receiver the sender's launch reached (a wrong-target cast shows here); no pairing code is logged
        note("launchTarget", "dialPort" to (dial?.resolvedPort() ?: options.dialPort), "dialUuid" to uuid, "name" to friendlyName)
        app.launch(body)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        note("launchFailed", "error" to (e::class.simpleName ?: "Throwable"), "message" to Redact.text(e.message ?: "").take(300))
        null
    }

    private suspend fun loadUuid(): String {
        val store = options.dataStore
        val stored = (store.get(StoreKeys.DIAL_UUID) as? JsonPrimitive)?.contentOrNull
        // the uuid goes into SSDP headers and the device description: anything but a uuid is replaced
        if (stored != null && UUID_SHAPE.matches(stored)) return stored
        val fresh = UUID.randomUUID().toString()
        store.set(StoreKeys.DIAL_UUID, JsonPrimitive(fresh))
        return fresh
    }

    private inline fun runQuietly(block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // cleaning up after a failure
        }
    }

    private fun note(type: String, vararg fields: Pair<String, Any?>) {
        try {
            deps.sink.event("lounge-receiver", mapOf<String, Any?>("type" to type) + fields)
        } catch (_: Exception) {
            // an event sink must not break the receiver
        }
    }
}
