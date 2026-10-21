package dev.t2u.lounge

// Kotlin original, protocol reference: youtubei.js and yt-cast-receiver DefaultPlaylistRequestHandler (MIT).
//
// CAST-04 / CAST-05: answers "what comes before and after this video, and what is the autoplay video" with one
// TVHTML5 /youtubei/v1/next call. The InnerTube protocol is undocumented, so everything it needs lives in
// Constants.kt (InnerTubeEndpoints, InnerTubeConstants) and the response is read with tolerant JsonElement helpers.

import dev.t2u.upnp.EventSink
import dev.t2u.upnp.NoEvents
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
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

    override suspend fun getPreviousNextVideos(target: Video, playlist: Playlist): PreviousNextVideos {
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
        val (_, text) = executeForBody(client.newCall(request))
        val root = Json.parseToJsonElement(text) as? JsonObject ?: return PreviousNextVideos(null, null)
        return parseNext(root, target)
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
                        val text = response.use { it.body.string() }
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

        /** Reads previous and next from a /next response (the middle-item shape; Task 2 widens it). */
        fun parseNext(root: JsonObject, target: Video): PreviousNextVideos {
            val set = root.obj("contents")?.obj("singleColumnWatchNextResults")?.obj("autoplay")?.obj("autoplay")
                ?.get("sets").let { it as? JsonArray }?.firstOrNull() as? JsonObject
                ?: return PreviousNextVideos(null, null)
            val previous = set.obj("previousVideoRenderer")?.obj("autoplayEndpointRenderer")?.obj("endpoint")?.obj("watchEndpoint")
            val next = set.obj("nextVideoRenderer")?.obj("autoplayEndpointRenderer")?.obj("endpoint")?.obj("watchEndpoint")
            return PreviousNextVideos(toVideo(previous, target), toVideo(next, target))
        }

        private fun toVideo(watch: JsonObject?, target: Video): Video? {
            watch ?: return null
            val id = watch.str("videoId") ?: return null
            return Video(
                id = id,
                client = target.client,
                context = VideoContext(
                    playlistId = watch.str("playlistId"),
                    index = (watch["index"] as? JsonPrimitive)?.content?.toIntOrNull(),
                    params = watch.str("params"),
                ),
            )
        }

        private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

        private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    }
}
