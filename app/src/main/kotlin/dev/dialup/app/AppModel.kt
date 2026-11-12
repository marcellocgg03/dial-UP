// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.dialup.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.SystemClock
import dev.dialup.app.model.BridgeStatus
import dev.dialup.app.model.FOREGROUND_SEARCH_GAP_MS
import dev.dialup.app.model.SearchThrottle
import dev.t2u.renderer.HiddenSpeaker
import dev.t2u.renderer.ManualAddResult
import dev.t2u.renderer.ManualSpeaker
import dev.t2u.renderer.PinnedSpeaker
import dev.t2u.renderer.SpeakerAction
import dev.t2u.renderer.SpeakerUi
import dev.t2u.renderer.parseManualIp
import dev.t2u.renderer.perform
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * What the screen collects: a process singleton (like [AppSettings]) that turns the service-side
 * state in [ServiceHub] into flows the UI can follow. The UI never reaches into the service itself.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppModel private constructor(context: Context) {
    private val appContext: Context = context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings: Settings = AppSettings.of(context)

    /** True while the bridge service is up (set after the battery gate, cleared in onDestroy). */
    val serviceRunning: StateFlow<Boolean> =
        ServiceHub.serviceFlow.map { it != null }.stateIn(scope, SharingStarted.Eagerly, ServiceHub.service != null)

    /**
     * Whether the phone has a Wi-Fi or hotspot address the bridge could bind to ([hasBindableNetwork]). The service stops itself
     * on Wi-Fi loss, so the UI reads presence itself instead of asking the service. It listens only while a
     * screen collects it (WhileSubscribed): the network callback is unregistered when the last collector goes, and the 5 s
     * re-check (a hotspot starting or stopping raises no Wi-Fi callback) stops with it.
     */
    val wifiPresent: StateFlow<Boolean> =
        wifiPresenceFlow().distinctUntilChanged().flowOn(Dispatchers.IO)
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000), runCatching { hasBindableNetwork(context) }.getOrDefault(true))

    private fun wifiPresenceFlow(): Flow<Boolean> = callbackFlow {
        val cm = appContext.getSystemService(ConnectivityManager::class.java)
        // never claim "no Wi-Fi" when the check itself cannot run
        fun probe() { trySend(runCatching { hasBindableNetwork(appContext) }.getOrDefault(true)) }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = probe()
            override fun onLost(network: Network) = probe()
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = probe()
        }
        val registered = cm != null && runCatching {
            cm.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), callback)
        }.isSuccess
        probe()
        if (!registered) trySend(true)
        val recheck = launch {
            while (isActive) {
                delay(WIFI_RECHECK_MS)
                probe()
            }
        }
        awaitClose {
            recheck.cancel()
            if (registered) runCatching { cm?.unregisterNetworkCallback(callback) }
        }
    }

    /** The cast feature while the service runs, else null (the speaker list reads speakers from it). */
    val cast: StateFlow<CastFeature?> = ServiceHub.cast

    /** The running bridge's speakers with their live state; empty while the bridge is off and across a bridge rebuild. */
    val speakers: StateFlow<List<SpeakerUi>> =
        cast.flatMapLatest { it?.speakers ?: flowOf(emptyList()) }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** True while the running bridge is inside a multicast renderer-search reply window. */
    val searching: StateFlow<Boolean> =
        cast.flatMapLatest { it?.searching ?: flowOf(false) }.stateIn(scope, SharingStarted.Eagerly, false)

    /** How many speakers are being cast to; 0 while the bridge is off. */
    val casting: StateFlow<Int> =
        cast.flatMapLatest { it?.castCount?.map { c -> c.casting } ?: flowOf(0) }
            .stateIn(scope, SharingStarted.Eagerly, 0)

    /** The bridge card's state: bridge up or not, and the access mode (it words "Waiting for Wi-Fi"). */
    val status: StateFlow<BridgeStatus> =
        cast.flatMapLatest { it?.status ?: flowOf(BridgeStatus.OFF) }.stateIn(scope, SharingStarted.Eagerly, BridgeStatus.OFF)

    /** True while a bridge exists (BridgeStatus.running is `bridge != null`); false with the service up but waiting for Wi-Fi. */
    val bridgeUp: StateFlow<Boolean> = status.map { it.running }.stateIn(scope, SharingStarted.Eagerly, false)

    /**
     * Hide from a card's menu, the only way to take a card off the list. The running bridge drops the speaker as a cast
     * target at once (stopping it first when it plays) and keeps it out; with no bridge, or when the bridge does not know the
     * speaker, it goes straight into the hidden store (this is how a saved renderer's greyed card is hidden with the bridge off;
     * the registry reads the same process-level store at the next start). Fire-and-forget: a failure is dropped, a tap must never
     * crash the app.
     */
    fun hide(speaker: SpeakerUi): Job = scope.launch {
        try {
            val bridge = cast.value?.currentBridge()
            if (bridge == null || !bridge.hide(speaker.udn)) {
                SpeakerStores.of(appContext).hidden.add(HiddenSpeaker(speaker.udn, speaker.name, speaker.address))
            }
            // a hidden card loses its pin
            if (SpeakerStores.of(appContext).pinned.remove(speaker.udn)) {
                ServiceHub.service?.evidence?.event("unpinned", mapOf("udn" to speaker.udn, "source" to "hide"))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Failures.report("speaker:hide", e)
            // the speaker simply stays where it is
        }
    }

    /**
     * One SSDP search through the running bridge, at once ([trigger] says who asked: `ui` or `foreground`). With no
     * bridge nothing happens. Fire-and-forget: a failure is dropped, a tap must never crash the app.
     */
    fun refreshRenderers(trigger: String): Job = scope.launch {
        try {
            cast.value?.currentBridge()?.searchNow(trigger)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Failures.report("speaker:refresh", e)
            // the list simply waits for the next periodic search
        }
    }

    /**
     * The Refresh button: forget the listed renderers and search from scratch like the first discovery (the empty list shows the
     * search spinner). Forgetting a renderer ends its cast, so the screen asks first while anything is cast to. Fire-and-forget
     * like [refreshRenderers].
     */
    fun rediscoverRenderers(): Job = scope.launch {
        try {
            cast.value?.currentBridge()?.rediscover("ui")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Failures.report("speaker:rediscover", e)
            // the list simply waits for the next periodic search
        }
    }

    private val foregroundThrottle = SearchThrottle({ SystemClock.elapsedRealtime() }, FOREGROUND_SEARCH_GAP_MS)

    /**
     * The app came to the foreground: one [refreshRenderers] search, at most one per [FOREGROUND_SEARCH_GAP_MS]; null
     * when the throttle holds it back. MainActivity.opens already skips the first start after a recreation, so a rotation does
     * not search.
     */
    fun searchOnForeground(): Job? = if (foregroundThrottle.tryAcquire()) refreshRenderers("foreground") else null

    /** The hidden speakers; the store lives with the process, so the list is right while the bridge is off too. */
    val hidden: StateFlow<List<HiddenSpeaker>> = SpeakerStores.of(context).hidden.entries

    /**
     * Restore. Through the running bridge when there is one, which also searches for the speaker at once; with the
     * bridge off straight on the store, and the bridge's next start finds the speaker. Fire-and-forget.
     */
    fun unhide(udn: String): Job = scope.launch {
        try {
            val bridge = cast.value?.currentBridge()
            if (bridge == null || !bridge.unhide(udn)) SpeakerStores.of(appContext).hidden.remove(udn)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Failures.report("speaker:unhide", e)
            // the speaker stays hidden
        }
    }

    /** The pinned speakers, in the order they were pinned; the store lives with the process, so the list is right with the bridge off too. */
    val pinned: StateFlow<List<PinnedSpeaker>> = SpeakerStores.of(context).pinned.entries

    /**
     * Pins [speaker] to the top of the list (with [pin] false, unpins it). The name and address are kept so the card can be
     * drawn greyed while the bridge is off. Fire-and-forget: a failure leaves the pin as it was.
     */
    fun setPinned(speaker: SpeakerUi, pin: Boolean): Job = scope.launch {
        try {
            val store = SpeakerStores.of(appContext).pinned
            if (pin) store.add(PinnedSpeaker(speaker.udn, speaker.name, speaker.address)) else store.remove(speaker.udn)
            ServiceHub.service?.evidence?.event(if (pin) "pinned" else "unpinned", mapOf("udn" to speaker.udn, "source" to "menu"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Failures.report("speaker:pin", e)
        }
    }

    /** The speakers added by address; the store lives with the process, so the list is right while the bridge is off too. */
    val manual: StateFlow<List<ManualSpeaker>> = SpeakerStores.of(context).manual.entries

    /**
     * Delete on the card (or the Hidden row) of a renderer saved by address (only [dev.dialup.app.model.deleteOffered]
     * cards have the item). Through the running bridge when there is one (it also takes the renderer out of the cast menus);
     * with the bridge off, or when the bridge does not know it, straight on the store. The UDN also leaves the hidden and name
     * stores so nothing of the renderer stays behind. Fire-and-forget: a failure leaves the renderer listed.
     */
    fun deleteManual(udn: String): Job = scope.launch {
        try {
            val bridge = cast.value?.currentBridge()
            val stores = SpeakerStores.of(appContext)
            if (bridge == null || !bridge.removeManual(udn)) stores.manual.remove(udn)
            stores.hidden.remove(udn)
            stores.names.remove(udn)
            if (stores.pinned.remove(udn)) ServiceHub.service?.evidence?.event("unpinned", mapOf("udn" to udn, "source" to "delete"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Failures.report("speaker:delete-manual", e)
            // the renderer stays listed
        }
    }

    /**
     * GUI rename: the names the author gave the renderers, by UDN; the store lives with the process, so it is readable while
     * the bridge is off too.
     */
    val names: StateFlow<Map<String, String>> = SpeakerStores.of(context).names.entries

    /**
     * GUI rename: names the renderer [udn] [name], or resets it to its own name when [name] is null. Matched by UDN. Through the
     * the store only, unless [live]: then through the running bridge (the card and the DIAL friendly name change at once, a cast is
     * not cut). The caller passes [live] only with nothing cast; with a cast the old name stays until the bridge restarts, so
     * DialUP and YouTube never show different names. An unusable name changes nothing. Fire-and-forget.
     */
    fun rename(udn: String, name: String?, live: Boolean = false): Job = scope.launch {
        try {
            val bridge = if (live) cast.value?.currentBridge() else null
            if (bridge == null || !bridge.rename(udn, name)) {
                val store = SpeakerStores.of(appContext).names
                if (name == null) store.remove(udn) else store.set(udn, name)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Failures.report("speaker:rename", e)
            // the renderer keeps its name
        }
    }

    /**
     * Add speaker by the IPv4 address in [text]. Null means the text is not a private LAN IPv4 address and nothing was
     * sent (the address must be a private LAN IPv4 literal); with no bridge the answer is [ManualAddResult.NotUsable],
     * because the probe runs from the bridge's SSDP client. Otherwise the running bridge probes the address and says what
     * came of it. A failure is reported as [ManualAddResult.NotUsable]; cancellation propagates. The bridge saves a speaker
     * that was added.
     */
    suspend fun addManual(text: String): ManualAddResult? {
        val ip = parseManualIp(text) ?: return null
        val bridge = cast.value?.currentBridge() ?: return ManualAddResult.NotUsable("The bridge is not running")
        return try {
            withContext(Dispatchers.Default) { bridge.addManual(ip) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Failures.report("speaker:add-manual", e)
            ManualAddResult.NotUsable("Could not add the renderer: ${e.javaClass.simpleName}")
        }
    }

    /** Fire-and-forget [controlNow] for a card tap; it never throws into the UI. */
    fun control(udn: String, action: SpeakerAction): Job = scope.launch { controlNow(udn, action) }

    /**
     * Runs a card [action] on the speaker [udn] of the running bridge and says whether the speaker did it. A command that
     * takes over [CONTROL_TIMEOUT_MS], a missing speaker or bridge, and any failure all answer false: a card tap must never
     * crash the app. Cancellation still propagates. There is no volume action.
     */
    suspend fun controlNow(udn: String, action: SpeakerAction): Boolean =
        try {
            // Off the caller's dispatcher: the presenter calls this from the UI scope.
            withContext(Dispatchers.Default) {
                withTimeoutOrNull(CONTROL_TIMEOUT_MS) { cast.value?.currentBridge()?.renderer(udn)?.perform(action) } ?: false
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Failures.report("speaker:control", e)
            false
        }

    companion object {
        private const val CONTROL_TIMEOUT_MS = 20_000L
        private const val WIFI_RECHECK_MS = 5_000L

        @Volatile private var instance: AppModel? = null

        fun of(context: Context): AppModel {
            instance?.let { return it }
            synchronized(this) {
                instance?.let { return it }
                return AppModel(context.applicationContext).also { instance = it }
            }
        }
    }
}
