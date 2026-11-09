// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.dialup.app

import android.content.Context
import dev.t2u.media.YtDlpRecord
import dev.t2u.media.YtDlpRecordStore
import dev.t2u.media.parseYtDlpRecord
import dev.t2u.media.ytDlpRecordJson
import dev.t2u.upnp.AtomicWriteOptions
import dev.t2u.upnp.writeTextAtomic
import java.io.File
import java.io.IOException

/**
 * The persisted yt-dlp update record (`<dir>/ytdlp-update.json`): last success and last attempt on the wall clock. One
 * file shared by the in-bridge updater, the offline Update now and the daily job (plan 07.1-07). A missing or unreadable
 * file loads as an empty record, which means "due"; a failed save is swallowed because it must not stop an update.
 */
class YtDlpRecordFile(private val dir: File) : YtDlpRecordStore {
    @Synchronized
    override fun load(): YtDlpRecord = try {
        parseYtDlpRecord(File(dir, YTDLP_RECORD_FILE).readText())
    } catch (_: IOException) {
        YtDlpRecord(null, null)
    } catch (_: SecurityException) {
        YtDlpRecord(null, null)
    }

    @Synchronized
    override fun save(record: YtDlpRecord) {
        try {
            writeTextAtomic(File(dir, YTDLP_RECORD_FILE), ytDlpRecordJson(record), AtomicWriteOptions.APP)
        } catch (_: IOException) {
        } catch (_: SecurityException) {
        }
    }
}

private const val YTDLP_RECORD_FILE = "ytdlp-update.json"

/** The one process-wide [YtDlpRecordFile] on `filesDir`. */
object YtDlpRecords {
    @Volatile private var instance: YtDlpRecordFile? = null

    fun of(context: Context): YtDlpRecordFile = of(context.applicationContext.filesDir)

    internal fun of(filesDir: File): YtDlpRecordFile {
        instance?.let { return it }
        synchronized(this) {
            instance?.let { return it }
            val created = YtDlpRecordFile(filesDir)
            instance = created
            return created
        }
    }
}
