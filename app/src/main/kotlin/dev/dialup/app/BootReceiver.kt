package dev.dialup.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import dev.dialup.app.model.startWanted
import kotlinx.coroutines.CancellationException

/**
 * Starts the bridge after a phone reboot, through the same gate as every other start: the author's
 * switch must be on and the battery exemption held ([bootStartDecision]). Without them it writes one logcat
 * line and starts nothing; the author is asked for the exemption the next time the app is opened (no
 * hint notification).
 *
 * The switch is persisted, and a Stop from the notification, adb or the app turns it off,
 * so a Stop followed by a reboot leaves the bridge off. A force-stopped app gets
 * no BOOT_COMPLETED until it is opened, which is accepted. The receiver is not exported and ignores every
 * action but BOOT_COMPLETED.
 *
 * BOOT_COMPLETED often comes before Wi-Fi associates. Without a bindable network (no Wi-Fi client address, no hotspot) the
 * service would be stopped at once, so the receiver arms [NetworkResume] instead and the bridge starts when Wi-Fi comes.
 *
 * The decision runs off the main thread: `onReceive` takes `goAsync()` and decides on [BridgeLifecycle.scope], finishing the
 * broadcast exactly once.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        // The decision reads settings and the network (binder and interface work): off the main thread, with the broadcast kept alive.
        val pending = goAsync()
        val app = context.applicationContext
        BridgeLifecycle.scope.launchFinishing({ pending.finish() }) {
            val switchOn = AppSettings.of(app).data.value.bridgeEnabled
            if (!switchOn) {
                Log.i(TAG, "boot: the bridge is switched off in the app; not started")
                return@launchFinishing
            }
            // a trace left by a process that died in the middle of a restart or update is cleared by this first touch
            val enabled = startWanted(switchOn, MaintenanceMarker.isActive(app))
            if (!enabled) {
                Log.i(TAG, "boot: a restart or update is in progress; the bridge starts when it ends")
                return@launchFinishing
            }
            val exempt = BatteryGate.isOpen(app)
            if (!bootStartDecision(enabled, exempt)) {
                Log.i(TAG, "boot: the bridge was not started because the battery exemption is missing; opening the app asks for it")
                return@launchFinishing
            }
            if (!hasBindableNetwork(app)) {
                NetworkResume.arm(app)
                Log.i(TAG, "boot: no Wi-Fi yet; the bridge starts when it connects")
                return@launchFinishing
            }
            try {
                ContextCompat.startForegroundService(
                    app,
                    Intent(app, BridgeService::class.java).putExtra(EXTRA_START_REASON, StartReason.BOOT.wire),
                )
                Log.i(TAG, "boot: started the bridge service")
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                Log.w(TAG, "boot: the system refused to start the bridge service", t)
            }
        }
    }

    private companion object {
        const val TAG = "T2U"
    }
}
