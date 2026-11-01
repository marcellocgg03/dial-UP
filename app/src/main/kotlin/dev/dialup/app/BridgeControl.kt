// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.dialup.app

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import dev.dialup.app.model.SingleFlight
import dev.dialup.app.model.restartSequence
import dev.dialup.app.model.shouldStartService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The only code that starts or stops the bridge service from the UI (plan 07-04). The switch is one persisted
 * flag ([AppSettingsData.bridgeEnabled]); every start path reads it (the service bootstrap, [BootReceiver] and
 * the app's own start), so off stays off until [setEnabled] turns it on again (D-04).
 */
object BridgeControl {
    /**
     * Persists the switch and applies it. On: starts the service when the battery exemption is held and it is
     * not already running. Off: stops the running service, which stops discovery, DIAL, the media server,
     * polling and the locks with it (D-02). [source] (ui, restart) goes into the `settings` event.
     */
    fun setEnabled(context: Context, on: Boolean, source: String) {
        val app = context.applicationContext
        AppSettings.of(app).update { it.copy(bridgeEnabled = on) }
        ServiceHub.service?.evidence?.event("settings", mapOf("bridgeEnabled" to on, "source" to source))
        if (on) {
            if (shouldStartService(true, BatteryGate.isOpen(app), ServiceHub.service != null)) {
                // reason extra unset: the service reads this as an open (the author turned it on)
                ContextCompat.startForegroundService(app, Intent(app, BridgeService::class.java))
            }
        } else {
            ServiceHub.service?.stopSelf()
        }
    }

    private val restartFlight = SingleFlight<Boolean>()

    /**
     * Restarts the bridge: off, wait up to 15 s for the old service to be gone and for the process-wide stop of plan 07-20,
     * then on. Only call it after [dev.dialup.app.model.restartStep] said RESTART_NOW or the author confirmed (plan 07-10's
     * RestartPrompt does that): a restart ends playback like a switch-off does (D-03). The off-then-on order avoids starting
     * the service while the old instance is still being destroyed. The sequence runs on [BridgeLifecycle.scope], so closing
     * or recreating the Activity mid-restart cannot cancel it, and its switch-on is non-cancellable (CR-03). A second restart
     * while one runs joins it. Returns false when the old service did not stop in time.
     */
    suspend fun restart(context: Context): Boolean =
        restartFlight.launch(BridgeLifecycle.scope) {
            restartSequence(
                setEnabled = { setEnabled(context, it, "restart") },
                awaitStopped = { awaitServiceGone() },
            )
        }.await()

    /** True when the service is gone and no stop is in flight, each within [STOP_TIMEOUT_MS]. */
    private suspend fun awaitServiceGone(): Boolean =
        withTimeoutOrNull(STOP_TIMEOUT_MS) { ServiceHub.serviceFlow.first { it == null } } != null &&
            BridgeLifecycle.awaitIdle(STOP_TIMEOUT_MS)

    private const val STOP_TIMEOUT_MS = 15_000L
}
