// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.dialup.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/** This phone's device name as the YouTube lounge reports it for the phone's own sender: manufacturer, then model. */
internal class OwnDevice(private val manufacturer: String, private val model: String) {
    /** True for a sender name that is this phone: the exact "manufacturer model" (any case), or any name containing the model. */
    fun isOwnName(senderName: String): Boolean {
        val name = senderName.trim()
        if (name.isEmpty()) return false
        if (name.equals("$manufacturer $model".trim(), ignoreCase = true)) return true
        return model.isNotBlank() && name.contains(model.trim(), ignoreCase = true)
    }
}

/** The text of the notice for a cast that the device named [senderName] started on [speaker]. */
internal fun guestNoticeText(senderName: String, speaker: String): String = "$senderName started a cast on $speaker"

/** A notice to show: its [text], and whether another device's address was seen on the LAN lately ([seenOnLan]) or only the name told. */
internal data class GuestNotice(val text: String, val seenOnLan: Boolean)

/**
 * The notice for a lounge sender that connected to [speaker], or null when there is none: the Settings switch
 * ([notifyGuests]) is off, the sender has no name, or its name is this phone's own ([own]). [recentGuest] is another device's LAN
 * address seen within the last minutes (null when none): it only records how the guest was recognised. When the discovery was
 * cached and no address was seen, a sender that is not this phone is still announced by its name. The one place the decision
 * is made, so a test can reach it without a Context.
 */
internal fun guestNoticeFor(notifyGuests: Boolean, senderName: String, speaker: String, own: OwnDevice, recentGuest: String?): GuestNotice? {
    if (!notifyGuests || senderName.isBlank() || own.isOwnName(senderName)) return null
    return GuestNotice(guestNoticeText(senderName.trim(), speaker), seenOnLan = recentGuest != null)
}

/**
 * The notification id for the device named [senderName]: the same name always gets the same id, so a second cast from it
 * replaces its notification (without alerting again) instead of piling up. Kept clear of the bridge notification's id.
 */
internal fun guestNoticeId(senderName: String): Int = GuestNotifier.ID_BASE + (senderName.trim().lowercase().hashCode() and 0xFFFF)

/** One notice per device name per [intervalMs]: a guest that drops and reconnects within seconds is announced once. */
internal class GuestNoticeGate(
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val intervalMs: Long = 30_000L,
    private val maxEntries: Int = 256,
) {
    private val last = HashMap<String, Long>()

    /** True when [senderName] has not been announced within the interval; records the announcement. */
    @Synchronized
    fun due(senderName: String): Boolean {
        val key = senderName.trim().lowercase()
        val now = clockMs()
        val before = last[key]
        if (before != null && now - before < intervalMs) return false
        if (last.size >= maxEntries) {
            last.values.removeAll { now - it >= intervalMs }
            if (last.size >= maxEntries) last.entries.minByOrNull { it.value }?.let { last.remove(it.key) }
        }
        last[key] = now
        return true
    }
}

/** The whole decision for one connecting sender: [guestNoticeFor] and then the once-per-name gate (spent only on a real notice). */
internal class GuestNoticeDecider(
    private val own: OwnDevice,
    private val notifyGuests: () -> Boolean,
    private val gate: GuestNoticeGate = GuestNoticeGate(),
) {
    fun decide(speaker: String, senderName: String, recentGuest: String?): GuestNotice? {
        val notice = guestNoticeFor(notifyGuests(), senderName, speaker, own, recentGuest) ?: return null
        return if (gate.due(senderName)) notice else null
    }
}

/**
 * Tells the author that another device started a cast through the YouTube lounge. Its own channel, so it can be muted on
 * its own; a tap opens DialUP and dismisses the notice.
 */
internal class GuestNotifier(private val context: Context) {
    fun notify(speaker: String, senderName: String) {
        try {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Other devices", NotificationManager.IMPORTANCE_DEFAULT))
            val open = PendingIntent.getActivity(
                context,
                2,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_dialup)
                .setContentTitle("Another device is casting")
                .setContentText(guestNoticeText(senderName, speaker))
                .setContentIntent(open)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .build()
            val manager = NotificationManagerCompat.from(context)
            if (manager.areNotificationsEnabled()) manager.notify(guestNoticeId(senderName), notification)
        } catch (_: Exception) {
            // a missing notification permission or a refused channel must never touch the cast
        }
    }

    companion object {
        const val CHANNEL_ID = "dialup-guests"
        const val ID_BASE = 100
    }
}
