// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.dialup.app

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

/**
 * Opens the system share sheet with one zip of the event-log files (every rotated file and the current one, behind a
 * summary of their time ranges) and nothing else (an unrelated file must never be shared). Started
 * by the Share log button on the Settings tab (no longer by the notification); not exported. A chooser started by an Activity carries the
 * one-file read grant reliably, which is why this is an Activity and not a service trampoline.
 * state.json, store.json and config.json are never offered.
 */
class ShareLogActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            // Make the file complete before it is handed over.
            ServiceHub.service?.evidence?.flush()
            val dir = eventLogDir(filesDir)
            val file = File(dir, LogArchive.ZIP_NAME)
            val logs = LogArchive.logFiles(dir)
            if (logs.isEmpty()) {
                file.delete()
                Toast.makeText(this, "No log yet", Toast.LENGTH_SHORT).show()
            } else {
                LogArchive.build(logs, file)
                val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "application/zip"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, "DialUP log")
                    clipData = ClipData.newRawUri("log", uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val chooser = Intent.createChooser(send, "Share log").apply {
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(chooser)
            }
        } catch (t: Throwable) {
            ServiceHub.service?.evidence?.event(
                "feature-error",
                mapOf("where" to "share-log", "error" to t.javaClass.name, "message" to t.message),
            )
            Toast.makeText(this, "Could not share the log", Toast.LENGTH_SHORT).show()
        }
        finish()
    }
}
