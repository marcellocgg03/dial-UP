package dev.t2u.lounge

// Port of yt-cast-receiver 2.1.0 lib/app/Session.js (begin) and lib/app/RPCConnection.js
// (MIT, Copyright (c) Patrick Kan). The lounge transport for one client theme (YouTube `cl` or
// YouTube Music `m`): screen id, lounge token, bind init POST, then the RPC long-poll.
//
// Protocol-risk constraint: the lounge protocol is undocumented, so this code stays in its own module,
// reads protocol constants from Constants.kt and (from the logging step on) reports every message through LoungeLog.

import dev.t2u.upnp.EventSink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.security.SecureRandom
import java.util.Random
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class SessionStatus { STOPPED, STARTING, RUNNING, REFRESHING, STOPPING }

/** A lounge endpoint answered with a status other than 2xx. [path] has no query (it would hold credentials). */
class LoungeHttpException(val status: Int, val path: String) : IOException("HTTP $status from $path")

/** The session could not be established or kept; the message names the client, the cause carries the detail. */
class SessionException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * One lounge session. [begin] performs generate_screen_id (unless a screen id is stored), get_lounge_token_batch,
 * the bind init POST and opens the RPC long-poll; incoming messages go to [onMessages], one batch at a time, in order.
 */
private const val FAREWELL_TIMEOUT_MS = 2000L
private const val MIN_STREAM_MS = 1000L

class Session(
    val client: Client,
    private val screenName: String,
    private val brand: String,
    private val model: String,
    private val dataStore: DataStore?,
    private val http: OkHttpClient,
    private val endpoints: LoungeEndpoints,
    private val sink: EventSink,
    private val scope: CoroutineScope,
    private val random: Random = SecureRandom(),
    /** Pause between failed RPC connects (a test shortens it). */
    private val rpcRetryDelayMs: Long = 1000,
    /**
     * Adaptation 47: how long the RPC reconnect keeps retrying the same token after network errors (not HTTP statuses)
     * before the token is refreshed. 0 turns the tolerance off.
     */
    private val rpcOutageBudgetMs: Long = 120_000,
    /** Waits between those retries; the last value repeats. */
    private val rpcOutageWaitsMs: List<Long> = listOf(2_000, 4_000, 8_000, 16_000, 30_000),
) {
    @Volatile
    var status: SessionStatus = SessionStatus.STOPPED
        internal set

    @Volatile
    var screenId: String? = null
        private set

    val deviceId: String get() = bind.id

    /** Called with each batch of incoming messages, sequentially, in arrival order. */
    @Volatile
    var onMessages: suspend (List<IncomingMessage>, Session) -> Unit = { _, _ -> }

    /** Called once when the session ended because of an error. */
    @Volatile
    var onTerminate: (Throwable) -> Unit = {}

    private val bind = BindParams(UUID.randomUUID().toString(), screenName, LoungeConstants.SCREEN_APP, client.theme, brand, model, random)

    private val log = LoungeLog(sink, client.theme)

    private val formType = "application/x-www-form-urlencoded;charset=UTF-8".toMediaType()

    private class RpcHandle(var job: Job? = null, @Volatile var call: Call? = null) {
        fun close() {
            call?.cancel()
            job?.cancel()
        }
    }

    @Volatile
    private var rpc: RpcHandle? = null

    private class LoungeToken(val token: String, val refreshIntervalMs: Long?)

    suspend fun begin() {
        if (status != SessionStatus.STOPPED) return
        status = SessionStatus.STARTING
        log.session("begin")
        try {
            connect(useStore = true)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            val error = SessionException("(${client.displayName}) Failed to establish session", e)
            end()
            throw error
        }
        status = SessionStatus.RUNNING
    }

    /**
     * Screen id (stored, or generated), lounge token, bind init, RPC connection, then the identity is stored.
     * [useStore] is false when refreshing: the screen id in memory is kept.
     */
    private suspend fun connect(useStore: Boolean) {
        val storageKey = StoreKeys.mdxContext(client.theme)
        var screenFromStore = false
        if (useStore && screenId == null && dataStore != null) {
            val stored = dataStore.get(storageKey) as? JsonObject
            if (stored != null) {
                bind.updateWithMdxContext((stored["deviceId"] as? JsonPrimitive)?.contentOrNull)
                val sid = (stored["screenId"] as? JsonPrimitive)?.contentOrNull
                if (!sid.isNullOrEmpty()) {
                    screenId = sid
                    screenFromStore = true
                }
            }
        }
        ofs = 0
        establish(screenFromStore)
        if (dataStore != null) {
            try {
                dataStore.set(
                    storageKey,
                    JsonObject(mapOf("deviceId" to JsonPrimitive(bind.id), "screenId" to JsonPrimitive(screenId))),
                )
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // a store that cannot write is the store's problem; the session works without it
            }
        }
    }

    /** generate_screen_id, token, bind init, then the RPC connection. */
    private suspend fun establish(screenFromStore: Boolean) {
        if (screenId == null) screenId = generateScreenId()
        val token = try {
            getLoungeToken()
        } catch (e: IOException) {
            if (!screenFromStore) throw e
            // the stored screen id may be what the token service refused: use a fresh one once
            screenId = generateScreenId()
            getLoungeToken()
        }
        bind.updateWithLoungeToken(token.token)
        registerSecrets()
        startRefreshTimer(token.refreshIntervalMs ?: LoungeConstants.TOKEN_REFRESH_FALLBACK_MS)
        for (message in getInitSessionMessages()) {
            if (message.name == "c" || message.name == "S") {
                bind.updateWithMessage(message)
            } else if (message.name == "loungeStatus") {
                handleMessages(listOf(message))
            }
        }
        // test that the RPC query can be built before connecting
        bind.toQueryString(BindType.RPC)
        startRpcReader(openRpcStream())
    }

    /**
     * Ends the session: closes the RPC stream, drops queued sends and tells the senders (best effort).
     * With [error] the session ended because of it and [onTerminate] is called.
     */
    suspend fun end(error: Throwable? = null) {
        if (status == SessionStatus.STOPPED || status == SessionStatus.STOPPING) return
        status = SessionStatus.STOPPING
        log.session("end", "error" to error?.let { it::class.simpleName })
        try {
            refreshTimer?.cancel()
            rpc?.close()
            rpc = null
            clearQueuedSends()
            try {
                withTimeoutOrNull(FAREWELL_TIMEOUT_MS) {
                    sendLock.withLock { postMessages(SendTask(listOf(OutgoingMessage.loungeScreenDisconnected()), epoch, CompletableDeferred())) }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // the lounge may not be reachable any more; we are quitting anyway
            }
        } finally {
            bind.reset()
            status = SessionStatus.STOPPED
        }
        if (error != null) onTerminate(error)
    }

    /**
     * Gets a fresh lounge token and a new bind session (Session.js refreshLoungeToken). Queued sends wait meanwhile
     * and go out with their AID cleared, because AID restarts after a refresh. A failure ends the session with that error.
     */
    suspend fun refreshLoungeToken() {
        if (!refreshMutex.tryLock()) return
        try {
            if (status != SessionStatus.RUNNING) return
            status = SessionStatus.REFRESHING
            log.session("refresh")
            refreshTimer?.cancel()
            val old = rpc
            try {
                sendLock.withLock {
                    bind.reset()
                    try {
                        connect(useStore = false)
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        throw SessionException("(${client.displayName}) Error while refreshing lounge token", e)
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                old?.close()
                end(e)
                throw e
            }
            old?.close()
            epoch++
            if (status == SessionStatus.REFRESHING) status = SessionStatus.RUNNING else rpc?.close()
        } finally {
            refreshMutex.unlock()
        }
    }

    // ---- lounge token refresh timer

    @Volatile
    private var refreshTimer: Job? = null

    /** Refreshes the token when it expires (Session.js: a timer set from refreshIntervalInMillis, 13 days as fallback). */
    private fun startRefreshTimer(intervalMs: Long) {
        refreshTimer?.cancel()
        refreshTimer = scope.launch {
            delay(intervalMs)
            // a separate coroutine: the refresh replaces this timer
            scope.launch {
                try {
                    refreshLoungeToken()
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    // refreshLoungeToken already ended the session and called onTerminate
                }
            }
        }
    }

    // ---- pairing code

    /** Registers a TV pairing code with this screen (Session.js registerPairingCode). Never logs the code. */
    suspend fun registerPairingCode(code: String) {
        val screen = screenId ?: throw IncompleteLoungeDataException("Missing data required to register pairing code", listOf("screenId"))
        log.addSecret(code)
        val form = formEncode(
            listOf(
                "access_type" to "permanent",
                "app" to LoungeConstants.SCREEN_APP,
                "pairing_code" to code,
                "screen_id" to screen,
                "screen_name" to screenName,
                "device_id" to bind.id,
            ),
        )
        val request = Request.Builder().url(endpoints.registerPairingCode).header("User-Agent", LoungeConstants.USER_AGENT)
            .post(form.toRequestBody(formType)).build()
        execute(request).use { checkOk(it, request) }
    }

    // ---- sending

    private class SendTask(
        val messages: List<OutgoingMessage>,
        val epoch: Int,
        val result: CompletableDeferred<Boolean>,
        val deferKey: String? = null,
    )

    private class DeferredSend(val task: SendTask, var job: Job? = null)

    private val sendChannel = Channel<SendTask>(Channel.UNLIMITED)
    private val sendLock = Mutex()
    private val refreshMutex = Mutex()
    private val deferred = HashMap<String, DeferredSend>()

    @Volatile
    private var epoch = 0

    private var ofs = 0

    private val sendConsumer: Job by lazy {
        scope.launch {
            for (task in sendChannel) process(task)
        }
    }

    /**
     * Sends [messages] in queue order. With [deferKey], the send waits [deferMs]; a later call with the same key
     * replaces it and the replaced call returns false. A zero interval queues it immediately but still permits
     * replacement before it is sent. Returns true when the POST was accepted without being superseded.
     */
    suspend fun sendMessage(
        messages: List<OutgoingMessage>,
        deferKey: String? = null,
        deferMs: Long = LoungeConstants.VOLUME_DEFER_MS,
    ): Boolean {
        if (status != SessionStatus.RUNNING && status != SessionStatus.REFRESHING) return false
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
                    end(e)
                    return
                }
                try {
                    refreshLoungeToken()
                } catch (r: Exception) {
                    if (r is CancellationException) throw r
                    task.result.complete(false)
                    return
                }
            }
        }
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

    private fun clearQueuedSends() {
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

    // ---- lounge HTTP

    private suspend fun generateScreenId(): String {
        val request = Request.Builder().url(endpoints.generateScreenId).header("User-Agent", LoungeConstants.USER_AGENT).build()
        val text = execute(request).use { r ->
            checkOk(r, request)
            withContext(Dispatchers.IO) { String(r.body.bytes(), Charsets.UTF_8) }
        }
        val id = text.trim()
        if (id.isEmpty()) throw IOException("generate_screen_id returned an empty body")
        return id
    }

    private suspend fun getLoungeToken(): LoungeToken {
        val body = formEncode(listOf("screen_ids" to screenId!!)).toRequestBody(formType)
        val request = Request.Builder().url(endpoints.getLoungeTokenBatch).header("User-Agent", LoungeConstants.USER_AGENT).post(body).build()
        val text = execute(request).use { r ->
            checkOk(r, request)
            withContext(Dispatchers.IO) { String(r.body.bytes(), Charsets.UTF_8) }
        }
        val screen = try {
            Json.parseToJsonElement(text).jsonObject["screens"]!!.jsonArray[0].jsonObject
        } catch (e: Exception) {
            throw IOException("lounge token data error")
        }
        val token = (screen["loungeToken"] as? JsonPrimitive)?.contentOrNull
        if (token.isNullOrEmpty()) throw IOException("lounge token data error")
        val interval = (screen["refreshIntervalInMillis"] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 }
        return LoungeToken(token, interval)
    }

    private suspend fun getInitSessionMessages(): List<IncomingMessage> {
        val url = endpoints.bind + "?" + bind.toQueryString(BindType.INIT_SESSION)
        val request = Request.Builder().url(url).header("User-Agent", LoungeConstants.USER_AGENT)
            .post(formEncode(listOf("count" to "0")).toRequestBody(formType)).build()
        val text = execute(request).use { r ->
            checkOk(r, request)
            withContext(Dispatchers.IO) { String(r.body.bytes(), Charsets.UTF_8) }
        }
        val messages = ArrayList<IncomingMessage>()
        lateinit var decoder: ChunkDecoder
        decoder = ChunkDecoder(
            onPayload = {
                log.raw(it)
                messages.addAll(decoder.parseMessages(it))
            },
            onGarbage = { log.problem("decode", it) },
        )
        decoder.feed(text)
        log.rx(messages)
        return messages
    }

    /** Updates the bind parameters (AID, SID, gsessionid) and hands the batch to the listener. A listener failure is reported, never fatal. */
    private suspend fun handleMessages(messages: List<IncomingMessage>) {
        bind.updateWithMessages(messages)
        registerSecrets()
        try {
            onMessages(messages, this)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            log.error("onMessages", e)
        }
    }

    // ---- RPC long-poll

    /** Opens the RPC GET; IO errors and 5xx are retried [LoungeConstants.RPC_MAX_RETRIES] times in all, a 4xx fails at once. */
    private suspend fun openRpcStream(): Opened {
        var lastError: Exception = IOException("RPC connection failed")
        for (attempt in 1..LoungeConstants.RPC_MAX_RETRIES) {
            val request = Request.Builder().url(endpoints.bind + "?" + bind.toQueryString(BindType.RPC))
                .header("User-Agent", LoungeConstants.USER_AGENT).get().build()
            val call = rpcClient.newCall(request)
            val started = System.nanoTime()
            try {
                val response = call.await()
                log.http("GET", request.url.encodedPath, response.code, (System.nanoTime() - started) / 1_000_000)
                if (response.isSuccessful) return Opened(call, response)
                val status = response.code
                response.close()
                lastError = LoungeHttpException(status, request.url.encodedPath)
                if (status < 500) throw lastError
            } catch (e: IOException) {
                lastError = e
                if (e is LoungeHttpException && e.status < 500) throw e
                if (e !is LoungeHttpException) log.http("GET", request.url.encodedPath, -1, (System.nanoTime() - started) / 1_000_000)
            }
            if (attempt < LoungeConstants.RPC_MAX_RETRIES) delay(rpcRetryDelayMs)
        }
        throw lastError
    }

    /**
     * [openRpcStream], and when it fails with a network error (an IOException that is not the lounge answering with a
     * status) the same token is retried with growing waits for up to [rpcOutageBudgetMs] since the first failure, so a
     * Wi-Fi outage of a minute or two does not cost the session its token (plan 05-10; yt-cast-receiver's RPCConnection
     * terminates after 3 connects). An HTTP status failure or an exhausted budget throws, which takes the refresh path.
     */
    private suspend fun openRpcRidingOutOutage(handle: RpcHandle): Opened {
        var firstFailure = 0L
        var attempt = 0
        while (true) {
            try {
                return openRpcStream()
            } catch (e: IOException) {
                if (e is LoungeHttpException || rpc !== handle) throw e
                val now = System.nanoTime()
                if (attempt == 0) firstFailure = now
                if ((now - firstFailure) / 1_000_000 >= rpcOutageBudgetMs) throw e
                val wait = rpcOutageWaitsMs.getOrNull(attempt) ?: rpcOutageWaitsMs.lastOrNull() ?: rpcRetryDelayMs
                attempt++
                log.session("rpc-wait", "attempt" to attempt, "waitMs" to wait)
                delay(wait)
            }
        }
    }

    private class Opened(val call: Call, val response: Response)

    private val rpcClient: OkHttpClient by lazy {
        http.newBuilder().readTimeout(LoungeConstants.RPC_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS).build()
    }

    private fun startRpcReader(opened: Opened) {
        val handle = RpcHandle(call = opened.call)
        rpc = handle
        handle.job = scope.launch(Dispatchers.IO) { rpcLoop(opened, handle) }
    }

    /** True while the RPC stream is open or being reopened. */
    internal fun rpcOpen(): Boolean = rpc?.job?.isActive == true

    /**
     * Reads the stream; when it ends (the lounge closes it every few minutes) or times out, opens a new one. When
     * reopening fails after [LoungeConstants.RPC_MAX_RETRIES] connects (and, for network errors, after the outage budget
     * of [openRpcRidingOutOutage]) the lounge token is refreshed (RPCConnection.js 'terminate' then Session.js refreshLoungeToken).
     */
    private suspend fun rpcLoop(first: Opened, handle: RpcHandle) {
        var opened = first
        while (true) {
            val started = System.nanoTime()
            handle.call = opened.call
            readStream(opened.response, handle)
            currentCoroutineContext().ensureActive()
            if (rpc !== handle) return
            // a stream that dies at once must not turn into a busy loop
            if ((System.nanoTime() - started) / 1_000_000 < MIN_STREAM_MS) delay(rpcRetryDelayMs)
            log.session("rpc-reconnect")
            opened = try {
                openRpcRidingOutOutage(handle)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (rpc !== handle) return
                log.error("rpc-connect", e)
                scope.launch {
                    try {
                        refreshLoungeToken()
                    } catch (r: Exception) {
                        if (r is CancellationException) throw r
                        // refreshLoungeToken already ended the session and called onTerminate
                    }
                }
                return
            }
        }
    }

    /** Reads one RPC response to its end, delivering each decoded batch before reading on. */
    private suspend fun readStream(response: Response, handle: RpcHandle) {
        response.use { r ->
            val utf8 = Utf8StreamDecoder()
            val pending = ArrayList<String>()
            lateinit var decoder: ChunkDecoder
            decoder = ChunkDecoder(onPayload = { pending.add(it) }, onGarbage = { log.problem("decode", it) })
            val source = r.body.source()
            val buf = ByteArray(8192)
            try {
                coroutineScope {
                    // a blocked read is released by cancelling the call
                    val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
                        try {
                            awaitCancellation()
                        } finally {
                            handle.call?.cancel()
                        }
                    }
                    try {
                        while (true) {
                            ensureActive()
                            val n = source.read(buf, 0, buf.size)
                            if (n < 0) break
                            decoder.feed(utf8.decode(buf, 0, n))
                            for (payload in pending.toList()) {
                                log.raw(payload)
                                val batch = decoder.parseMessages(payload)
                                if (batch.isNotEmpty()) {
                                    log.rx(batch)
                                    handleMessages(batch)
                                }
                            }
                            pending.clear()
                        }
                    } finally {
                        watcher.cancel()
                    }
                }
            } catch (_: IOException) {
                // stream ended or timed out: the caller decides what to do
            }
        }
    }

    // ---- helpers

    /** Runs [request] through [http] and reports method, path (no query), status and time. */
    private suspend fun execute(request: Request): Response {
        val started = System.nanoTime()
        try {
            val response = http.newCall(request).await()
            log.http(request.method, request.url.encodedPath, response.code, (System.nanoTime() - started) / 1_000_000)
            return response
        } catch (e: IOException) {
            log.http(request.method, request.url.encodedPath, -1, (System.nanoTime() - started) / 1_000_000)
            throw e
        }
    }

    private fun registerSecrets() {
        bind.secrets().forEach { log.addSecret(it) }
        log.addSecret(screenId)
    }

    private fun checkOk(response: Response, request: Request) {
        if (!response.isSuccessful) throw LoungeHttpException(response.code, request.url.encodedPath)
    }
}

/** Awaits an OkHttp call; cancelling the coroutine cancels the call. */
internal suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(
        object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (cont.isActive) cont.resume(response) else response.close()
            }
        },
    )
}
