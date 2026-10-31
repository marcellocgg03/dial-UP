// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for Tube2U[PnP] for Android.
package dev.t2u.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.t2u.app.AppModel
import dev.t2u.app.model.statusLine

/** The app bar: the name and one line saying whether the bridge runs and who may cast to it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Header(model: AppModel) {
    val status by model.status.collectAsStateWithLifecycle()
    TopAppBar(
        title = {
            Column {
                Text("DialUP")
                Text(
                    text = statusLine(status),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag("status-line"),
                )
            }
        },
    )
}
