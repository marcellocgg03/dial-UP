package dev.t2u.lounge

// The outgoing half of a lounge session (Session.js sendMessage and its queue): messages leave one at a time, in order,
// with the AID and offset of the bind session. Split out of Session.kt; Session keeps the lifecycle and the tokens.

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException

/**
 * The send queue of one [Session]: the channel and its consumer, the deferred (replaceable) sends and the POST of a
 * message batch. [sendLock] serialises the POSTs; [withSendLock] lends it to the session for the farewell and for the
 * token refresh, so a refresh never overlaps a send.
 *
 * A network error on a POST (an [IOException] that is not a [LoungeHttpException]) is retried in place with the same
 * messages and the same offset, with the reader's waits ([outageWaitsMs], the last value repeating), until [outageBudgetMs]
 * is used up; the send lock is not held while it waits, and a session that ended meanwhile gets no further POST. The lounge is
 * assumed to take a repeated offset as the same request (research assumption A1, still to be checked on a phone), so a POST
 * whose answer was lost is not counted twice: the offset moves on only after the lounge accepted the POST. An HTTP refusal, any other error, and a network error after the budget refresh the token
 * through [refreshToken] and retry once; a second failure ends the session through [endSession]. A budget of 0 turns the
 * tolerance off.
 *
 * While a send waits, the sends queued behind it are coalesced (see [coalesceWhileWaiting]).
 */
internal class SessionSendQueue(
    private val scope: CoroutineScope,
    private val endpoints: LoungeEndpoints,
    private val bind: BindParams,
    private val log: LoungeLog,
    private val formType: MediaType,
    private val execute: suspend (Request) -> Response,
    /** True while the session may send (running or refreshing its token). */
    private val canSend: () -> Boolean,
    private val refreshToken: suspend () -> Unit,
    private val endSession: suspend (Throwable) -> Unit,
    /** How long a send keeps retrying in place after network errors (the reader's budget); 0 turns that off. */
    private val outageBudgetMs: Long,
    /** Waits between those retries; the last value repeats. */
    private val outageWaitsMs: List<Long>,
    /** Milliseconds of a monotonic clock. */
    private val nowMs: () -> Long,
) {
    private class SendTask(
        val messages: List<OutgoingMessage>,
        val epoch: Int,
        val result: CompletableDeferred<Boolean>,
        val deferKey: String? = null,
    )

    private class DeferredSend(val task: SendTask, var job: Job? = null)

    private val sendChannel = Channel<SendTask>(Channel.UNLIMITED)
    private val sendLock = Mutex()
    private val deferred = HashMap<String, DeferredSend>()

    /** Tasks taken off [sendChannel] while a send waited, in queue order; the consumer sends them before it reads the channel again. */
    private val heldBack = ArrayDeque<SendTask>()

    @Volatile
    private var epoch = 0

    private var ofs = 0

    private val sendConsumer: Job by lazy {
        scope.launch {
            while (true) {
                val task = synchronized(heldBack) { heldBack.removeFirstOrNull() }
                    ?: sendChannel.receiveCatching().getOrNull()
                    ?: break
                process(task)
            }
        }
    }

    /** Runs [block] holding the send lock: no POST is in flight meanwhile. */
    suspend fun <T> withSendLock(block: suspend () -> T): T = sendLock.withLock { block() }

    /** The bind session restarts: outgoing messages count from zero again. */
    fun resetOffset() {
        ofs = 0
    }

    /** A token refresh finished (called while the send lock is still held): sends queued before it go out with their AID cleared. */
    fun nextEpoch() {
        epoch++
    }

    /** See [Session.sendMessage]. */
    suspend fun sendMessage(
        messages: List<OutgoingMessage>,
        deferKey: String? = null,
        deferMs: Long = LoungeConstants.VOLUME_DEFER_MS,
    ): Boolean {
        if (!canSend()) return false
        sendConsumer.isActive // starts the consumer
        val task = SendTask(messages, epoch, CompletableDeferred(), deferKey)
        if (deferKey == null) {
            sendChannel.trySend(task)
        } else {
            synchronized(deferred) {
                cancelDeferredSendLocked(deferKey)
                val pending = DeferredSend(task)
                deferred[deferKey] = pending
                if (deferMs <= 0) {
                    sendChannel.trySend(task)
                } else {
                    pending.job = scope.launch {
                        delay(deferMs)
                        synchronized(deferred) {
                            if (deferred[deferKey]?.task === task) sendChannel.trySend(task)
                        }
                    }
                }
            }
        }
        return task.result.await()
    }

    /** Discards a deferred send or its retry; an HTTP request already in flight cannot be recalled. */
    fun cancelDeferredSend(key: String) {
        synchronized(deferred) { cancelDeferredSendLocked(key) }
    }

    private fun cancelDeferredSendLocked(key: String) {
        deferred.remove(key)?.let {
            it.job?.cancel()
            it.task.result.complete(false)
        }
    }

    private suspend fun process(task: SendTask) {
        var attempt = 0
        var waits = 0
        var outageStartedMs = 0L
        while (true) {
            try {
                sendLock.withLock {
                    if (task.deferKey != null) {
                        val current = synchronized(deferred) {
                            deferred[task.deferKey]?.task === task
                        }
                        if (!current) return
                    }
                    postMessages(task)
                }
                if (task.deferKey != null) {
                    synchronized(deferred) {
                        if (deferred[task.deferKey]?.task === task) deferred.remove(task.deferKey)
                    }
                }
                task.result.complete(true)
                return
            } catch (e: CancellationException) {
                task.result.complete(false)
                throw e
            } catch (e: Exception) {
                if (e is IOException && e !is LoungeHttpException && outageBudgetMs > 0) {
                    val now = nowMs()
                    if (waits == 0) outageStartedMs = now
                    if (now - outageStartedMs < outageBudgetMs) {
                        if (!canSend()) {
                            // the session ended or is ending: no POST after the end
                            task.result.complete(false)
                            return
                        }
                        coalesceWhileWaiting()
                        waits++
                        val waitMs = outageWaitsMs.getOrNull(waits - 1) ?: outageWaitsMs.lastOrNull() ?: 1000
                        log.session("send-wait", "attempt" to waits, "waitMs" to waitMs)
                        try {
                            // the send lock was left before the exception reached this catch: the farewell and a refresh can take it
                            delay(waitMs)
                        } catch (c: CancellationException) {
                            task.result.complete(false)
                            throw c
                        }
                        if (!canSend()) {
                            task.result.complete(false)
                            return
                        }
                        coalesceWhileWaiting()
                        continue
                    }
                }
                attempt++
                if (attempt >= 2) {
                    // the retry after a token refresh failed as well
                    task.result.complete(false)
                    endSession(e)
                    return
                }
                try {
                    refreshToken()
                } catch (r: Exception) {
                    // a caller outside the cancelled scope must get its answer too
                    task.result.complete(false)
                    if (r is CancellationException) throw r
                    return
                }
            }
        }
    }

    /**
     * Takes every send queued behind the waiting one off the channel and, for each of the names in [COALESCED_NAMES], keeps
     * only the newest message of that name; the order of everything else is kept. A task left with no message answers false.
     * Keyed (deferred) tasks are never touched, nor are other message names (loungeScreenDisconnected, volume, up next, ...):
     * those keep their order and are bounded by the tolerance itself, because the wait ends within the budget and then the
     * session refreshes or ends and clears the queue. Runs only while a send waits: a healthy queue is never coalesced. The
     * waiting task itself is not in the deque, so its POST stays identical for the same-offset rule.
     */
    private fun coalesceWhileWaiting() {
        synchronized(heldBack) {
            while (true) heldBack.addLast(sendChannel.tryReceive().getOrNull() ?: break)
            val seen = HashSet<String>()
            for (index in heldBack.indices.reversed()) {
                val task = heldBack[index]
                if (task.deferKey != null) continue
                val kept = task.messages.asReversed().filter { it.name !in COALESCED_NAMES || seen.add(it.name) }.asReversed()
                when {
                    kept.size == task.messages.size -> Unit
                    kept.isEmpty() -> {
                        heldBack.removeAt(index)
                        task.result.complete(false)
                    }
                    else -> heldBack[index] = SendTask(kept, task.epoch, task.result, task.deferKey)
                }
            }
        }
    }

    /** POSTs [messages] outside the queue (the farewell). Not queued: callers hold the send lock, see [withSendLock]. */
    suspend fun postUnqueued(messages: List<OutgoingMessage>) {
        postMessages(SendTask(messages, epoch, CompletableDeferred()))
    }

    /** One message POST. Not queued: callers hold [sendLock]. */
    private suspend fun postMessages(task: SendTask) {
        val aid = if (task.epoch == epoch) task.messages.firstOrNull()?.aid else null
        val url = endpoints.bind + "?" + bind.toQueryString(BindType.SEND_MESSAGE, aid)
        val pairs = sendMessagePostData(task.messages, ofs)
        log.tx(task.messages)
        val request = Request.Builder().url(url).header("User-Agent", LoungeConstants.USER_AGENT)
            .post(formEncode(pairs).toRequestBody(formType)).build()
        execute(request).use { checkOk(it, request) }
        // only an accepted POST moves the offset: one whose answer was lost is sent again with the same one
        ofs += task.messages.size
    }

    /** Fails every queued and deferred send; the session is ending. */
    fun clearQueuedSends() {
        synchronized(deferred) {
            deferred.values.forEach {
                it.job?.cancel()
                it.task.result.complete(false)
            }
            deferred.clear()
        }
        synchronized(heldBack) {
            heldBack.forEach { it.result.complete(false) }
            heldBack.clear()
        }
        while (true) {
            val task = sendChannel.tryReceive().getOrNull() ?: break
            task.result.complete(false)
        }
    }
}

/** The state reports a newer one of the same name replaces while a send waits. */
private val COALESCED_NAMES = setOf("onStateChange", "nowPlaying")
