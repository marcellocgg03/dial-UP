package dev.t2u.app

import android.content.Context
import android.os.PowerManager

/** Why the service was started; the wire name goes into the `service-start` and `service-gate` log events. */
enum class StartReason(val wire: String) {
    /** The author opened the app. */
    OPEN("open"),

    /** BOOT_COMPLETED after a phone reboot (D-03). */
    BOOT("boot"),

    /** Android restarted the process after a kill; the service gets a null intent (D-01). */
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
 * Whether the bridge may run: only with the battery-optimisation exemption (D-12) and only while the
 * author's switch is on (D-04). The reason never opens the gate; it is passed so the decision and its
 * log line name the same reason. A sticky restart after the switch went off is denied here (RESEARCH Pitfall 1).
 */
@Suppress("UNUSED_PARAMETER")
fun gateDecision(exempt: Boolean, enabled: Boolean, reason: StartReason): Boolean = exempt && enabled

/** The Android side of the gate. */
object BatteryGate {
    fun isOpen(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true
}

/** One step of the open flow in [MainActivity] (D-12 to D-14). */
enum class LaunchStep { ASK_NOTIFICATIONS, ASK_EXEMPTION, START_SERVICE, FINISH_WITHOUT_SERVICE }

/**
 * The steps an open runs. Notifications are asked once (the first-launch marker) and only on API 33+
 * when not yet granted. The exemption is asked on every open until it is granted; once it is, the
 * service is started instead (D-13, D-14).
 */
fun launchSteps(markerExists: Boolean, sdk: Int, notificationsGranted: Boolean, exempt: Boolean): List<LaunchStep> =
    buildList {
        if (!markerExists && sdk >= 33 && !notificationsGranted) add(LaunchStep.ASK_NOTIFICATIONS)
        add(if (exempt) LaunchStep.START_SERVICE else LaunchStep.ASK_EXEMPTION)
    }

/** After the system exemption dialog closes: the service starts only if the exemption is now held, whatever resultCode said (D-12). */
fun afterExemptionDialog(exempt: Boolean): LaunchStep =
    if (exempt) LaunchStep.START_SERVICE else LaunchStep.FINISH_WITHOUT_SERVICE
