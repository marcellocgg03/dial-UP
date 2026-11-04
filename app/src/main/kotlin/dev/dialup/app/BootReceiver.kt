package dev.dialup.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Starts the bridge after a phone reboot (D-03), through the same gate as every other start: the author's
 * switch must be on and the battery exemption held ([bootStartDecision]). Without them it writes one logcat
 * line and starts nothing; the author is asked for the exemption the next time the app is opened (D-15, no
 * hint notification).
 *
 * The switch is persisted (plan 07-04, D-04), and a Stop from the notification, adb or the app turns it off,
 * so a Stop followed by a reboot leaves the bridge off. This reverses the Phase 5 note that a Stop followed
 * by a reboot starts the bridge again (Phase 5 Open Question 4, UI-06). A force-stopped app gets
 * no BOOT_COMPLETED until it is opened (D-01 accepts this). The receiver is not exported and ignores every
 * action but BOOT_COMPLETED.
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
