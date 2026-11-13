// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.dialup.app

import android.content.Context
import dev.dialup.app.model.AppUpdateModel
import dev.dialup.app.model.RELEASES_API_URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The one process-wide app update check: the card on the Renderers tab and the App updates section in Settings share it. */
object AppUpdates {
    @Volatile private var instance: AppUpdateModel? = null

    fun of(context: Context): AppUpdateModel {
        instance?.let { return it }
        synchronized(this) {
            instance?.let { return it }
            val app = context.applicationContext
            val version = BuildConfigFacts.versionName(app)
            val client = githubReleasesClient()
            val agent = userAgentFor(version)
            val record = UpdateRecordFile(app.filesDir)
            val created = AppUpdateModel(
                currentVersion = version ?: "0",
                fetch = { withContext(Dispatchers.IO) { fetchReleases(client, RELEASES_API_URL, agent) } },
                load = record::load,
                save = record::save,
                wallMs = System::currentTimeMillis,
                channel = { AppSettings.of(app).data.value.updateChannel },
            )
            instance = created
            return created
        }
    }
}
