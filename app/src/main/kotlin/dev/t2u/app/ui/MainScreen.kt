// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for Tube2U[PnP] for Android.
package dev.t2u.app.ui

import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.t2u.app.AppModel
import dev.t2u.app.model.PermissionState
import dev.t2u.app.model.bannerState
import kotlinx.coroutines.flow.StateFlow

/**
 * The Compose root. testTagsAsResourceId lets uiautomator see the test tags (plan 07-16). The permission
 * banners sit above the speaker list (D-01); their Grant buttons call back into the Activity, which owns the
 * result launchers.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun MainScreen(
    model: AppModel,
    permissions: StateFlow<PermissionState>,
    onGrantNotifications: () -> Unit,
    onGrantBattery: () -> Unit,
) {
    val granted by permissions.collectAsStateWithLifecycle()
    DialUpTheme {
        Scaffold(
            modifier = Modifier.fillMaxSize().semantics { testTagsAsResourceId = true },
            topBar = { Header(model) },
        ) { padding ->
            Column(Modifier.padding(padding)) {
                Banners(
                    banners = bannerState(Build.VERSION.SDK_INT, granted.notificationsGranted, granted.exempt),
                    onGrantNotifications = onGrantNotifications,
                    onGrantBattery = onGrantBattery,
                )
                SpeakersTab(model, Modifier.weight(1f))
            }
        }
    }
}
