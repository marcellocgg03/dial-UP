package dev.t2u.lounge

// The incoming half of a lounge session (RPCConnection.js): the RPC long-poll GET, its reader, reopening the stream and
// riding out a network outage. Split out of Session.kt; Session keeps the lifecycle, the tokens and the pairing.

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * The RPC long-poll of one [Session]. [deliver] gets each decoded batch (the session updates the bind parameters and
 * hands it to its listener, so secrets are registered with the log as before); when the stream cannot be reopened,
 * [refreshToken] refreshes the lounge token.
 */
internal class RpcLongPoll(
    http: OkHttpClient,
    private val endpoints: LoungeEndpoints,
    private val bind: BindParams,
    private val log: LoungeLog,
    private val scope: CoroutineScope,
    /** Pause between failed RPC connects (a test shortens it). */
    private val rpcRetryDelayMs: Long,
    /**
     * Adaptation 47: how long the RPC reconnect keeps retrying the same token after network errors (not HTTP statuses)
     * before the token is refreshed. 0 turns the tolerance off.
     */
    private val rpcOutageBudgetMs: Long,
    /** Waits between those retries; the last value repeats. */
    private val rpcOutageWaitsMs: List<Long>,
    private val deliver: suspend (List<IncomingMessage>) -> Unit,
    private val refreshToken: suspend () -> Unit,
) {
    class RpcHandle(var job: Job? = null, @Volatile var call: Call? = null) {
        fun close() {
            call?.cancel()
            job?.cancel()
        }
    }

    private class Opened(val call: Call, val response: Response)

    /** The reader that is current, or null; a reader whose handle is no longer this one stops. */
    @Volatile
    var current: RpcHandle? = null
        private set

    /** Opens the stream and starts reading it. */
    suspend fun start() {
        startRpcReader(openRpcStream())
    }

    /** Closes the current stream and forgets it. */
    fun close() {
        current?.close()
        current = null
    }

    /** True while the RPC stream is open or being reopened. */
    fun isOpen(): Boolean = current?.job?.isActive == true

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
     * Wi-Fi outage of a minute or two does not cost the session its token (yt-cast-receiver's RPCConnection
     * terminates after 3 connects). An HTTP status failure or an exhausted budget throws, which takes the refresh path.
     */
    private suspend fun openRpcRidingOutOutage(handle: RpcHandle): Opened {
        var firstFailure = 0L
        var attempt = 0
        while (true) {
            try {
                return openRpcStream()
            } catch (e: IOException) {
                if (e is LoungeHttpException || current !== handle) throw e
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

    private val rpcClient: OkHttpClient by lazy {
        http.newBuilder().readTimeout(LoungeConstants.RPC_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS).build()
    }

    private fun startRpcReader(opened: Opened) {
        val handle = RpcHandle(call = opened.call)
        current = handle
        handle.job = scope.launch(Dispatchers.IO) { rpcLoop(opened, handle) }
    }

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
            if (current !== handle) return
            // a stream that dies at once must not turn into a busy loop
            if ((System.nanoTime() - started) / 1_000_000 < MIN_STREAM_MS) delay(rpcRetryDelayMs)
            log.session("rpc-reconnect")
            opened = try {
                openRpcRidingOutOutage(handle)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (current !== handle) return
                log.error("rpc-connect", e)
                scope.launch {
                    try {
                        refreshToken()
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
                                    deliver(batch)
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
}
