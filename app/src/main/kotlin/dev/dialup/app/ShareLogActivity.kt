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
 * Opens the system share sheet with the current log file and nothing else (D-11, T-05-22). Started
 * by the notification's Share log action; not exported. A chooser started by an Activity carries the
 * one-file read grant reliably, which is why this is an Activity and not a service trampoline.
 * Rotated logs, state.json, store.json and config.json are never offered.
 */
class ShareLogActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            // Make the file complete before it is handed over.
            ServiceHub.service?.evidence?.flush()
            val file = File(logDirFor(getExternalFilesDir(null), filesDir).first, EvidenceLog.FILE_NAME)
            if (!file.isFile || file.length() == 0L) {
                Toast.makeText(this, "No log yet", Toast.LENGTH_SHORT).show()
            } else {
                val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
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
