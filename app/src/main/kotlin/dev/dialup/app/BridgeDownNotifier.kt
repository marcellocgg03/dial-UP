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
import kotlin.coroutines.cancellation.CancellationException

/** The text of the notice that the bridge gave up after [attempts] failed starts or moves in a row. */
internal fun bridgeDownText(attempts: Int): String {
    val times = if (attempts == 1) "1 time" else "$attempts times"
    return "The bridge failed $times in a row to start or to move to the new network, so it was switched off. " +
        "Open DialUP and switch it on to try again."
}

/**
 * The one notice that the bridge gave up and is not running. Id 2 is kept clear of the bridge notification (1), the guest
 * notices (100 and up) and the update notice (300). It sits on the Bridge Status channel, created here the same way
 * [CastNotifier] creates it, so the channel is not changed. A tap opens DialUP and dismisses the notice; a later successful
 * start cancels it. A missing notification permission or a refused channel never touches the bridge.
 */
internal class BridgeDownNotifier(private val context: Context) {
    fun notify(attempts: Int) {
        try {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CastNotifier.CHANNEL_ID, "Bridge Status", NotificationManager.IMPORTANCE_LOW))
            val open = PendingIntent.getActivity(
                context,
                REQUEST_OPEN,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val text = bridgeDownText(attempts)
            val notification = NotificationCompat.Builder(context, CastNotifier.CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_dialup)
                .setContentTitle("DialUP is not running")
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .build()
            val manager = NotificationManagerCompat.from(context)
            if (manager.areNotificationsEnabled()) manager.notify(ID, notification)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // a missing notification permission or a refused channel must never touch the bridge
        }
    }

    /** Removes the notice; nothing happens when it is not showing. */
    fun cancel() {
        try {
            NotificationManagerCompat.from(context).cancel(ID)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // never touches the bridge
        }
    }

    companion object {
        const val ID = 2

        /** Its own PendingIntent request code: 0 to 3 are taken by the bridge notification and its Stop, the guest notices and the update notice. */
        private const val REQUEST_OPEN = 4
    }
}
