package dev.dialup.app

// Plan 05-11: the idle-time heartbeat of the bridge's housekeeping (RESEARCH Pattern 6). Coroutine timers stall when the
// CPU sleeps, and since plan 05-09 the wake lock is held only while casting, so one AlarmManager alarm wakes the phone
// every few minutes, runs CastFeature.housekeeping and sets the next alarm. GPL-3.0-only.

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.os.SystemClock
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Schedules the housekeeping alarm. The alarm is inexact (`setAndAllowWhileIdle`, which needs no permission), explicit
 * (it targets [HousekeepingReceiver] only) and re-armed by the receiver after every run; [CastFeature.stop] cancels it.
 *
 * Known limit, the timing bounds (plan 05-11, measured by plans 05-12 and 05-13):
 *  - Phone awake (screen on, or casting with the wake lock of plan 05-09): housekeeping every 5 minutes from the
 *    in-process timer, the retry check every 10 s; a failed setup is retried within 5 min 10 s (DISC-05 "within about
 *    five minutes").
 *  - Doze (nothing casting, no wake lock): idle housekeeping runs are at most nine minutes ([IDLE_HOUSEKEEPING_BOUND_MS])
 *    apart. Basis: the Android Doze documentation says setAndAllowWhileIdle alarms fire no more than once per nine
 *    minutes per app; AOSP AlarmManagerService gives an app on the battery-optimisation allowlist (the service only
 *    runs with that exemption, D-12) FLAG_ALLOW_WHILE_IDLE_UNRESTRICTED and leaves the inexact window of 75 % of the
 *    delay (5:00 to 8:45 after the alarm was set). Derived: re-announce (LAN mode) and self-probe at most 9 minutes
 *    apart, a failed-setup retry at most 5 + 9 = 14 minutes after the failure, a revival's first attempt at most
 *    9 minutes after the terminate and later attempts at most 14 minutes apart, a yt-dlp update at most 9 minutes
 *    after its deadline.
 *  - Samsung One UI may stretch alarms further; the `housekeeping-alarm` event carries `lateMs`, which shows the real
 *    idle cadence in the soak log.
 */
object HousekeepingAlarm {
    /** Delay of each alarm; the in-process timer of the bridge uses the same five minutes. */
    const val HOUSEKEEPING_ALARM_MS: Long = 300_000L

    /** The documented Doze worst case between two idle housekeeping runs; soak-report's default bound is the same. */
    const val IDLE_HOUSEKEEPING_BOUND_MS: Long = 540_000L

    const val ACTION_HOUSEKEEPING: String = "dev.dialup.HOUSEKEEPING"
    const val EXTRA_DUE: String = "due"

    private const val REQUEST_CODE = 3

    fun schedule(context: Context) {
        val due = SystemClock.elapsedRealtime() + HOUSEKEEPING_ALARM_MS
        val alarms = context.getSystemService(AlarmManager::class.java)
        alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, due, pendingIntent(context, due))
    }

    fun cancel(context: Context) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        alarms.cancel(pendingIntent(context, 0L))
    }

    private fun pendingIntent(context: Context, due: Long): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, HousekeepingReceiver::class.java).setAction(ACTION_HOUSEKEEPING).putExtra(EXTRA_DUE, due),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}

/**
 * Receives the housekeeping alarm. Not exported: the alarm's PendingIntent is explicit and immutable (T-05-29). Holds a
 * partial wake lock with a 60 s timeout, runs the cast feature's housekeeping bounded to 45 s, sets the next alarm and
 * releases the lock and finishes the broadcast when the work ends or the scope is cancelled. Without a running service
 * nothing runs and nothing is rescheduled.
 */
class HousekeepingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != HousekeepingAlarm.ACTION_HOUSEKEEPING) return
        val service = ServiceHub.service ?: return
        val cast = service.features.filterIsInstance<CastFeature>().firstOrNull() ?: return
        val due = intent.getLongExtra(HousekeepingAlarm.EXTRA_DUE, 0L)
        val app = context.applicationContext
        val pending = goAsync()
        val power = app.getSystemService(PowerManager::class.java)
        val lock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "t2u:tick")
        lock.setReferenceCounted(false)
        lock.acquire(WAKE_LOCK_TIMEOUT_MS)
        // runs once, whatever the scope does: a cancelled scope never starts the body below (WR-04)
        val finish = {
            runCatching { if (lock.isHeld) lock.release() }
            runCatching { pending.finish() }
            Unit
        }
        service.scope.launchFinishing(finish) {
            try {
                val late = if (due > 0L) SystemClock.elapsedRealtime() - due else -1L
                service.evidence.event("housekeeping-alarm", mapOf("lateMs" to late))
                withTimeoutOrNull(WORK_TIMEOUT_MS) { cast.housekeeping("alarm") }
            } catch (t: Throwable) {
                runCatching {
                    service.evidence.event(
                        "feature-error",
                        mapOf("where" to "housekeeping-alarm", "error" to t.javaClass.name, "message" to t.message),
                    )
                }
            } finally {
                runCatching { if (ServiceHub.service === service && service.scope.isActive) HousekeepingAlarm.schedule(app) }
            }
        }
    }

    private companion object {
        const val WAKE_LOCK_TIMEOUT_MS = 60_000L
        const val WORK_TIMEOUT_MS = 45_000L
    }
}
