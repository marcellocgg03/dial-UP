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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import dev.t2u.app.model.GrantAction
import dev.t2u.app.model.PermissionState
import dev.t2u.app.model.notificationGrantAction
import dev.t2u.app.model.shouldStartService
import dev.t2u.app.ui.MainScreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The app's screen (phase 7, D-01): opening the app always shows the Compose UI, never closes it and never
 * pops a system dialog by itself. Missing permissions show as inline banners whose Grant button launches the
 * request. The exemption and notification state is read again from the platform on every resume (the system
 * screens return no reliable result, T-07-44), and onResume starts the bridge when the switch is on and the
 * exemption is held (D-01); a switched-off or non-exempt bridge is never started from here (D-04, D-12).
 */
class MainActivity : ComponentActivity() {
    private val permissionState = MutableStateFlow(PermissionState(notificationsGranted = true, exempt = false))

    /** The result code of the system exemption screen is ignored; the state is re-read instead. */
    private val batteryLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        Log.i(TAG, "battery exemption screen closed")
        refreshPermissions()
    }

    /** Whether the permission dialog was shown at least once in this Activity; it decides REQUEST against OPEN_SETTINGS. */
    private var askedNotifications = false

    private val notificationsLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        Log.i(TAG, "POST_NOTIFICATIONS answered granted=$granted")
        refreshPermissions()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        refreshPermissions()
        setContent {
            MainScreen(
                model = AppModel.of(applicationContext),
                permissions = permissionState.asStateFlow(),
                onGrantNotifications = ::grantNotifications,
                onGrantBattery = ::grantBattery,
            )
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissions()
        startServiceIfNeeded()
    }

    private fun refreshPermissions() {
        permissionState.value = PermissionState(notificationsGranted = notificationsGranted(), exempt = BatteryGate.isOpen(this))
    }

    private fun notificationsGranted(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /**
     * The notifications banner's Grant: the system dialog, or after a denial for good the app's notification
     * settings page, because a request then returns at once without asking (RESEARCH Pitfall 6).
     */
    private fun grantNotifications() {
        val action = notificationGrantAction(
            askedBefore = askedNotifications,
            rationale = shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS),
        )
        Log.i(TAG, "notifications grant: $action")
        when (action) {
            GrantAction.REQUEST -> {
                askedNotifications = true
                notificationsLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            GrantAction.OPEN_SETTINGS -> try {
                startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
                )
            } catch (e: ActivityNotFoundException) {
                Log.w(TAG, "no activity for the notification settings intent", e)
            }
        }
    }

    /** The battery-optimisation exemption through the system dialog, started from the banner's Grant button. */
    @android.annotation.SuppressLint("BatteryLife")
    private fun grantBattery() {
        Log.i(TAG, "requesting the battery-optimisation exemption")
        try {
            batteryLauncher.launch(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "no activity for the battery exemption intent, not starting the bridge", e)
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
    }
}
