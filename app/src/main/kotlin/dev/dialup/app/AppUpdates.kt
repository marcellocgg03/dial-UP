// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.dialup.app

import android.content.Context
import dev.dialup.app.model.AppUpdateModel
import dev.dialup.app.model.RELEASES_API_URL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
            // Both are built on first use, on the model's own threads: this runs in the main screen's composition, and StrictMode
            // reports `filesDir` (a disk lookup) and building the HTTP client (a custom slow call) there.
            val client by lazy { githubReleasesClient() }
            val agent = userAgentFor(version)
            val record by lazy { UpdateRecordFile(app.filesDir) }
            val created = AppUpdateModel(
                currentVersion = version ?: "0",
                fetch = { withContext(Dispatchers.IO) { fetchReleases(client, RELEASES_API_URL, agent) } },
                load = { record.load() },
                save = { record.save(it) },
                wallMs = System::currentTimeMillis,
                channel = { AppSettings.of(app).data.value.updateChannel },
                // the check outlives any screen that asked for it; the client's 20 s call timeout bounds it
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
                // the screens build this on the main thread; the record file is read on IO by the model's own scope
                loadContext = Dispatchers.IO,
            )
            instance = created
            return created
        }
    }
}
