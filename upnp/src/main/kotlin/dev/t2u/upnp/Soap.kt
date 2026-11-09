package dev.t2u.upnp

// Port of tube2upnp src/soap.js, GPL-3.0-only.
//
// Minimal UPnP SOAP client. Replaces upnp-device-client's callAction, which crashed the whole process
// when a renderer returned a fault without <errorDescription> (seen in the wild on Pause) and sent
// Content-Length as the character count instead of the byte count, truncating non-ASCII metadata.
// The request body here is a ByteArray, so OkHttp sets the byte-exact Content-Length.

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * [code] is "ENETWORK" (no answer), "ENOACTION" (UPnP error 401) or "EUPNP" (any other fault);
 * [statusCode] is the HTTP status and [errorCode] the UPnP errorCode text ("" when none).
 */
class UpnpError(
    message: String,
    val code: String?,
    val statusCode: Int? = null,
    val errorCode: String? = null,
) : Exception(message)

data class UpnpService(
    val serviceType: String,
    val controlURL: String,
    val scpdURL: String,
    val eventSubURL: String,
)

data class SoapFault(val code: String, val desc: String)

data class SinkProtocol(
    val protocol: String,
    val network: String,
    val contentFormat: String,
    val additionalInfo: String,
)

/** Common UPnP AVTransport error codes, for readable logs. */
val ERROR_NAMES: Map<String, String> = mapOf(
    "401" to "Invalid Action",
    "402" to "Invalid Args",
    "501" to "Action Failed",
    "701" to "Transition not available",
    "702" to "No contents",
    "703" to "Read error",
    "704" to "Format not supported for playback",
    "706" to "Illegal seek target",
    "710" to "Seek mode not supported",
    "714" to "Illegal MIME-type",
    "716" to "Resource not found",
)

fun soapEscape(v: Any?): String {
    if (v == null) return ""
    val s = v.toString()
    val sb = StringBuilder(s.length + 16)
    for (c in s) {
        when (c) {
            '&' -> sb.append("&amp;")
            '<' -> sb.append("&lt;")
            '>' -> sb.append("&gt;")
            '"' -> sb.append("&quot;")
            '\'' -> sb.append("&apos;")
            else -> sb.append(c)
        }
    }
    return sb.toString()
}

fun buildEnvelope(serviceType: String, action: String, params: List<Pair<String, Any?>>): String {
    val args = params.joinToString("") { (k, v) -> "<$k>${soapEscape(v)}</$k>" }
    return "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
        "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
        "<s:Body><u:$action xmlns:u=\"$serviceType\">$args</u:$action></s:Body></s:Envelope>"
}

private val XML_MEDIA_TYPE = "text/xml; charset=\"utf-8\"".toMediaType()

/**
 * [http] must be [lanHttpClient] or derived from it with newBuilder(), so SOAP can only reach
 * private LAN addresses; the no-argument constructor does exactly that.
 */
class SoapClient(private val http: OkHttpClient) {
    constructor() : this(lanHttpClient())


    /**
     * POSTs one SOAP action and returns the children of the response element as a name -> text map.
     * Throws [UpnpError]; never anything else for a bad renderer. Cancelling the coroutine cancels the HTTP call.
     */
    suspend fun callAction(
        service: UpnpService,
        action: String,
        params: List<Pair<String, Any?>> = emptyList(),
        timeoutMs: Long = 8000,
    ): Map<String, String> {
        val body = buildEnvelope(service.serviceType, action, params).toByteArray(Charsets.UTF_8)
        val request = try {
            Request.Builder()
                .url(service.controlURL)
                .header("SOAPACTION", "\"${service.serviceType}#$action\"")
                .post(body.toRequestBody(XML_MEDIA_TYPE))
                .build()
        } catch (e: IllegalArgumentException) {
            throw UpnpError("$action: bad control URL (${e.message})", "ENETWORK")
        }
        val call = http.newCall(request)
        call.timeout().timeout(timeoutMs, TimeUnit.MILLISECONDS)

        val (status, text) = try {
            executeForText(call)
        } catch (e: IOException) {
            val timedOut = e is InterruptedIOException || e.message?.contains("timeout", ignoreCase = true) == true
            throw UpnpError("$action: ${if (timedOut) "timed out" else (e.message ?: e.javaClass.simpleName)}", "ENETWORK")
        } catch (e: IllegalArgumentException) {
            throw UpnpError("$action: ${e.message}", "ENETWORK")
        }

        if (status !in 200..299) {
            val (code, desc) = parseFault(text)
            val name = desc.ifEmpty { ERROR_NAMES[code] ?: "HTTP $status" }
            throw UpnpError(
                "$action: $name${if (code.isNotEmpty()) " ($code)" else ""}",
                code = if (code == "401") "ENOACTION" else "EUPNP",
                statusCode = status,
                errorCode = code,
            )
        }
        return try {
            parseResponse(text, action)
        } catch (_: Exception) {
            emptyMap() // Some renderers return junk on success; the call still worked.
        }
    }
}

/**
 * Runs [call] without blocking a thread and returns the HTTP status and the whole body as text.
 * Cancelling the coroutine cancels the call. Shared by [SoapClient] and [fetchDescription].
 */
internal suspend fun executeForText(call: Call): Pair<Int, String> = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { call.cancel() }
    call.enqueue(
        object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val text = response.use { it.body.string() }
                    if (cont.isActive) cont.resume(response.code to text)
                } catch (e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }
            }
        },
    )
}

private fun localName(tag: String): String = tag.substringAfterLast(':')

/** Collects the children of the <u:{action}Response> element into a plain map (local names, trimmed text). */
fun parseResponse(xml: String, action: String): Map<String, String> {
    val doc = Jsoup.parse(xml, "", Parser.xmlParser())
    val resp = doc.getAllElements().firstOrNull {
        val t = it.tagName()
        t == "${action}Response" || t.endsWith(":${action}Response")
    } ?: return emptyMap()
    val out = LinkedHashMap<String, String>()
    for (child in resp.children()) out[localName(child.tagName())] = child.text().trim()
    return out
}

/** Best-effort fault parsing: never throws, whatever the renderer sends. */
fun parseFault(xml: String): SoapFault = try {
    val doc = Jsoup.parse(xml, "", Parser.xmlParser())
    var code = ""
    var desc = ""
    for (e in doc.getAllElements()) {
        when (localName(e.tagName())) {
            "errorCode" -> code = e.text().trim()
            "errorDescription" -> desc = e.text().trim()
            "faultstring" -> if (desc.isEmpty()) desc = e.text().trim()
        }
    }
    SoapFault(code, desc)
} catch (_: Exception) {
    SoapFault("", collapse(xml.take(200)))
}

private fun collapse(s: String): String = s.replace(Regex("\\s+"), " ").trim()

/** Parses GetProtocolInfo's Sink list into protocol/network/contentFormat/additionalInfo entries. */
fun parseSink(sink: String?): List<SinkProtocol> =
    (sink ?: "").split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { line ->
        val p = line.split(':')
        SinkProtocol(p.getOrElse(0) { "" }, p.getOrElse(1) { "" }, p.getOrElse(2) { "" }, p.getOrElse(3) { "" })
    }
