package dev.dialup.app

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * Owns the bridge's one notification for [service]: the channel, the first foreground start, and the show, update and
 * removal that follow the cast count ([shouldShowCastNotification]). It keeps whether the service is in the foreground and the
 * text last posted, so the notification is re-posted only when the text changes. [events] is the event log sink
 * (the log does not exist yet when the first foreground start runs, so the caller passes a lookup, not the log).
 *
 * Not thread safe on its own: [startForeground] runs on the main thread in onCreate, [update] on the service scope afterwards,
 * exactly as the code did inside the service.
 */
internal class CastNotifier(
    private val service: Service,
    private val events: (kind: String, fields: Map<String, Any?>) -> Unit,
) {
    private var foregroundActive = false
    private var lastNotifiedText: String? = notificationText(CastCount(0, 0))

    /** Creates the channel and starts the service in the foreground with the zero-cast notification; throws if Android refuses. */
    fun startForeground() {
        createChannel()
        ServiceCompat.startForeground(
            service,
            NOTIFICATION_ID,
            buildNotification(CastCount(0, 0), ongoing = true),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
        foregroundActive = true
    }

    /** Removes the notification (the service is going away). */
    fun cancel() {
        service.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }

    private fun createChannel() {
        val nm = service.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Bridge Status", NotificationManager.IMPORTANCE_LOW))
    }

    private fun buildNotification(count: CastCount, ongoing: Boolean = foregroundActive): Notification {
        val stop = PendingIntent.getService(
            service,
            0,
            Intent(service, BridgeService::class.java).setAction(BridgeService.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(service, CHANNEL_ID)
            .setContentIntent(openAppIntent(service))
            .setContentTitle("DialUP")
            .setContentText(notificationText(count))
            .setSmallIcon(R.drawable.ic_stat_dialup)
            .setOngoing(ongoing)
            .addAction(0, "Stop", stop)
            .build()
    }

    /** Shows, updates or removes the notification for [count]; [wifiAvailable] false hides it ([shouldShowCastNotification]). */
    fun update(count: CastCount, wifiAvailable: Boolean) {
        val manager = service.getSystemService(NotificationManager::class.java)
        if (!shouldShowCastNotification(count, wifiAvailable)) {
            if (foregroundActive) {
                ServiceCompat.stopForeground(service, ServiceCompat.STOP_FOREGROUND_REMOVE)
                foregroundActive = false
            } else {
                manager.cancel(NOTIFICATION_ID)
            }
            lastNotifiedText = null
            return
        }

        val text = notificationText(count)
        if (!foregroundActive) {
            val promotionError = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    startCastForeground(count)
                    null
                } catch (e: ForegroundServiceStartNotAllowedException) {
                    e
                } catch (e: SecurityException) {
                    e
                }
            } else {
                try {
                    startCastForeground(count)
                    null
                } catch (e: SecurityException) {
                    e
                }
            }
            if (promotionError == null) {
                foregroundActive = true
                lastNotifiedText = text
                return
            }
            events(
                "feature-error",
                mapOf("where" to "promote-foreground", "error" to promotionError.javaClass.name, "message" to promotionError.message),
            )
        }

        if (text != lastNotifiedText) {
            lastNotifiedText = text
            manager.notify(NOTIFICATION_ID, buildNotification(count))
        }
    }

    private fun startCastForeground(count: CastCount) {
        ServiceCompat.startForeground(
            service,
            NOTIFICATION_ID,
            buildNotification(count, ongoing = true),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
    }

    companion object {
        /** Opens DialUP; a tap on the notification does not dismiss it (the bridge notification is ongoing). */
        fun openAppIntent(service: Service): PendingIntent = PendingIntent.getActivity(
            service,
            1,
            Intent(service, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        const val CHANNEL_ID = "dialup"
        const val NOTIFICATION_ID = 1
    }
}
