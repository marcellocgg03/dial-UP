package dev.t2u.lounge

// Derived from yt-cast-receiver 2.1.0 lib/app/DefaultPlaylistRequestHandler.js (MIT, Copyright (c) Patrick Kan);
// protocol reference: youtubei.js.
//
// Answers "what comes before and after this video, and what is the autoplay video" with one
// TVHTML5 /youtubei/v1/next call. The InnerTube protocol is undocumented, so everything it needs lives in
// Constants.kt (InnerTubeEndpoints, InnerTubeConstants) and the response is read with tolerant JsonElement helpers.

import dev.t2u.upnp.EventSink
import dev.t2u.upnp.NoEvents
import dev.t2u.upnp.objectOrNull
import dev.t2u.upnp.stringOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Asks YouTube's InnerTube `/next` (as the TVHTML5 client, no API key) for the videos around a target.
 *
 * Every failure (HTTP error, malformed JSON, no network, oversized body, nothing found) gives
 * `PreviousNextVideos(null, null)` and never throws, except CancellationException, which propagates so a stale
 * refresh ends the call. Events carry only the video id, status, byte length and the top-level response keys: never a
 * body, a ctt or a visitorData, which could identify the user.
 *
 * The recently-asked guard replaces markWatched (a /player call plus a stats ping, not reproducible anonymously) only
 * as far as stopping a two-video Mix loop: at the last queue item an autoplay candidate that
 * was asked about in the last [InnerTubeConstants.RECENT_LIMIT] calls is dropped. It never applies to explicit
 * previous or next navigation inside a list.
 */
class InnerTubePlaylistRequestHandler(
    http: OkHttpClient,
    private val endpoints: InnerTubeEndpoints = InnerTubeEndpoints(),
    private val events: EventSink = NoEvents,
    private val visitorData: () -> String = VisitorData::generate,
) : PlaylistRequestHandler {
    private val client: OkHttpClient = http.newBuilder()
        .callTimeout(InnerTubeConstants.CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    @Volatile
    private var currentVisitor: String? = null

    private val recent = ArrayDeque<String>()

    override suspend fun getPreviousNextVideos(target: Video, playlist: Playlist): PreviousNextVideos {
        val empty = PreviousNextVideos(null, null)
        remember(target.id)
        return try {
            val visitor = currentVisitor ?: visitorData().also { currentVisitor = it }
            val body = buildBody(target, visitor).toString().toRequestBody(JSON_MEDIA_TYPE)
            val request = Request.Builder()
                .url(endpoints.next)
                .post(body)
                .header("X-Youtube-Client-Name", InnerTubeConstants.CLIENT_NAME_ID)
                .header("X-Youtube-Client-Version", InnerTubeConstants.CLIENT_VERSION)
                .header("X-Goog-Visitor-Id", visitor)
                .header("User-Agent", InnerTubeConstants.USER_AGENT)
                .header("Origin", InnerTubeConstants.ORIGIN)
                .build()
            val (status, text) = executeForBody(client.newCall(request))
            if (status !in 200..299) {
                log("innertubeHttpError", target, "status" to status, "bytes" to text.length)
                return empty
            }
            val root = Json.parseToJsonElement(text) as? JsonObject
            if (root == null) {
                log("innertubeNoObject", target, "status" to status, "bytes" to text.length)
                return empty
            }
            ((root["responseContext"] as? JsonObject)?.get("visitorData") as? JsonPrimitive)
                ?.takeIf { it.isString && it.content.isNotEmpty() }
                ?.let { currentVisitor = it.content }

            val found = parseNext(root, target)
            if (playlist.isLast)
                log("innertubeShape", target, "arrays" to videoArrays(root), "listId" to (target.context?.playlistId != null))
            val result = applyGuard(found, playlist, target)
            if (found.previous == null && found.next == null) {
                log(
                    "innertube",
                    target,
                    "status" to status,
                    "bytes" to text.length,
                    "keys" to root.keys.sorted().joinToString(","),
                    "found" to false,
                )
            } else {
                log(
                    "innertube",
                    target,
                    "status" to status,
                    "bytes" to text.length,
                    "previous" to (result.previous != null),
                    "next" to (result.next != null),
                )
            }
            result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Class name only: a parser's message can quote the response body.
            log("innertubeFailed", target, "error" to (e::class.simpleName ?: "Exception"))
            empty
        }
    }

    override fun reset() {
        synchronized(recent) { recent.clear() }
    }

    private fun remember(videoId: String) = synchronized(recent) {
        recent.remove(videoId)
        recent.addLast(videoId)
        while (recent.size > InnerTubeConstants.RECENT_LIMIT) recent.removeFirst()
    }

    /** Drops an autoplay candidate that was already asked about, but only at the last queue item (never previous). */
    private fun applyGuard(found: PreviousNextVideos, playlist: Playlist, target: Video): PreviousNextVideos {
        val next = found.next ?: return found
        if (!playlist.isLast) return found
        val repeated = synchronized(recent) { next.id in recent }
        if (!repeated) return found
        log("autoplayRepeat", target)
        return found.copy(next = null)
    }

    private fun log(type: String, target: Video, vararg fields: Pair<String, Any?>) {
        try {
            events.event("lounge", mapOf<String, Any?>("type" to type, "videoId" to target.id) + fields)
        } catch (_: Exception) {
            // An event sink must not break playback.
        }
    }

    private fun buildBody(target: Video, visitor: String): JsonObject {
        val zone = TimeZone.getDefault()
        val ctx = target.context
        return buildJsonObject {
            put("videoId", target.id)
            put("enableMdxAutoplay", true)
            put("isMdxPlayback", true)
            ctx?.playlistId?.let { put("playlistId", it) }
            ctx?.params?.let { put("params", it) }
            ctx?.index?.let { put("playlistIndex", it) }
            putJsonObject("context") {
                putJsonObject("client") {
                    put("clientName", InnerTubeConstants.CLIENT_NAME)
                    put("clientVersion", InnerTubeConstants.CLIENT_VERSION)
                    put("userAgent", InnerTubeConstants.USER_AGENT)
                    put("visitorData", visitor)
                    put("hl", "en")
                    put("gl", "US")
                    put("osName", InnerTubeConstants.OS_NAME)
                    put("osVersion", InnerTubeConstants.OS_VERSION)
                    put("platform", InnerTubeConstants.PLATFORM)
                    put("timeZone", zone.id)
                    put("utcOffsetMinutes", zone.getOffset(System.currentTimeMillis()) / 60000)
                    put("originalUrl", InnerTubeConstants.ORIGINAL_URL)
                }
                putJsonObject("user") {
                    put("enableSafetyMode", false)
                    put("lockedSafetyMode", false)
                    ctx?.ctt?.let { ctt ->
                        putJsonArray("credentialTransferTokens") {
                            add(
                                buildJsonObject {
                                    put("scope", "VIDEO")
                                    put("token", ctt)
                                },
                            )
                        }
                    }
                }
                putJsonObject("request") {
                    put("useSsl", true)
                    putJsonArray("internalExperimentFlags") {}
                }
            }
        }
    }

    /** Runs [call] without blocking a thread; cancelling the coroutine cancels the call. Returns status and body text. */
    private suspend fun executeForBody(call: Call): Pair<Int, String> = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val text = response.use { r ->
                            val source = r.body.source()
                            val limit = InnerTubeConstants.MAX_RESPONSE_BYTES
                            if (source.request(limit + 1)) throw IOException("response larger than $limit bytes")
                            source.readUtf8()
                        }
                        if (cont.isActive) cont.resume(response.code to text)
                    } catch (e: IOException) {
                        if (cont.isActive) cont.resumeWithException(e)
                    }
                }
            },
        )
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private val VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")

        /**
         * Spike diagnostic (25 entry window): every JSON array that holds at least three objects carrying a videoId,
         * as "path:count" with array indexes dropped. Only key names and counts, never ids or tokens.
         */
        fun videoArrays(root: JsonObject): String {
            val counts = sortedMapOf<String, Int>()
            fun hasVideoId(e: kotlinx.serialization.json.JsonElement): Boolean = when (e) {
                is JsonObject -> e.containsKey("videoId") || e.values.any { hasVideoId(it) }
                is JsonArray -> e.any { hasVideoId(it) }
                else -> false
            }
            fun walk(e: kotlinx.serialization.json.JsonElement, path: String, depth: Int) {
                if (depth > 24) return
                when (e) {
                    is JsonObject -> e.forEach { (k, v) -> walk(v, "$path/$k", depth + 1) }
                    is JsonArray -> {
                        val n = e.count { it is JsonObject && hasVideoId(it) }
                        if (n >= 3) counts[path] = n
                        e.forEach { walk(it, path, depth + 1) }
                    }
                    else -> {}
                }
            }
            walk(root, "", 0)
            return counts.entries.joinToString(",") { "${it.key}:${it.value}" }.take(1500)
        }

        /**
         * Reads previous, next and the autoplay video from a /next response. The explicit next wins; at the last item
         * of a list the next is the maybeHistory endpoint, and when no nextVideoRenderer exists the autoplay endpoint is
         * returned as next (the Node handler merges them; Playlist.autoplay is "next while at the last item").
         * Every shape difference degrades to "not found" and never throws.
         */
        fun parseNext(root: JsonObject, target: Video): PreviousNextVideos {
            val sets = root.path("contents", "singleColumnWatchNextResults", "autoplay", "autoplay")?.get("sets") as? JsonArray
                ?: return PreviousNextVideos(null, null)
            val set = sets.firstOrNull { ((it as? JsonObject)?.get("mode") as? JsonPrimitive)?.content == "NORMAL" } as? JsonObject
                ?: sets.firstOrNull() as? JsonObject
                ?: return PreviousNextVideos(null, null)

            val previous = set.watchEndpoint(listOf("previousVideoRenderer", "autoplayEndpointRenderer", "endpoint"))
            val next = set.watchEndpoint(
                listOf("nextVideoRenderer", "autoplayEndpointRenderer", "endpoint"),
                listOf("nextVideoRenderer", "maybeHistoryEndpointRenderer", "endpoint"),
            )
            val autoplay = set.watchEndpoint(
                listOf("autoplayVideoRenderer", "mdxAutoplayVideoRenderer", "navigationEndpoint"),
                listOf("autoplayVideoRenderer", "autonavEndpointRenderer", "endpoint"),
                listOf("autoplayVideoRenderer", "autoplayEndpointRenderer", "endpoint"),
            )
            return PreviousNextVideos(
                previous = toVideo(previous, target),
                next = toVideo(next, target) ?: toVideo(autoplay, target),
            )
        }

        /** The first existing watchEndpoint object among the key [paths]; a path ends at the object holding "watchEndpoint". */
        private fun JsonObject.watchEndpoint(vararg paths: List<String>): JsonObject? =
            paths.firstNotNullOfOrNull { p -> path(*p.toTypedArray())?.objectOrNull("watchEndpoint") }

        private fun JsonObject.path(vararg keys: String): JsonObject? =
            keys.fold(this as JsonObject?) { o, k -> o?.objectOrNull(k) }

        private fun toVideo(watch: JsonObject?, target: Video): Video? {
            watch ?: return null
            val id = watch.stringOrNull("videoId")?.takeIf { VIDEO_ID.matches(it) } ?: return null
            val ctt = watch.path("watchEndpointSupportedAuthorizationTokenConfig", "videoAuthorizationToken")
                ?.get("credentialTransferTokens").let { it as? JsonArray }
                ?.firstOrNull().let { it as? JsonObject }?.stringOrNull("token")
            return Video(
                id = id,
                client = target.client,
                context = VideoContext(
                    playlistId = watch.stringOrNull("playlistId"),
                    index = (watch["index"] as? JsonPrimitive)?.content?.toIntOrNull(),
                    ctt = ctt,
                    params = watch.stringOrNull("params"),
                ),
            )
        }
    }
}
