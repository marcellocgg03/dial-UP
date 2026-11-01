// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.dialup.app.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import dev.dialup.app.DEFAULT_THEME
import dev.dialup.app.ThemeMode
import dev.dialup.app.useDarkTheme

/**
 * The app's Material 3 theme (GUI-12): follows the Theme setting (System follows the phone's light or dark setting),
 * with wallpaper colours on Android 12 and later.
 */
@Composable
fun DialUpTheme(mode: ThemeMode = DEFAULT_THEME, content: @Composable () -> Unit) {
    val dark = useDarkTheme(mode, isSystemInDarkTheme())
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(LocalContext.current) else dynamicLightColorScheme(LocalContext.current)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colors, content = content)
}
