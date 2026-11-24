// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.dialup.app

import java.io.File

/**
 * The shared event log at the configured level, for writers that run with no bridge (the daily yt-dlp job, the offline
 * updater of Settings).
 *
 * A new [EvidenceLog] starts at DEBUG and only a running service sets its level, so without this such a writer would log
 * while the user has switched event logging off. The level is the one a service start gives: config.json with settings.json
 * laid over it ([loadAppConfig]). When a service runs, [EvidenceLog.shared] returns the service's own log and the level
 * written here is the one the service computed the same way.
 *
 * Blocking file reads: call it off the main thread. [open] exists for JVM tests (the defaults of [EvidenceLog] call Android).
 */
fun offlineEvidence(filesDir: File, open: (File) -> EvidenceLog = { EvidenceLog.shared(it) }): EvidenceLog =
    open(eventLogDir(filesDir)).also { it.setLevel(logLevelOf(loadAppConfig(filesDir).config.logLevel)) }
