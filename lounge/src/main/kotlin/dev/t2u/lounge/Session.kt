package dev.t2u.lounge

// Port of yt-cast-receiver 2.1.0 lib/app/Session.js (begin) and lib/app/RPCConnection.js
// (MIT, Copyright (c) Patrick Kan). The lounge transport for one client theme (YouTube `cl` or
// YouTube Music `m`): screen id, lounge token, bind init POST, then the RPC long-poll.
//
// CLAUDE.md protocol-risk constraint: the lounge protocol is undocumented, so this code stays in its own module,
// reads protocol constants from Constants.kt and (from the logging step on) reports every message through LoungeLog.

import dev.t2u.upnp.EventSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
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
        val storageKey = StoreKeys.mdxContext(client.theme)
        var screenFromStore = false
        if (screenId == null && dataStore != null) {
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
        try {
            establish(screenFromStore)
        } catch (e: Exception) {
            val error = SessionException("(${client.displayName}) Failed to establish session", e)
            end()
            throw error
        }
        status = SessionStatus.RUNNING
        if (dataStore != null) {
            try {
                dataStore.set(
                    storageKey,
                    JsonObject(mapOf("deviceId" to JsonPrimitive(bind.id), "screenId" to JsonPrimitive(screenId))),
                )
            } catch (_: Exception) {
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

    suspend fun end() {
        if (status == SessionStatus.STOPPED || status == SessionStatus.STOPPING) return
        status = SessionStatus.STOPPING
        try {
            rpc?.close()
            rpc = null
        } finally {
            bind.reset()
            status = SessionStatus.STOPPED
        }
    }

    // ---- lounge HTTP

    private suspend fun generateScreenId(): String {
        val request = Request.Builder().url(endpoints.generateScreenId).header("User-Agent", LoungeConstants.USER_AGENT).build()
        val text = http.newCall(request).await().use { r ->
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
        val text = http.newCall(request).await().use { r ->
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
        val text = http.newCall(request).await().use { r ->
            checkOk(r, request)
            withContext(Dispatchers.IO) { String(r.body.bytes(), Charsets.UTF_8) }
        }
        val messages = ArrayList<IncomingMessage>()
        lateinit var decoder: ChunkDecoder
        decoder = ChunkDecoder(onPayload = { messages.addAll(decoder.parseMessages(it)) })
        decoder.feed(text)
        return messages
    }

    private suspend fun handleMessages(messages: List<IncomingMessage>) {
        bind.updateWithMessages(messages)
        onMessages(messages, this)
    }

    // ---- RPC long-poll

    /** Opens the RPC GET; IO errors and 5xx are retried [LoungeConstants.RPC_MAX_RETRIES] times in all, a 4xx fails at once. */
    private suspend fun openRpcStream(): Opened {
        var lastError: Exception = IOException("RPC connection failed")
        for (attempt in 1..LoungeConstants.RPC_MAX_RETRIES) {
            val request = Request.Builder().url(endpoints.bind + "?" + bind.toQueryString(BindType.RPC))
                .header("User-Agent", LoungeConstants.USER_AGENT).get().build()
            val call = rpcClient.newCall(request)
            try {
                val response = call.await()
                if (response.isSuccessful) return Opened(call, response)
                val status = response.code
                response.close()
                lastError = LoungeHttpException(status, request.url.encodedPath)
                if (status < 500) throw lastError
            } catch (e: IOException) {
                lastError = e
                if (e is LoungeHttpException && e.status < 500) throw e
            }
            if (attempt < LoungeConstants.RPC_MAX_RETRIES) delay(rpcRetryDelayMs)
        }
        throw lastError
    }

    private class Opened(val call: Call, val response: Response)

    private val rpcClient: OkHttpClient by lazy {
        http.newBuilder().readTimeout(LoungeConstants.RPC_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS).build()
    }

    private fun startRpcReader(opened: Opened) {
        val handle = RpcHandle(call = opened.call)
        rpc = handle
        handle.job = scope.launch(Dispatchers.IO) { readStream(opened.response, handle) }
    }

    /** Reads one RPC response to its end, delivering each decoded batch before reading on. */
    private suspend fun readStream(response: Response, handle: RpcHandle) {
        response.use { r ->
            val utf8 = Utf8StreamDecoder()
            val pending = ArrayList<String>()
            lateinit var decoder: ChunkDecoder
            decoder = ChunkDecoder(onPayload = { pending.add(it) })
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
                                val batch = decoder.parseMessages(payload)
                                if (batch.isNotEmpty()) handleMessages(batch)
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

/**
 * The WHATWG application/x-www-form-urlencoded serializer URLSearchParams uses: alphanumerics and `*-._` stay,
 * a space becomes `+`, everything else is %XX of its UTF-8 bytes.
 */
fun formEncode(pairs: List<Pair<String, String>>): String = pairs.joinToString("&") { (k, v) -> formComponent(k) + "=" + formComponent(v) }

private const val HEX = "0123456789ABCDEF"

private fun formComponent(s: String): String {
    val sb = StringBuilder(s.length + 8)
    for (b in s.toByteArray(Charsets.UTF_8)) {
        val c = b.toInt() and 0xFF
        when {
            (c in 'A'.code..'Z'.code) || (c in 'a'.code..'z'.code) || (c in '0'.code..'9'.code) ||
                c == '*'.code || c == '-'.code || c == '.'.code || c == '_'.code -> sb.append(c.toChar())
            c == ' '.code -> sb.append('+')
            else -> sb.append('%').append(HEX[c shr 4]).append(HEX[c and 0xF])
        }
    }
    return sb.toString()
}
