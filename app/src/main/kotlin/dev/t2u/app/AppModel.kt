// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for Tube2U[PnP] for Android.
package dev.t2u.app

import android.content.Context
import dev.t2u.app.model.BridgeStatus
import dev.t2u.renderer.SpeakerUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

    /** The header's state: bridge up or not, the Wi-Fi address it runs on and the access mode (plan 07-04 words "Waiting for Wi-Fi"). */
    val status: StateFlow<BridgeStatus> =
        cast.flatMapLatest { it?.status ?: flowOf(BridgeStatus.OFF) }.stateIn(scope, SharingStarted.Eagerly, BridgeStatus.OFF)

    companion object {
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
