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

/** The text of the notice for a cast that [remote] started on [speaker]. */
internal fun guestNoticeText(remote: String, speaker: String): String = "$remote started a cast on $speaker"

/**
 * The notification id for [remote]: the same address always gets the same id, so a second launch from it replaces its
 * notification (and alerts again) instead of piling up. Kept clear of the bridge notification's id.
 */
internal fun guestNoticeId(remote: String): Int = GuestNotifier.ID_BASE + (remote.hashCode() and 0xFFFF)

/**
 * Tells the author that another device on the LAN started a cast (LAN server mode). Its own channel, so it can be muted on
 * its own; a tap opens DialUP and dismisses the notice.
 */
internal class GuestNotifier(private val context: Context) {
    fun notify(speaker: String, remote: String) {
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
                .setContentText(guestNoticeText(remote, speaker))
                .setContentIntent(open)
                .setAutoCancel(true)
                .setOnlyAlertOnce(false)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .build()
            val manager = NotificationManagerCompat.from(context)
            if (manager.areNotificationsEnabled()) manager.notify(guestNoticeId(remote), notification)
        } catch (_: Exception) {
            // a missing notification permission or a refused channel must never touch the cast
        }
    }

    companion object {
        const val CHANNEL_ID = "dialup-guests"
        const val ID_BASE = 100
    }
}
