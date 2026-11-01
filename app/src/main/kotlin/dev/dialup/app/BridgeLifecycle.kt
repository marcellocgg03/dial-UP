// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.dialup.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Plan 07-20 (review CR-01 a): the old bridge must be gone before the next one binds, also when the old and the new bridge
 * belong to two [BridgeService] instances (the service is destroyed and recreated by the switch, Restart and the
 * notification). The state therefore lives in the process, not in [CastFeature].
 *
 * A stop registers its token synchronously ([beginStop]) before the caller returns, and completes it when the shutdown
 * finished. Every start calls [awaitIdle] and then takes [mutex].
 */
class LifecycleCore {
    private val inFlight = MutableStateFlow(0)
    private val stoppingState = MutableStateFlow(false)

    /** True while at least one stop has begun and not completed. */
    val stopping: StateFlow<Boolean> = stoppingState.asStateFlow()

    /** The one lock every start, retry, rebind and stop takes. */
    val mutex = Mutex()

    /** Registers a stop now. [StopToken.complete] must be called once the shutdown ended, whatever its outcome. */
    fun beginStop(): StopToken {
        synchronized(this) {
            inFlight.value += 1
            stoppingState.value = true
        }
        return StopToken(this)
    }

    internal fun finish() {
        synchronized(this) {
            inFlight.value -= 1
            stoppingState.value = inFlight.value > 0
        }
    }

    /** Suspends until no stop is in flight; false when [timeoutMs] ran out first. */
    suspend fun awaitIdle(timeoutMs: Long): Boolean = withTimeoutOrNull(timeoutMs) {
        stopping.first { !it }
        true
    } ?: false
}

/** One stop in flight; [complete] is idempotent. */
class StopToken internal constructor(private val core: LifecycleCore) {
    private val done = AtomicBoolean(false)

    fun complete() {
        if (done.compareAndSet(false, true)) core.finish()
    }
}

/**
 * The process-wide instance. [scope] outlives every service: the shutdown of a destroyed service continues here, off
 * the main thread (review WR-02). The handler only logs, nothing launched here may end the process.
 */
object BridgeLifecycle {
    private val core = LifecycleCore()

    val mutex: Mutex get() = core.mutex
    val stopping: StateFlow<Boolean> get() = core.stopping

    val scope: CoroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + loggingExceptionHandler { where, t ->
            android.util.Log.w("t2u", "bridge lifecycle $where: ${t.javaClass.name}: ${t.message}")
        },
    )

    fun beginStop(): StopToken = core.beginStop()

    suspend fun awaitIdle(timeoutMs: Long): Boolean = core.awaitIdle(timeoutMs)
}

/** True when [t] or one of its causes is a [java.net.BindException] (a port the old bridge still holds, plan 07-19). */
fun isBindFailure(t: Throwable): Boolean {
    var cur: Throwable? = t
    var depth = 0
    while (cur != null && depth < 10) {
        if (cur is java.net.BindException) return true
        cur = cur.cause
        depth++
    }
    return false
}

/** Delay of the start retry after [attempt] earlier consecutive bind failures (0 for the first retry); other failures wait [START_RETRY_MS]. */
fun startRetryDelayMs(attempt: Int, bindFailure: Boolean): Long =
    if (bindFailure && attempt < QUICK_BIND_RETRIES) QUICK_BIND_RETRY_MS else START_RETRY_MS

private const val QUICK_BIND_RETRIES = 2
private const val QUICK_BIND_RETRY_MS = 2_000L
