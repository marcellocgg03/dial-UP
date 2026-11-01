// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.dialup.app

import android.content.Context
import dev.dialup.app.model.BridgeStatus
import dev.dialup.app.model.addRefusal
import dev.t2u.renderer.HiddenSpeaker
import dev.t2u.renderer.ManualAddResult
import dev.t2u.renderer.ManualSpeaker
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
import kotlinx.coroutines.flow.SharingStarted
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

    /** The cast feature while the service runs, else null (plan 07-03 reads speakers from it). */
    val cast: StateFlow<CastFeature?> = ServiceHub.cast

    /** The running bridge's speakers with their live state; empty while the bridge is off and across a bridge rebuild. */
    val speakers: StateFlow<List<SpeakerUi>> =
        cast.flatMapLatest { it?.speakers ?: flowOf(emptyList()) }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** How many speakers are being cast to; 0 while the bridge is off. */
    val casting: StateFlow<Int> =
        cast.flatMapLatest { it?.castCount?.map { c -> c.casting } ?: flowOf(0) }
            .stateIn(scope, SharingStarted.Eagerly, 0)

    /** The bridge card's state: bridge up or not, and the access mode (plan 07-04 words "Waiting for Wi-Fi"). */
    val status: StateFlow<BridgeStatus> =
        cast.flatMapLatest { it?.status ?: flowOf(BridgeStatus.OFF) }.stateIn(scope, SharingStarted.Eagerly, BridgeStatus.OFF)

    /** True while a bridge exists (BridgeStatus.running is `bridge != null`); false with the service up but waiting for Wi-Fi. */
    val bridgeUp: StateFlow<Boolean> = status.map { it.running }.stateIn(scope, SharingStarted.Eagerly, false)

    /**
     * GUI-03: Hide from a card's menu, the only way to take a card off the list. The running bridge drops the speaker as a cast
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
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // the speaker simply stays where it is
        }
    }

    /** GUI-03: the hidden speakers; the store lives with the process, so the list is right while the bridge is off too. */
    val hidden: StateFlow<List<HiddenSpeaker>> = SpeakerStores.of(context).hidden.entries

    /**
     * GUI-03: Restore. Through the running bridge when there is one, which also searches for the speaker at once; with the
     * bridge off straight on the store, and the bridge's next start finds the speaker. Fire-and-forget.
     */
    fun unhide(udn: String): Job = scope.launch {
        try {
            val bridge = cast.value?.currentBridge()
            if (bridge == null || !bridge.unhide(udn)) SpeakerStores.of(appContext).hidden.remove(udn)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // the speaker stays hidden
        }
    }

    /** GUI-04, D-07: the speakers added by address (they can be hidden, not removed); the store lives with the process, so the list is right while the bridge is off too. */
    val manual: StateFlow<List<ManualSpeaker>> = SpeakerStores.of(context).manual.entries

    /** GUI rename: the names the author gave the renderers, by UDN; the store lives with the process, so it is readable while the bridge is off too. */
    val names: StateFlow<Map<String, String>> = SpeakerStores.of(context).names.entries

    /**
     * GUI rename: names the renderer [udn] [name], or resets it to its own name when [name] is null. Matched by UDN. Through the
     * running bridge when there is one (the card and the DIAL friendly name change at once); with the bridge off straight on the
     * store, and the bridge's next start uses the name. An unusable name changes nothing. Fire-and-forget.
     */
    fun rename(udn: String, name: String?): Job = scope.launch {
        try {
            val bridge = cast.value?.currentBridge()
            if (bridge == null || !bridge.rename(udn, name)) {
                val store = SpeakerStores.of(appContext).names
                if (name == null) store.remove(udn) else store.set(udn, name)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // the renderer keeps its name
        }
    }

    /**
     * GUI-04: Add speaker by the IPv4 address in [text]. Null means the text is not a private LAN IPv4 address and nothing was
     * sent (T-07-29); with no bridge the answer is [ManualAddResult.NotUsable] saying to switch it on, or that the bridge is waiting for Wi-Fi when the service is up, because the probe runs
     * from the bridge's SSDP client. Otherwise the running bridge probes the address and says what came of it. A failure is
     * reported as [ManualAddResult.NotUsable]; cancellation propagates. The bridge saves a speaker that was added (plan 07-13).
     */
    suspend fun addManual(text: String): ManualAddResult? {
        val ip = parseManualIp(text) ?: return null
        val bridge = cast.value?.currentBridge() ?: return ManualAddResult.NotUsable(addRefusal(serviceRunning.value, false) ?: BRIDGE_OFF_ADD)
        return try {
            withContext(Dispatchers.Default) { bridge.addManual(ip) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ManualAddResult.NotUsable("Could not add the renderer: ${e.javaClass.simpleName}")
        }
    }

    /** Fire-and-forget [controlNow] for a card tap; it never throws into the UI. */
    fun control(udn: String, action: SpeakerAction): Job = scope.launch { controlNow(udn, action) }

    /**
     * Runs a card [action] on the speaker [udn] of the running bridge and says whether the speaker did it. A command that
     * takes over [CONTROL_TIMEOUT_MS], a missing speaker or bridge, and any failure all answer false: a card tap must never
     * crash the app (plan 07-08). Cancellation still propagates. There is no volume action (T-02-36).
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
            false
        }

    companion object {
        private const val CONTROL_TIMEOUT_MS = 20_000L

        /** What the add dialog and the Add button say while the bridge is off. */
        const val BRIDGE_OFF_ADD = "Switch the bridge on to add a renderer"

        /** What they say while the service runs but has no bridge yet (no Wi-Fi, a failed start, a restart). */
        const val BRIDGE_WAITING_ADD = "The bridge is waiting for Wi-Fi; add the renderer once it is running"

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
