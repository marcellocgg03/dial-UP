// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for Tube2U[PnP] for Android.
package dev.t2u.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.t2u.app.model.Banner

/**
 * One card per missing permission, each with a sentence and a Grant button (D-01). Nothing is requested
 * automatically: the author taps Grant and the Activity launches the request.
 */
@Composable
fun Banners(banners: List<Banner>, onGrantNotifications: () -> Unit, onGrantBattery: () -> Unit) {
    if (banners.isEmpty()) return
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (banner in banners) {
            when (banner) {
                Banner.BATTERY -> BannerCard(
                    tag = "banner-battery",
                    buttonTag = "grant-battery",
                    text = "Allow unrestricted battery use; the bridge does not start without it",
                    onGrant = onGrantBattery,
                )
                Banner.NOTIFICATIONS -> BannerCard(
                    tag = "banner-notifications",
                    buttonTag = "grant-notifications",
                    text = "Allow notifications so the bridge's status and its Stop button are visible",
                    onGrant = onGrantNotifications,
                )
            }
        }
    }
}

@Composable
private fun BannerCard(tag: String, buttonTag: String, text: String, onGrant: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().testTag(tag),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Button(onClick = onGrant, modifier = Modifier.testTag(buttonTag)) { Text("Grant") }
        }
    }
}
