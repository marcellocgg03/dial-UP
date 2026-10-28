package dev.t2u.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Starts the bridge after a phone reboot (D-03), through the same battery gate as every other start
 * (D-04). Without the exemption it writes one logcat line and starts nothing; the author is asked
 * for the exemption the next time the app is opened (D-15, no hint notification).
 *
 * No "stopped by user" flag is stored, so a notification Stop followed by a reboot starts the bridge
 * again (Open Question 4). A force-stopped app gets no BOOT_COMPLETED until it is opened (D-01
 * accepts this). The receiver is not exported and ignores every action but BOOT_COMPLETED.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!BatteryGate.isOpen(context)) {
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
