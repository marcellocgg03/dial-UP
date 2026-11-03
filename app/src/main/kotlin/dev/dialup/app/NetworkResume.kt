// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.dialup.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import androidx.core.app.NotificationCompat
import java.net.Inet4Address

/**
 * The bridge pauses itself when the phone has had no Wi-Fi or hotspot address for [NO_NETWORK_GRACE_MS]: the service stops,
 * so the foreground notification and its status bar icon go away, while the switch in the app stays on (nothing is persisted
 * off). This object is what brings it back. [arm] registers a Wi-Fi network callback with a PendingIntent, which the system
 * keeps delivering even after the app process was killed; [NetworkResumeReceiver] then starts the service again once a Wi-Fi
 * client address exists. Opening the app also starts the bridge (MainActivity), whatever the network.
 *
 * The hotspot case is not covered: the phone's own hotspot is not a Wi-Fi network for the callback, but the bridge counts it
 * as a network, so it never pauses while the hotspot is on.
 */
object NetworkResume {
    private const val PREFS = "t2u_net_resume"
    private const val KEY_ARMED = "armed"
    private const val REQUEST_CODE = 5
    private const val RESUME_CHANNEL = "dialup-resume"
    private const val RESUME_NOTIFICATION_ID = 2
    private const val TAG = "T2U"

    private fun pendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, NetworkResumeReceiver::class.java),
            // the system adds the network extras to the intent, so it has to be mutable
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    fun isArmed(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ARMED, false)

    private fun setArmed(context: Context, armed: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ARMED, armed).apply()
    }

    /** Starts listening for a Wi-Fi network. Safe to call twice. */
    fun arm(context: Context) {
        val app = context.applicationContext
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return
        try {
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            cm.registerNetworkCallback(request, pendingIntent(app))
            setArmed(app, true)
        } catch (t: Throwable) {
            Log.w(TAG, "network resume: could not register the callback", t)
        }
    }

    /** Stops listening and removes the tap-to-resume notification. Safe when not armed. */
    fun disarm(context: Context) {
        val app = context.applicationContext
        if (isArmed(app)) {
            try {
                app.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(pendingIntent(app))
            } catch (t: Throwable) {
                Log.w(TAG, "network resume: could not unregister the callback", t)
            }
            setArmed(app, false)
        }
        app.getSystemService(NotificationManager::class.java)?.cancel(RESUME_NOTIFICATION_ID)
    }

    /** True when a Wi-Fi client network with an IPv4 address exists now. */
    fun hasWifiAddress(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        @Suppress("DEPRECATION")
        for (network in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(network) ?: continue
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
            val props = cm.getLinkProperties(network) ?: continue
            if (props.linkAddresses.any { it.address is Inet4Address }) return true
        }
        return false
    }

    /** Shown when the system refuses to start the service from the background: one tap opens the app, which starts the bridge. */
    fun postTapToResume(context: Context) {
        val app = context.applicationContext
        val nm = app.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(RESUME_CHANNEL, "DialUP, resume", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(
            app,
            REQUEST_CODE,
            Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(app, RESUME_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_dialup)
            .setContentTitle("DialUP")
            .setContentText("Wi-Fi is back. Tap to turn the bridge on again.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        nm.notify(RESUME_NOTIFICATION_ID, notification)
    }
}

/** Receives the Wi-Fi network events of [NetworkResume.arm]; acts only while armed, with the switch on, and with a Wi-Fi address. */
class NetworkResumeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!NetworkResume.isArmed(context)) return
        if (!NetworkResume.hasWifiAddress(context)) return
        BridgeControl.resumeAfterNetwork(context)
    }
}
