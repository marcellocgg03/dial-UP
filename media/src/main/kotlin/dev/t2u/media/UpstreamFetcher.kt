package dev.t2u.media

// Kotlin original (the Node proxy uses node:http directly). GPL-3.0-only.

import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * How the media server reaches YouTube's own audio URL for a `direct` token. :media has no HTTP client
 * dependency, so the implementation (OkHttp, in :renderer) is injected.
 *
 * The implementation decides which hosts it will talk to and how redirects are handled; the media server only
 * relays. It must throw [java.io.IOException] for a refused host or a transport failure, and the exception text
 * must not carry the full URL (signed URLs are secrets).
 */
interface UpstreamFetcher {
    suspend fun fetch(request: UpstreamRequest): UpstreamResponse
}

/** One upstream request. [headers] holds exactly what is sent; nothing else is added by the server. */
data class UpstreamRequest(val url: String, val method: String, val headers: Map<String, String>) {
    // The URL is signed: keep it out of logs and exception messages that print the request.
    override fun toString(): String = "UpstreamRequest(method=$method, headers=${headers.keys})"
}

/**
 * An upstream answer. [headers] has lower-cased names. [body] is null for HEAD and for answers without one.
 * [close] closes the body and runs [onClose] exactly once, however often it is called; closing before the body is
 * read to its end abandons the transfer and releases the connection.
 */
class UpstreamResponse(
    val status: Int,
    val headers: Map<String, String>,
    val body: InputStream?,
    private val onClose: () -> Unit = {},
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            body?.close()
        } catch (_: Exception) {
            // closing is best effort; the connection is dropped either way
        } finally {
            onClose()
        }
    }
}
