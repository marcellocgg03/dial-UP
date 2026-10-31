package dev.t2u.app

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.ContextCompat
import dev.t2u.app.model.shouldStartService
import dev.t2u.app.ui.MainScreen
import java.io.File

/**
 * The app's screen (phase 7, D-01): opening the app always shows the Compose UI and never closes it.
 * Until plan 07-05 replaces them by inline banners, the launch steps still run: opening the app asks for POST_NOTIFICATIONS once (first launch,
 * marker file in filesDir) and for the battery-optimisation exemption on every open until it is
 * granted (D-13). The bridge needs the exemption (D-12): after the dialog the exemption is read
 * again and the service is started only when it is held; a refusal shows nothing else and starts
 * nothing. Once the exemption is granted, later opens start the service when it is not running. The steps come from the pure [launchSteps] and [afterExemptionDialog].
 */
class MainActivity : ComponentActivity() {
    private val marker: File get() = File(filesDir, FIRST_LAUNCH_MARKER)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { MainScreen(AppModel.of(applicationContext)) }
        val steps = launchSteps(
            markerExists = marker.exists(),
            sdk = Build.VERSION.SDK_INT,
            notificationsGranted = notificationsGranted(),
            exempt = BatteryGate.isOpen(this),
        )
        Log.i(TAG, "open: steps=$steps")
        run(steps.first())
    }

    private fun run(step: LaunchStep) {
        when (step) {
            LaunchStep.ASK_NOTIFICATIONS -> {
                Log.i(TAG, "requesting POST_NOTIFICATIONS")
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS)
            }
            LaunchStep.ASK_EXEMPTION -> askExemption()
            LaunchStep.START_SERVICE -> {
                writeMarker()
                startServiceIfNeeded()
            }
            LaunchStep.FINISH_WITHOUT_SERVICE -> {
                // the marker is written so notifications are not asked again (D-12)
                writeMarker()
                Log.i(TAG, "battery exemption missing: the bridge was not started")
            }
        }
    }

    private fun notificationsGranted(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_NOTIFICATIONS) return
        Log.i(TAG, "POST_NOTIFICATIONS answered granted=${grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED}")
        // the answer never blocks the next step: ask for the exemption, or start if it is already held
        run(if (BatteryGate.isOpen(this)) LaunchStep.START_SERVICE else LaunchStep.ASK_EXEMPTION)
    }

    /** The battery-optimisation exemption through the system dialog. */
    @android.annotation.SuppressLint("BatteryLife")
    private fun askExemption() {
        Log.i(TAG, "requesting the battery-optimisation exemption")
        try {
            startActivityForResult(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")),
                REQ_BATTERY,
            )
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "no activity for the battery exemption intent, not starting the bridge", e)
            run(LaunchStep.FINISH_WITHOUT_SERVICE)
        }
    }

    @Deprecated("Deprecated in Java; kept until plan 07-05 moves to the ActivityResult API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_BATTERY) return
        Log.i(TAG, "battery exemption dialog closed, resultCode=$resultCode")
        run(afterExemptionDialog(BatteryGate.isOpen(this)))
    }

    private fun writeMarker() {
        try {
            marker.writeText("done\n")
        } catch (t: Throwable) {
            Log.w(TAG, "could not write the first-launch marker", t)
        }
    }

    /** Opening the app never turns a switched-off bridge on (D-04): the start goes through [shouldStartService]. */
    private fun startServiceIfNeeded() {
        val enabled = AppSettings.of(this).data.value.bridgeEnabled
        if (shouldStartService(enabled, BatteryGate.isOpen(this), ServiceHub.service != null)) {
            ContextCompat.startForegroundService(this, Intent(this, BridgeService::class.java))
        } else if (!enabled) {
            Log.i(TAG, "open: the bridge is switched off in the app; not started")
        }
    }

    private companion object {
        const val TAG = "T2U"
        const val FIRST_LAUNCH_MARKER = "first-launch-done"
        const val REQ_NOTIFICATIONS = 1
        const val REQ_BATTERY = 2
    }
}
