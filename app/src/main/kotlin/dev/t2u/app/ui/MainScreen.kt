// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for Tube2U[PnP] for Android.
package dev.t2u.app.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import dev.t2u.app.AppModel

/** The Compose root. testTagsAsResourceId lets uiautomator see the test tags (plan 07-16). */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun MainScreen(model: AppModel) {
    MaterialTheme {
        Scaffold(
            modifier = Modifier.fillMaxSize().semantics { testTagsAsResourceId = true },
            topBar = { Header(model) },
        ) { padding ->
            SpeakersTab(model, Modifier.padding(padding))
        }
    }
}
