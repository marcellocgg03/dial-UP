package dev.t2u.renderer

// The poll scheduling of one renderer, GPL-3.0-only.

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Runs [tick] every [interval] milliseconds in [scope]: one sequential loop, so a slow speaker never stacks polls.
 * It owns the lock, the job and the generation counter. Every [start] and [stop] bumps the generation, and a loop
 * that is no longer on the current generation never runs another tick, even when its cancellation has not arrived yet.
 * [mayRun] is the guard [start] checks (no loop while destroyed or while the network is down); [alive] is the
 * loop's own "not destroyed" condition. Both are read at every use. A failed tick (not a cancellation) goes to [onError].
 */
internal class PollLoop(
    private val scope: CoroutineScope,
    private val interval: () -> Long,
    private val mayRun: () -> Boolean,
    private val alive: () -> Boolean,
    private val tick: suspend () -> Unit,
    private val onError: (Exception) -> Unit,
) {
    private val pollLock = Any()
    private var pollJob: Job? = null
    private var pollGen = 0

    fun start() {
        stop()
        // no poll while the phone has no network: a stream broken by the move must not be mirrored to the phone
        if (!mayRun()) return
        synchronized(pollLock) {
            val gen = ++pollGen
            pollJob = scope.launch {
                while (isActive && alive() && gen == pollGenSnapshot()) {
                    delay(interval())
                    if (gen != pollGenSnapshot()) break
                    try {
                        tick()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        onError(e)
                    }
                }
            }
        }
    }

    private fun pollGenSnapshot(): Int = synchronized(pollLock) { pollGen }

    fun stop() {
        synchronized(pollLock) {
            pollGen++
            pollJob?.cancel()
            pollJob = null
        }
    }
}
