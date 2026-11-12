// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.dialup.app

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log

/**
 * The service stops itself after Wi-Fi has been absent beyond the short rebind window; the switch in
 * the app stays on. This object is what brings it back. [arm] registers a Wi-Fi network callback with a PendingIntent, which
 * the system keeps delivering even after the app process was killed; [NetworkResumeReceiver] then starts the service once a
 * Wi-Fi client address exists, the switch is on and the battery exemption is held. The start paths that find no network
 * (app open, boot, the switch) arm it too instead of starting a bridge that would stop at once. A start the system refuses is
 * only logged: the bridge stays off with the switch on until the author opens the app or flips the switch.
 *
 * The hotspot case is not covered by the callback: the phone's own hotspot is not a Wi-Fi network for it, but the bridge
 * counts it as a network, so it does not stop while the hotspot is on.
 */
object NetworkResume {
    private const val PREFS = "t2u_net_resume"
    private const val KEY_ARMED = "armed"
    private const val REQUEST_CODE = 5
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

    /** Stops listening. Safe when not armed. */
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
    }
}

/** Receives the Wi-Fi network events of [NetworkResume.arm]; acts only while armed, with the switch on, and with an address the bridge can bind to ([hasBindableNetwork], the rule every start gate uses). */
class NetworkResumeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!NetworkResume.isArmed(context)) return
        if (!hasBindableNetwork(context)) return
        BridgeControl.resumeAfterNetwork(context)
    }
}
