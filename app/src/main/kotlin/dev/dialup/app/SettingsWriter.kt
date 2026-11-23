// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.dialup.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/**
 * One daemon thread that runs tasks one at a time in the order they were posted. A task that throws is reported through
 * [report] (as `uncaught`) and the next one runs: the writer survives a failing task. [post] never blocks the caller;
 * [run] suspends until the task has run, which is after every task posted before it.
 */
internal class SerialWriter(threadName: String, report: (String, Throwable) -> Unit) {
    private val dispatcher: ExecutorCoroutineDispatcher =
        Executors.newSingleThreadExecutor { r -> Thread(r, threadName).apply { isDaemon = true } }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher + loggingExceptionHandler(report))

    /** Queues [block] behind the earlier tasks and returns at once. */
    fun post(block: () -> Unit): Job = scope.launch { block() }

    /** Runs [block] on the writer thread after every earlier [post]; returns its result. */
    suspend fun <T> run(block: () -> T): T = withContext(dispatcher) { block() }

    /** Stops the thread once the queued tasks are done. For tests; the process instance is never closed. */
    fun close() {
        dispatcher.close()
    }
}

/**
 * The one place settings writes run. Every settings change from the UI (the presenter setters, the Header switch, the
 * confirm-off dialog) and the notification Stop go through here, so the write and its fsync never happen on the main thread
 * and the changes land in the order they were made. The writer outlives every screen and the service scope, so a write is
 * never cancelled by a screen that closes or a service that is being destroyed.
 */
object SettingsWriter {
    private val writer = SerialWriter("t2u-settings") { _, t -> Failures.report("settings-writer", t) }

    /** Queues [block] behind the earlier writes and returns at once. */
    fun post(block: () -> Unit): Job = writer.post(block)

    /** Runs [block] on the writer thread, after every write posted before it. */
    suspend fun <T> run(block: () -> T): T = writer.run(block)
}
