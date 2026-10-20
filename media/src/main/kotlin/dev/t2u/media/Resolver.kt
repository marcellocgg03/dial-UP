package dev.t2u.media

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.net.URI

// Port of reference/tube2upnp/src/resolver.js for the Phase 1 measurements. Pure JVM (no android.*):
// the youtubedl-android adapter lives in :app behind YtDlpGateway. Deliberately no cache, no
// in-flight coalescing and no 403/410 re-resolve: that is PLAY-01 in Phase 2, and a memo cache would
// hide the raw yt-dlp timings this phase exists to measure.

/** resolver.js VIDEO_ID. A YouTube video ID is exactly 11 characters of this alphabet. */
val VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")

/** config.js ytdlpFormat default of the Node server, so results stay comparable. */
const val NODE_FORMAT = "bestaudio[ext=m4a]/bestaudio[acodec^=mp4a]/bestaudio/best"

private const val MAX_ECHO = 32

/** Refuses anything that is not an 11-character ID before any yt-dlp process can start (threat T-01-10). */
fun requireVideoId(id: String): String {
    require(VIDEO_ID.matches(id)) { "Refusing invalid video ID \"${id.take(MAX_ECHO)}\"" }
    return id
}

fun isValidVideoId(id: String): Boolean = VIDEO_ID.matches(id)

data class YtDlpOption(val name: String, val value: String?)

/** Discrete options plus the URL. The gateway must put a `--` between the two; the URL never sits in an option value. */
data class YtDlpCall(val options: List<YtDlpOption>, val url: String)

fun watchUrl(id: String): String = "https://www.youtube.com/watch?v=${requireVideoId(id)}"

/** resolver.js `#run` arguments (the `--js-runtimes` pair is added by youtubedl-android itself). */
fun dumpJsonCall(id: String, cacheDir: String?, verbose: Boolean): YtDlpCall {
    val url = watchUrl(id)
    val options = mutableListOf(
        YtDlpOption("--dump-json", null),
        YtDlpOption("--no-playlist", null),
        YtDlpOption("--no-warnings", null),
        YtDlpOption("--no-progress", null),
        YtDlpOption("-f", NODE_FORMAT),
    )
    if (cacheDir != null) options += YtDlpOption("--cache-dir", cacheDir)
    if (verbose) options += YtDlpOption("-v", null)
    return YtDlpCall(options, url)
}

/** resolver.js `downloadWithYtdlp` arguments for a plain download of the Node format. */
fun downloadCall(id: String, outTemplate: String, cacheDir: String?): YtDlpCall {
    val url = watchUrl(id)
    val options = mutableListOf(
        YtDlpOption("-f", NODE_FORMAT),
        YtDlpOption("--no-playlist", null),
        YtDlpOption("--no-warnings", null),
        YtDlpOption("--no-progress", null),
        YtDlpOption("--fixup", "never"),
        YtDlpOption("--no-part", null),
        YtDlpOption("--force-overwrites", null),
        YtDlpOption("-o", outTemplate),
    )
    if (cacheDir != null) options += YtDlpOption("--cache-dir", cacheDir)
    return YtDlpCall(options, url)
}

/**
 * [stderrTail] is the last few KiB (for the error text), [stderrHead] the first 64 KiB (for the
 * `-v` JS-runtime line, which yt-dlp prints before it extracts). Both may be empty.
 */
data class GatewayResult(
    val exitCode: Int,
    val stdout: String,
    val stderrTail: String,
    val wallMs: Long,
    val stderrHead: String = "",
)

interface YtDlpGateway {
    fun run(call: YtDlpCall): GatewayResult

    fun version(): String?

    /** [channel] is "stable" or "nightly"; returns a short status string such as DONE or ALREADY_UP_TO_DATE. */
    fun update(channel: String): String
}

data class ResolvedInfo(
    val url: String,
    val ext: String,
    val mime: String,
    val headers: Map<String, String>,
    val title: String,
    val artist: String,
    val album: String,
    val thumbnail: String,
    val duration: Double,
    val filesize: Long,
    val formatId: String?,
)

private val MIME_BY_EXT = mapOf(
    "m4a" to "audio/mp4",
    "mp4" to "video/mp4",
    "webm" to "audio/webm",
    "mp3" to "audio/mpeg",
    "opus" to "audio/ogg",
    "ogg" to "audio/ogg",
)

/** resolver.js mimeFor: a webm that carries video is video/webm. */
fun mimeFor(ext: String?, vcodec: String?): String {
    if (ext == "webm" && !vcodec.isNullOrEmpty() && vcodec != "none") return "video/webm"
    return MIME_BY_EXT[ext] ?: "application/octet-stream"
}

private fun JsonObject.str(key: String): String? {
    val e = this[key]
    return if (e is JsonPrimitive && e !is JsonNull) e.contentOrNull else null
}

private fun JsonObject.number(key: String): Double? {
    val e = this[key]
    return if (e is JsonPrimitive && e !is JsonNull) e.doubleOrNull ?: e.contentOrNull?.toDoubleOrNull() else null
}

private fun JsonObject.longNumber(key: String): Long? {
    val e = this[key]
    if (e !is JsonPrimitive || e is JsonNull) return null
    return e.longOrNull ?: e.doubleOrNull?.toLong() ?: e.contentOrNull?.toDoubleOrNull()?.toLong()
}

private fun JsonObject.headers(key: String): Map<String, String>? {
    val e = this[key] as? JsonObject ?: return null
    val out = LinkedHashMap<String, String>()
    for ((k, v) in e) {
        if (v is JsonPrimitive && v !is JsonNull) v.contentOrNull?.let { out[k] = it }
    }
    return out
}

/** resolver.js field extraction from `yt-dlp --dump-json`. */
fun parseInfo(json: String, id: String): ResolvedInfo {
    val root = Json.parseToJsonElement(json).jsonObject
    val fmt: JsonObject? = if (!root.str("url").isNullOrEmpty()) {
        root
    } else {
        (root["requested_formats"] as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            ?.firstOrNull { !it.str("url").isNullOrEmpty() }
    }
    val url = fmt?.str("url")
    if (fmt == null || url.isNullOrEmpty()) throw IllegalStateException("yt-dlp returned no direct URL")

    val ext = fmt.str("ext").orEmpty()
    return ResolvedInfo(
        url = url,
        ext = ext,
        mime = mimeFor(ext, fmt.str("vcodec")),
        headers = fmt.headers("http_headers") ?: root.headers("http_headers") ?: emptyMap(),
        title = root.str("track") ?: root.str("title") ?: id,
        artist = root.str("artist") ?: root.str("channel") ?: root.str("uploader") ?: "",
        album = root.str("album").orEmpty(),
        thumbnail = root.str("thumbnail").orEmpty(),
        duration = root.number("duration")?.takeIf { it.isFinite() } ?: 0.0,
        filesize = fmt.longNumber("filesize") ?: 0L,
        formatId = fmt.str("format_id"),
    )
}

private fun queryParam(url: String, name: String): String? {
    val query = try {
        URI(url).rawQuery
    } catch (_: Exception) {
        null
    } ?: return null
    for (pair in query.split('&')) {
        val eq = pair.indexOf('=')
        val key = if (eq < 0) pair else pair.substring(0, eq)
        if (key == name) return if (eq < 0) "" else pair.substring(eq + 1)
    }
    return null
}

/** The googlevideo `expire` query parameter in epoch seconds, or null. */
fun urlExpire(url: String): Long? = queryParam(url, "expire")?.toLongOrNull()?.takeIf { it != 0L }

/** Host of a URL, or null when it does not parse. Evidence records this instead of the signed URL (T-01-12). */
fun urlHost(url: String): String? = try {
    URI(url).host
} catch (_: Exception) {
    null
}

data class ResolveRecord(
    val id: String,
    val pass: String,
    val index: Int,
    val ok: Boolean,
    val wallMs: Long,
    val ext: String?,
    val formatId: String?,
    val urlHost: String?,
    val expire: Long?,
    val error: String?,
    val jsRuntimeLine: String?,
) {
    /** Never contains the full URL: only its host and `expire` value. */
    fun toJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("pass", pass)
        put("index", index)
        put("ok", ok)
        put("wallMs", wallMs)
        put("ext", ext)
        put("formatId", formatId)
        put("urlHost", urlHost)
        put("expire", expire)
        put("error", error)
        put("jsRuntimeLine", jsRuntimeLine)
    }
}

class Resolver(private val gateway: YtDlpGateway) {
    /** One uncached yt-dlp resolve. An invalid ID is refused here and never reaches the gateway. */
    fun resolveOnce(
        id: String,
        pass: String,
        index: Int,
        cacheDir: String?,
        verbose: Boolean,
    ): Pair<ResolveRecord, ResolvedInfo?> {
        if (!isValidVideoId(id)) {
            return ResolveRecord(id.take(MAX_ECHO), pass, index, false, 0, null, null, null, null, "invalid id", null) to null
        }
        val result = gateway.run(dumpJsonCall(id, cacheDir, verbose))
        val js = jsRuntimeLine(result)
        if (result.exitCode != 0) {
            return ResolveRecord(id, pass, index, false, result.wallMs, null, null, null, null, errorTail(result.stderrTail), js) to null
        }
        val info = try {
            parseInfo(result.stdout, id)
        } catch (e: Exception) {
            val why = e.message ?: e.javaClass.simpleName
            return ResolveRecord(id, pass, index, false, result.wallMs, null, null, null, null, why.take(300), js) to null
        }
        val record = ResolveRecord(
            id = id,
            pass = pass,
            index = index,
            ok = true,
            wallMs = result.wallMs,
            ext = info.ext,
            formatId = info.formatId,
            urlHost = urlHost(info.url),
            expire = urlExpire(info.url),
            error = null,
            jsRuntimeLine = js,
        )
        return record to info
    }

    companion object {
        /** The last 3 non-blank stderr lines joined by " | ", as resolver.js builds its error text. */
        fun errorTail(stderr: String): String =
            redactUrls(stderr.trim().lines().filter { it.isNotBlank() }.takeLast(3).joinToString(" | ")).take(600)

        private val URL_WITH_PATH = Regex("(https?://[^/\\s|]+)[^\\s|]*")

        /** Keeps the host of any URL in yt-dlp's error text, drops path and signed query (T-01-12). */
        fun redactUrls(text: String): String = URL_WITH_PATH.replace(text, "$1/...")

        /** yt-dlp prints "[debug] JS runtimes: ..." under -v. */
        fun jsRuntimeLine(result: GatewayResult): String? {
            for (text in listOf(result.stderrHead, result.stderrTail)) {
                val line = text.lineSequence().firstOrNull { it.contains("JS runtimes") }
                if (line != null) return line.trim().take(300)
            }
            return null
        }
    }
}
