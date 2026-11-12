package dev.t2u.lounge

// Port of yt-cast-receiver 2.1.0 lib/app/Session.js (begin) and lib/app/RPCConnection.js
// (MIT, Copyright (c) Patrick Kan). The lounge transport for one client theme (YouTube `cl` or
// YouTube Music `m`): screen id, lounge token, bind init POST, then the RPC long-poll.
//
// Protocol-risk constraint: the lounge protocol is undocumented, so this code stays in its own module,
// reads protocol constants from Constants.kt and (from the logging step on) reports every message through LoungeLog.

import dev.t2u.upnp.EventSink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
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
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * One lounge session. [begin] performs generate_screen_id (unless a screen id is stored), get_lounge_token_batch,
 * the bind init POST and opens the RPC long-poll; incoming messages go to [onMessages], one batch at a time, in order.
 */
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
        sendQueue.resetOffset()
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

    /** True while the loungeStatus of a fresh bind session is delivered: it is not yet the server's list of remote controls. */
    @Volatile
    var deliveringInitStatus: Boolean = false
        private set

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
                // the phone may not have bound to the fresh session yet, so this status can omit a sender that is still there
                deliveringInitStatus = true
                try {
                    handleMessages(listOf(message))
                } finally {
                    deliveringInitStatus = false
                }
            }
        }
        // test that the RPC query can be built before connecting
        bind.toQueryString(BindType.RPC)
        rpcLongPoll.start()
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
            rpcLongPoll.close()
            sendQueue.clearQueuedSends()
            try {
                withTimeoutOrNull(FAREWELL_TIMEOUT_MS) {
                    sendQueue.withSendLock { sendQueue.postUnqueued(listOf(OutgoingMessage.loungeScreenDisconnected())) }
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
            val old = rpcLongPoll.current
            try {
                sendQueue.withSendLock {
                    bind.reset()
                    try {
                        connect(useStore = false)
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        throw SessionException("(${client.displayName}) Error while refreshing lounge token", e)
                    }
                    // before the lock is released, so a send waiting for it already sees the new epoch and goes out without its AID
                    sendQueue.nextEpoch()
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                old?.close()
                end(e)
                throw e
            }
            old?.close()
            if (status == SessionStatus.REFRESHING) status = SessionStatus.RUNNING else rpcLongPoll.current?.close()
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

    private val refreshMutex = Mutex()

    private val sendQueue = SessionSendQueue(
        scope, endpoints, bind, log, formType,
        execute = ::execute,
        canSend = { status == SessionStatus.RUNNING || status == SessionStatus.REFRESHING },
        refreshToken = ::refreshLoungeToken,
        endSession = { end(it) },
    )

    /**
     * Sends [messages] in queue order. With [deferKey], the send waits [deferMs]; a later call with the same key
     * replaces it and the replaced call returns false. A zero interval queues it immediately but still permits
     * replacement before it is sent. Returns true when the POST was accepted without being superseded.
     */
    suspend fun sendMessage(
        messages: List<OutgoingMessage>,
        deferKey: String? = null,
        deferMs: Long = LoungeConstants.VOLUME_DEFER_MS,
    ): Boolean = sendQueue.sendMessage(messages, deferKey, deferMs)

    /** Discards a deferred send or its retry; an HTTP request already in flight cannot be recalled. */
    fun cancelDeferredSend(key: String) = sendQueue.cancelDeferredSend(key)

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
        val id = checkNotNull(screenId) { "getLoungeToken called before a screen id exists" }
        val body = formEncode(listOf("screen_ids" to id)).toRequestBody(formType)
        val request = Request.Builder()
            .url(endpoints.getLoungeTokenBatch)
            .header("User-Agent", LoungeConstants.USER_AGENT)
            .post(body)
            .build()
        val text = execute(request).use { r ->
            checkOk(r, request)
            withContext(Dispatchers.IO) { String(r.body.bytes(), Charsets.UTF_8) }
        }
        val screen = try {
            checkNotNull(Json.parseToJsonElement(text).jsonObject["screens"]) { "no screens key" }.jsonArray[0].jsonObject
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

    /**
     * Updates the bind parameters (AID, SID, gsessionid) and hands the batch to the listener. A listener failure is
     * reported, never fatal.
     */
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

    private val rpcLongPoll = RpcLongPoll(
        http, endpoints, bind, log, scope, rpcRetryDelayMs, rpcOutageBudgetMs, rpcOutageWaitsMs,
        deliver = ::handleMessages,
        refreshToken = ::refreshLoungeToken,
    )

    /** True while the RPC stream is open or being reopened. */
    internal fun rpcOpen(): Boolean = rpcLongPoll.isOpen()

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
}

internal fun checkOk(response: Response, request: Request) {
    if (!response.isSuccessful) throw LoungeHttpException(response.code, request.url.encodedPath)
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
