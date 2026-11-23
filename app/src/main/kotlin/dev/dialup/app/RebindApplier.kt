package dev.dialup.app

// What CastFeature does once the network debounce ends: the arms of [rebindAction], moved out so a fake target can drive each
// one on the JVM. GPL-3.0-only.

import dev.t2u.renderer.BridgeNetwork
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * What [RebindApplier] needs from the cast feature. Every member is a thin wrapper over what [CastFeature] already had; the
 * applier is called with the lifecycle lock held, so nothing here takes it.
 */
internal interface RebindTarget {
    /** True while a bridge exists. */
    val hasBridge: Boolean

    /** The IPv4 address the bridge was last started or moved on; null before the first start or after a start that found no Wi-Fi. */
    var lastIp: String?

    /** The phone's Wi-Fi IPv4 address right now, null when there is none. */
    fun wifiIp(): String?

    /** Sets the network-up flag the notification and the status UI follow. */
    fun markNetworkUp(up: Boolean)

    /** Starts the bridge, logging a failure instead of throwing; true only when a bridge exists afterwards. */
    suspend fun startGuarded(reason: String): Boolean

    /** True when the last [startGuarded] ended on a thrown exception, not on a missing Wi-Fi address. */
    val lastStartThrew: Boolean

    /** Stops the bridge without clearing or stopping the speakers. */
    suspend fun stopKeepingSpeakers()

    /** Looks for a bridge again after the retry delay. */
    fun scheduleStartRetry()

    /** Forgets earlier bind failures, so the quick retry does not continue for a start that was never tried. */
    fun resetBindFailures()

    /**
     * One failed attempt, a start or a move that threw. The target retries later by replaying [retryReason] (so the retry of a
     * move is the same move, not the "retry" reason, which does nothing while a bridge exists), or reports once when the limit
     * of failures in a row is reached.
     */
    fun failedAttempt(retryReason: String)

    /** A success, or an outcome with no Wi-Fi (which is not a failure): ends the run of failures. */
    fun resetFailures()

    /** Waits (bounded) for the system to validate the new network; true when it did. */
    suspend fun awaitValidated(): Boolean

    /** The Wi-Fi address and interface as a bridge network, null when there is none. */
    fun currentNetwork(): BridgeNetwork?

    /**
     * The running bridge's in-place move, or null when no bridge exists. Captured once, so the bridge that is checked is the
     * one that is moved.
     */
    fun inPlaceRebind(): (suspend (BridgeNetwork) -> Unit)?

    /** Writes an event to the event log. */
    fun event(kind: String, fields: Map<String, Any?>)
}

/**
 * Applies [rebindAction]'s verdict to a [RebindTarget]: keeps a bridge frozen, starts one, restarts it or moves it in place, and
 * writes the `net` event for each outcome. Call with the lifecycle lock held, after the debounce.
 */
internal class RebindApplier(private val target: RebindTarget) {
    /**
     * Never throws except a cancellation. A start or move that throws (the Wi-Fi address read, the validation wait, the network
     * read, the stop of a restart) is logged as one `net` event with mode `failed`, the reason and the exception class, and
     * handed to [RebindTarget.failedAttempt] with the original [reason]; a start that ended on a thrown exception counts too.
     * After [BRIDGE_FAILURE_LIMIT] failures in a row the target reports and stops retrying.
     */
    suspend fun apply(reason: String) {
        val oldIp = target.lastIp
        try {
            applyUnguarded(reason, oldIp)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            try {
                target.event(
                    "net",
                    mapOf("rebind" to false, "mode" to "failed", "reason" to reason, "oldIp" to oldIp, "error" to t.javaClass.name),
                )
            } catch (_: Throwable) {
                // logging must not keep the retry from being scheduled
            }
            target.failedAttempt(reason)
        }
    }

    private suspend fun applyUnguarded(reason: String, oldIp: String?) {
        when (rebindAction(reason, target.hasBridge, target.wifiIp())) {
            RebindAction.MARK_LOST_ONLY -> {
                // no Wi-Fi address now: the bridge stays, frozen, and the next address change moves it
                target.resetFailures()
                target.markNetworkUp(false)
                target.event("net", mapOf("rebind" to false, "mode" to "lost-kept", "reason" to reason, "oldIp" to oldIp))
            }

            RebindAction.START -> {
                // a move is never cut in half by a newer event; that one waits for the lock instead
                val ok = withContext(NonCancellable) { target.startGuarded(reason) }
                if (ok) {
                    target.resetFailures()
                    target.event(
                        "net",
                        mapOf("rebind" to true, "mode" to "start", "reason" to reason, "oldIp" to oldIp, "newIp" to target.lastIp),
                    )
                } else {
                    target.event("net", mapOf("rebind" to false, "mode" to "start-failed", "reason" to reason, "oldIp" to oldIp))
                    retryAfterFailedStart()
                }
            }

            RebindAction.RESTART -> {
                val ok = withContext(NonCancellable) {
                    target.stopKeepingSpeakers()
                    target.startGuarded(reason)
                }
                if (ok) {
                    target.resetFailures()
                    target.event(
                        "net",
                        mapOf("rebind" to true, "mode" to "restart", "reason" to reason, "oldIp" to oldIp, "newIp" to target.lastIp),
                    )
                } else {
                    target.event("net", mapOf("rebind" to false, "mode" to "restart-failed", "reason" to reason, "oldIp" to oldIp))
                    retryAfterFailedStart()
                }
            }

            // only the retry reason gets here: a bridge exists (done) or there is still no Wi-Fi (look again later)
            RebindAction.NOTHING -> if (!target.hasBridge) {
                // no start was tried, so a bind failure from an earlier one must not keep the quick retry going
                target.resetBindFailures()
                target.resetFailures()
                target.scheduleStartRetry()
            }

            RebindAction.REBIND_IN_PLACE -> rebindInPlace(reason, oldIp)
        }
    }

    /**
     * A start that threw is a failed attempt, retried with the "retry" reason (there is no bridge, so it starts one); a start
     * that found no Wi-Fi is not a failure and is looked for again at the usual delay.
     */
    private fun retryAfterFailedStart() {
        if (target.lastStartThrew) {
            target.failedAttempt("retry")
        } else {
            target.resetFailures()
            target.scheduleStartRetry()
        }
    }

    private suspend fun rebindInPlace(reason: String, oldIp: String?) {
        val started = System.nanoTime()
        val validated = target.awaitValidated()
        val waitedMs = (System.nanoTime() - started) / 1_000_000L
        // the network may be gone again after the wait: then the bridge stays frozen until the next address
        val network = target.currentNetwork()
        val rebind = target.inPlaceRebind()
        if (network == null || rebind == null) {
            target.resetFailures()
            target.markNetworkUp(false)
            target.event(
                "net",
                mapOf(
                    "rebind" to false,
                    "mode" to "lost-kept",
                    "reason" to reason,
                    "oldIp" to oldIp,
                    "validated" to validated,
                    "waitedMs" to waitedMs,
                ),
            )
            return
        }
        withContext(NonCancellable) { rebind(network) }
        target.lastIp = network.wifiIp.hostAddress
        target.resetFailures()
        target.markNetworkUp(true)
        target.event(
            "net",
            mapOf(
                "rebind" to true,
                "mode" to "in-place",
                "reason" to reason,
                "oldIp" to oldIp,
                "newIp" to target.lastIp,
                "validated" to validated,
                "waitedMs" to waitedMs,
            ),
        )
    }
}
