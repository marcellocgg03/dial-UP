// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.dialup.app

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.core.content.FileProvider

/**
 * Opens the system share sheet with one zip of the event-log files (every rotated file and the current one, behind a
 * summary of their time ranges) and nothing else (an unrelated file must never be shared). Started
 * by the Share log button on the Settings tab (no longer by the notification); not exported. A chooser started by an Activity carries the
 * one-file read grant reliably, which is why this is an Activity and not a service trampoline.
 * state.json, store.json and config.json are never offered. The files directory lookup, the flush, the file listing and the zip are built on a
 * background thread; only the Toasts and the chooser start run on the UI thread.
 */
class ShareLogActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val authority = "$packageName.fileprovider"
        Thread({
            try {
                // Context.getFilesDir stats the directory (a disk read), so it is read here and not in onCreate.
                val dir = eventLogDir(filesDir)
                // Make the file complete before it is handed over.
                val file = LogArchive.prepare(dir) { ServiceHub.service?.evidence?.flush() }
                val uri = file?.let { FileProvider.getUriForFile(this, authority, it) }
                runOnUiThread { if (usable()) offer(uri) else finish() }
            } catch (t: Throwable) {
                ServiceHub.service?.evidence?.event("feature-error", featureErrorFields("share-log", t))
                runOnUiThread {
                    if (usable()) Toast.makeText(this, "Could not share the log", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        }, "t2u-share-log").start()
    }

    private fun usable() = !isFinishing && !isDestroyed

    /** On the UI thread: the chooser for [uri], or the Toast when there is no log (null); then this Activity ends. */
    private fun offer(uri: Uri?) {
        try {
            if (uri == null) {
                Toast.makeText(this, "No log yet", Toast.LENGTH_SHORT).show()
            } else {
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
            ServiceHub.service?.evidence?.event("feature-error", featureErrorFields("share-log", t))
            Toast.makeText(this, "Could not share the log", Toast.LENGTH_SHORT).show()
        }
        finish()
    }
}
