package dev.t2u.app

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.File

/**
 * No GUI (decisions D-02, D-05). First launch: ask for POST_NOTIFICATIONS, then the
 * battery-optimisation exemption, start the foreground service and finish. A marker file in
 * filesDir records that the flow ran; later launches only start the service when it is not running.
 * Each step continues whatever the user answered, so a refusal never blocks the service.
 */
class MainActivity : Activity() {
    private val marker: File get() = File(filesDir, FIRST_LAUNCH_MARKER)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (marker.exists()) {
            Log.i(TAG, "later launch: no prompts")
            startServiceIfNeeded()
            finish()
            return
        }
        Log.i(TAG, "first launch: running the permission flow")
        stepNotifications()
    }

    /** Step 1: POST_NOTIFICATIONS on API 33+. */
    private fun stepNotifications() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.i(TAG, "step 1: requesting POST_NOTIFICATIONS")
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS)
            return
        }
        Log.i(TAG, "step 1: POST_NOTIFICATIONS already granted or not needed")
        stepBatteryExemption()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_NOTIFICATIONS) return
        Log.i(TAG, "step 1: POST_NOTIFICATIONS answered granted=${grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED}")
        stepBatteryExemption()
    }

    /** Step 2: the battery-optimisation exemption through the system dialog. */
    @android.annotation.SuppressLint("BatteryLife")
    private fun stepBatteryExemption() {
        val power = getSystemService(PowerManager::class.java)
        if (power.isIgnoringBatteryOptimizations(packageName)) {
            Log.i(TAG, "step 2: battery exemption already granted")
            stepFinish()
            return
        }
        Log.i(TAG, "step 2: requesting the battery-optimisation exemption")
        try {
            startActivityForResult(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")),
                REQ_BATTERY,
            )
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "step 2: no activity for the battery exemption intent, continuing", e)
            stepFinish()
        }
    }

    @Deprecated("Deprecated in Java; a bare Activity has no ActivityResult API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_BATTERY) return
        Log.i(TAG, "step 2: battery exemption dialog closed, resultCode=$resultCode")
        stepFinish()
    }

    /** Step 3: write the marker, start the service, finish. */
    private fun stepFinish() {
        try {
            marker.writeText("done\n")
        } catch (t: Throwable) {
            Log.w(TAG, "step 3: could not write the first-launch marker", t)
        }
        startServiceIfNeeded()
        Log.i(TAG, "step 3: first-launch flow done")
        finish()
    }

    private fun startServiceIfNeeded() {
        if (ServiceHub.service == null) {
            ContextCompat.startForegroundService(this, Intent(this, BridgeService::class.java))
        }
    }

    private companion object {
        const val TAG = "T2U"
        const val FIRST_LAUNCH_MARKER = "first-launch-done"
        const val REQ_NOTIFICATIONS = 1
        const val REQ_BATTERY = 2
    }
}
