package dev.t2u.renderer

// Kotlin original, GPL-3.0-only. The Node proxy talks to any URL the resolver returns; here the host is checked
// first (plan 03-02, threat T-03-05; recorded as an adaptation in PARITY.md by plan 03-12).

import dev.t2u.media.UpstreamFetcher
import dev.t2u.media.UpstreamRequest
import dev.t2u.media.UpstreamResponse
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Which upstream URLs the direct proxy may fetch. */
fun interface UpstreamPolicy {
    fun allows(url: HttpUrl): Boolean

    companion object {
        /** https, no user info, the default port, and a host that is `googlevideo.com` or one of its subdomains. */
        val GOOGLEVIDEO: UpstreamPolicy = UpstreamPolicy { url ->
            val host = url.host.lowercase()
            url.isHttps && url.username.isEmpty() && url.password.isEmpty() && url.port == 443 &&
                (host == "googlevideo.com" || host.endsWith(".googlevideo.com"))
        }
    }
}

/**
 * [UpstreamFetcher] on OkHttp. [http] is the LAN-guarded client from `lanHttpClient()`; the client used here is
 * derived from it with `newBuilder`, so the SVC-05 cleartext guard still applies even if [policy] would allow a
 * public cleartext host. Redirects are handled here, not by OkHttp: at most one hop, and the target must pass
 * [policy] too. There is no call timeout, because the body streams for as long as the song plays.
 *
 * Exceptions name the host only, never the full (signed) URL.
 */
class OkHttpUpstreamFetcher(
    http: OkHttpClient,
    private val policy: UpstreamPolicy = UpstreamPolicy.GOOGLEVIDEO,
) : UpstreamFetcher {
    private val client: OkHttpClient = http.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    override suspend fun fetch(request: UpstreamRequest): UpstreamResponse {
        val first = request.url.toHttpUrlOrNull() ?: throw IOException("upstream URL is not valid")
        checkPolicy(first)
        var url = first
        var response = execute(url, request)
        if (response.code in REDIRECT_CODES) {
            val location = response.header("Location")
            response.close()
            val target = location?.let { url.resolve(it) } ?: throw IOException("redirect from ${first.host} without a usable Location")
            checkPolicy(target)
            url = target
            response = execute(url, request)
            if (response.code in REDIRECT_CODES) {
                response.close()
                throw IOException("too many redirects from ${first.host}")
            }
        }
        val headers = LinkedHashMap<String, String>()
        for ((name, values) in response.headers.toMultimap()) headers[name.lowercase()] = values.first()
        val body = if (request.method == "HEAD") null else response.body.byteStream()
        return UpstreamResponse(response.code, headers, body) { response.close() }
    }

    private fun checkPolicy(url: HttpUrl) {
        if (!policy.allows(url)) throw IOException("upstream host ${url.host} is not allowed")
    }

    private suspend fun execute(url: HttpUrl, request: UpstreamRequest): Response {
        val builder = Request.Builder().url(url).method(request.method, null)
        try {
            for ((name, value) in request.headers) builder.header(name, value)
        } catch (_: IllegalArgumentException) {
            throw IOException("upstream request has an invalid header")
        }
        val call = client.newCall(builder.build())
        return suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(
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
    }

    private companion object {
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}
