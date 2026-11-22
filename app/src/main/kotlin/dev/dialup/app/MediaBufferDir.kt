package dev.dialup.app

import java.io.File

/**
 * Android can reclaim this directory, and its Clear cache action removes it. Move the old persistent files once.
 * What the migration ignores is counted into the running service's event log, else the shared event log file (as
 * YtDlpUpdateJobService does), so the count is logged on every pass even when no service runs.
 */
@Synchronized
internal fun mediaBufferDir(context: android.content.Context): File {
    val dir = File(context.cacheDir, "media-buffer")
    dev.t2u.media.migrateMediaBufferDir(
        File(context.filesDir, "media-buffer"),
        dir,
        onEvent = { fields -> (ServiceHub.service?.evidence ?: EvidenceLog.shared(eventLogDir(context.filesDir))).event("filebuild", fields) },
    )
    return dir
}
