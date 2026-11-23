// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.dialup.app

import android.content.Context
import android.util.Log
import java.io.File

/**
 * The maintenance gate of one process: counted [begin] and [end] around work that stops the bridge on purpose (a restart,
 * the stop around a yt-dlp update), plus a breadcrumb file.
 *
 * The in-memory count is the gate. The file is only a trace of work a killed process did not finish, never a gate across
 * processes: a persisted gate would keep the bridge off for ever after a force-stop in the middle of the work. [clearStale]
 * deletes a trace an earlier process left, so the first touch of every process starts clean.
 *
 * Overlapping work (a restart during an update) keeps the gate until the last [end]. A breadcrumb that cannot be written or
 * deleted is swallowed: the gate holds without it.
 */
internal class MaintenanceState(private val breadcrumb: File) {
    private var count = 0

    /** True while at least one piece of work is between its [begin] and its [end]. */
    val isActive: Boolean
        @Synchronized get() = count > 0

    @Synchronized
    fun begin() {
        if (count++ == 0) {
            try {
                breadcrumb.writeText(System.currentTimeMillis().toString())
            } catch (_: Exception) {
                // the in-memory gate holds without the trace
            }
        }
    }

    /** Ends one piece of work; true when it was the last one (the gate is open again). An end without a begin changes nothing. */
    @Synchronized
    fun end(): Boolean {
        if (count == 0) return false
        count--
        if (count > 0) return false
        deleteBreadcrumb()
        return true
    }

    /** Deletes a trace an earlier process left; true when one existed. */
    @Synchronized
    fun clearStale(): Boolean {
        val existed = try {
            breadcrumb.exists()
        } catch (_: Exception) {
            false
        }
        if (existed) deleteBreadcrumb()
        return existed
    }

    private fun deleteBreadcrumb() {
        try {
            breadcrumb.delete()
        } catch (_: Exception) {
            // nothing to do: the trace is cleared by the next process anyway
        }
    }
}

/**
 * The process-wide maintenance marker ([MaintenanceState] over `filesDir/bridge-maintenance`). The first call of any member
 * clears a breadcrumb an earlier process left and remembers that it did, so [takeInterrupted] tells the service bootstrap
 * once that work was cut short. After a force-stop the saved switch still says on, so the bridge starts through the normal gate.
 */
object MaintenanceMarker {
    private const val TAG = "T2U"
    private val lock = Any()
    private var state: MaintenanceState? = null
    private var interrupted = false

    private fun of(context: Context): MaintenanceState = synchronized(lock) {
        state ?: MaintenanceState(File(context.applicationContext.filesDir, "bridge-maintenance")).also {
            if (it.clearStale()) {
                interrupted = true
                Log.i(TAG, "maintenance: cleared a trace left by a process that ended in the middle of a restart or update")
            }
            state = it
        }
    }

    fun begin(context: Context) = of(context).begin()

    /** True when this end was the last one of overlapping work. */
    fun end(context: Context): Boolean = of(context).end()

    fun isActive(context: Context): Boolean = of(context).isActive

    /** True once, when the first touch of this process cleared a trace of work that a killed process did not finish. */
    fun takeInterrupted(context: Context): Boolean {
        of(context)
        return synchronized(lock) { interrupted.also { interrupted = false } }
    }
}
