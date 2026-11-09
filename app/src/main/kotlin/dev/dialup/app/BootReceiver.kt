package dev.dialup.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

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
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val enabled = AppSettings.of(context).data.value.bridgeEnabled
        if (!enabled) {
            Log.i(TAG, "boot: the bridge is switched off in the app; not started")
            return
        }
        val exempt = BatteryGate.isOpen(context)
        if (!bootStartDecision(enabled, exempt)) {
            Log.i(TAG, "boot: the bridge was not started because the battery exemption is missing; opening the app asks for it")
            return
        }
        if (!hasBindableNetwork(context)) {
            NetworkResume.arm(context)
            Log.i(TAG, "boot: no Wi-Fi yet; the bridge starts when it connects")
            return
        }
        try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, BridgeService::class.java).putExtra(EXTRA_START_REASON, StartReason.BOOT.wire),
            )
            Log.i(TAG, "boot: started the bridge service")
        } catch (t: Throwable) {
            Log.w(TAG, "boot: the system refused to start the bridge service", t)
        }
    }

    private companion object {
        const val TAG = "T2U"
    }
}
