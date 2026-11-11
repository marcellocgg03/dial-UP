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

/**
 * The send queue of one [Session]: the channel and its consumer, the deferred (replaceable) sends and the POST of a
 * message batch. [sendLock] serialises the POSTs; [withSendLock] lends it to the session for the farewell and for the
 * token refresh, so a refresh never overlaps a send. A failed POST refreshes the token through [refreshToken] and retries
 * once; a second failure ends the session through [endSession].
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

    @Volatile
    private var epoch = 0

    private var ofs = 0

    private val sendConsumer: Job by lazy {
        scope.launch {
            for (task in sendChannel) process(task)
        }
    }

    /** Runs [block] holding the send lock: no POST is in flight meanwhile. */
    suspend fun <T> withSendLock(block: suspend () -> T): T = sendLock.withLock { block() }

    /** The bind session restarts: outgoing messages count from zero again. */
    fun resetOffset() {
        ofs = 0
    }

    /** A token refresh finished: sends queued before it go out with their AID cleared. */
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
                    if (r is CancellationException) throw r
                    task.result.complete(false)
                    return
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
        ofs += task.messages.size
        log.tx(task.messages)
        val request = Request.Builder().url(url).header("User-Agent", LoungeConstants.USER_AGENT)
            .post(formEncode(pairs).toRequestBody(formType)).build()
        execute(request).use { checkOk(it, request) }
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
        while (true) {
            val task = sendChannel.tryReceive().getOrNull() ?: break
            task.result.complete(false)
        }
    }
}
