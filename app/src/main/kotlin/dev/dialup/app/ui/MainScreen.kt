// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.dialup.app.ui

import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.dialup.app.AppModel
import dev.dialup.app.AppSettings
import dev.dialup.app.AppUpdates
import dev.dialup.app.BuildConfigFacts
import dev.dialup.app.SettingsModel
import dev.dialup.app.ThemeMode
import dev.dialup.app.model.PermissionState
import dev.dialup.app.model.bannerState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The Compose root. testTagsAsResourceId lets uiautomator see the test tags. The permission
 * banners sit above both tabs; their Grant buttons call back into the Activity, which owns the
 * result launchers. A bottom bar switches between Speakers and Settings. The theme comes from the Activity.
 * Every app open ([opens] going up) lets the settings presenter apply a bridge restart that is still owed.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun MainScreen(
    model: AppModel,
    permissions: StateFlow<PermissionState>,
    opens: StateFlow<Int>,
    theme: ThemeMode,
    onGrantNotifications: () -> Unit,
    onGrantBattery: () -> Unit,
) {
    val granted by permissions.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember(scope) { SettingsModel(context, scope).presenter() }
    val settingsUi by settings.state.collectAsStateWithLifecycle()
    val openCount by opens.collectAsStateWithLifecycle()
    val appData by AppSettings.of(context).data.collectAsStateWithLifecycle()
    val updateCheckEnabled = appData.updateCheckEnabled
    val appUpdates = remember { AppUpdates.of(context) }
    val version = remember { BuildConfigFacts.versionName(context) ?: "0" }
    LaunchedEffect(openCount) {
        if (openCount > 0) {
            // the automatic release check runs beside the other calls and never delays them (throttled, off when switched off)
            launch { appUpdates.autoCheck(updateCheckEnabled) }
            settings.onAppOpened { model.casting.value }
            // one extra search when the app comes to the foreground (throttled; nothing without a bridge)
            model.searchOnForeground()
        }
    }
    var tab by rememberSaveable { mutableIntStateOf(TAB_SPEAKERS) }
    DialUpTheme(theme) {
        Scaffold(
            modifier = Modifier.fillMaxSize().semantics { testTagsAsResourceId = true },
            topBar = { Header(model) },
            bottomBar = {
                Column {
                    RestartCard(model, settings.restart, settingsUi.clearOnCastEnd)
                    NavigationBar {
                        NavigationBarItem(
                            selected = tab == TAB_SPEAKERS,
                            onClick = { tab = TAB_SPEAKERS },
                            icon = { Icon(Icons.Filled.Home, contentDescription = null) },
                            label = { Text("Renderers") },
                            modifier = Modifier.testTag("tab-speakers"),
                        )
                        NavigationBarItem(
                            selected = tab == TAB_SETTINGS,
                            onClick = { tab = TAB_SETTINGS },
                            icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                            label = { Text("Settings") },
                            modifier = Modifier.testTag("tab-settings"),
                        )
                    }
                }
            },
        ) { padding ->
            Column(Modifier.padding(padding)) {
                Banners(
                    banners = bannerState(Build.VERSION.SDK_INT, granted.notificationsGranted, granted.exempt),
                    onGrantNotifications = onGrantNotifications,
                    onGrantBattery = onGrantBattery,
                )
                if (tab == TAB_SETTINGS) {
                    SettingsTab(settings, model, Modifier.weight(1f))
                } else {
                    UpdateCard(appUpdates, updateCheckEnabled, version)
                    SpeakersTab(
                        model,
                        Modifier.weight(1f),
                        onRenamed = settings::onRenamed,
                        onRestart = { settings.restart.requestRestart(model.casting.value) },
                    )
                }
            }
        }
    }
}

private const val TAB_SPEAKERS = 0
private const val TAB_SETTINGS = 1
