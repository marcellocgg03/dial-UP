package dev.dialup.app

import android.content.Context
import android.os.PowerManager

/** Why the service was started; the wire name goes into the `service-start` and `service-gate` log events. */
enum class StartReason(val wire: String) {
    /** The author opened the app. */
    OPEN("open"),

    /** BOOT_COMPLETED after a phone reboot. */
    BOOT("boot"),

    /** Android restarted the process after a kill; the service gets a null intent. */
    STICKY("sticky"),
}

/** Intent extra naming the start reason; only [BootReceiver] sets it. It selects a log label, never the gate. */
const val EXTRA_START_REASON = "reason"

/**
 * The start reason from the first onStartCommand: a null intent is a sticky restart, the boot extra
 * is a boot start, anything else is the author opening the app. [action] is accepted so callers
 * pass the whole intent shape; it does not change the answer.
 */
@Suppress("UNUSED_PARAMETER")
fun startReasonOf(intentIsNull: Boolean, action: String?, reasonExtra: String?): StartReason =
    when {
        intentIsNull -> StartReason.STICKY
        reasonExtra == StartReason.BOOT.wire -> StartReason.BOOT
        else -> StartReason.OPEN
    }

/**
 * Whether the bridge may run: only with the battery-optimisation exemption and only while the
 * author's switch is on. The reason never opens the gate; it is passed so the decision and its
 * log line name the same reason. A sticky restart after the switch went off is denied here.
 */
@Suppress("UNUSED_PARAMETER")
fun gateDecision(exempt: Boolean, enabled: Boolean, reason: StartReason): Boolean = exempt && enabled

/**
 * Whether BOOT_COMPLETED may start the bridge: the author's switch is on and the exemption is held. It is checked before the service is started, so the system never gets a foreground-service
 * start the service would immediately deny.
 */
fun bootStartDecision(enabled: Boolean, exempt: Boolean): Boolean = enabled && exempt

/** The Android side of the gate. */
object BatteryGate {
    fun isOpen(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true
}
