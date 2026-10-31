// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for Tube2U[PnP] for Android.
package dev.t2u.app

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import dev.t2u.app.model.shouldStartService

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
}
