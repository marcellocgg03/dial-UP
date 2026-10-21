package dev.t2u.lounge

// Port of yt-cast-receiver 2.1.0 lib/app/Playlist.js and lib/app/Video.js (MIT, Copyright (c) Patrick Kan).

import dev.t2u.upnp.EventSink
import dev.t2u.upnp.NoEvents
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/** Where a [Video] sits in its playlist (lib/app/Video.js VideoContext). */
data class VideoContext(
    val playlistId: String? = null,
    val index: Int? = null,
    val ctt: String? = null,
    val params: String? = null,
)

/** A video the sender asked for. [id] is data only: it is validated before any process or URL uses it (T-02-28). */
data class Video(
    val id: String,
    val client: Client? = null,
    val context: VideoContext? = null,
)

/** A snapshot of the queue, as the sender is told about it. */
data class PlaylistState(
    val id: String?,
    val videoIds: List<String>,
    val previous: Video?,
    val current: Video?,
    val next: Video?,
    val autoplay: Video?,
)

/**
 * The player queue. It is told about the sender's playlist by `setPlaylist` and `updatePlaylist`
 * messages and asks a [PlaylistRequestHandler] for the previous, next and autoplay videos.
 *
 * Events (kind "playlist": playlistSet, playlistUpdated, playlistCleared, playlistAdded,
 * videoAdded, videoRemoved, videoSelected, autoplayModeChange, ...) go to [events]; the Node
 * event emitter has no listener consumer in the Kotlin port, status is derived from state instead.
 */
class Playlist {
    var events: EventSink = NoEvents

    /** Phase 2 uses the NoOp handler; Phase 3 sets the InnerTube one (CAST-05). */
    @Volatile
    var requestHandler: PlaylistRequestHandler = NoOpPlaylistRequestHandler

    @Volatile
    var id: String? = null
        private set

    @Volatile
    var videoIds: List<String> = emptyList()
        private set

    @Volatile
    var current: Video? = null
        private set

    @Volatile
    private var previousVideo: Video? = null

    @Volatile
    private var nextVideo: Video? = null

    @Volatile
    var autoplayMode: AutoplayMode = AutoplayMode.UNSUPPORTED
        private set

    @Volatile
    private var inFlight: Deferred<PreviousNextVideos?>? = null

    val length: Int get() = videoIds.size

    /** True while a previous/next request is in flight; Player.next and previous are ignored meanwhile. */
    val isUpdating: Boolean get() = inFlight != null

    val isLast: Boolean
        get() {
            val index = current?.context?.index ?: -1
            return index < 0 || index == length - 1
        }

    val hasPrevious: Boolean get() = previousVideo != null

    val hasNext: Boolean get() = !(nextVideo == null || isLast)

    /** The autoplay video: whatever follows the last video of the queue. */
    val autoplay: Video? get() = if (isLast) nextVideo else null

    suspend fun setAutoplayMode(value: AutoplayMode) {
        val old = autoplayMode
        autoplayMode = value
        if (old != value) {
            refreshPreviousNext()
            emit("autoplayModeChange", "from" to old.name, "to" to value.name)
        }
    }

    /**
     * Updates the queue with the payload of a `setPlaylist` or `updatePlaylist` message; any other
     * message name is ignored. A payload without listId clears the queue.
     */
    suspend fun updateByMessage(name: String, payload: JsonObject, client: Client?) {
        if (name != "setPlaylist" && name != "updatePlaylist") return

        val listId = payload["listId"]
        if (!listId.isTruthy()) {
            id = null
            current = null
            nextVideo = null
            previousVideo = null
            videoIds = emptyList()
            emit("playlistCleared")
            return
        }
        val playlistId = listId.text()
        id = playlistId
        val rawIds = payload["videoIds"]
        videoIds = if (rawIds.isTruthy()) rawIds.text().split(',') else emptyList()

        if (name == "setPlaylist") {
            val currentIndex = payload["currentIndex"]
            val videoId = payload["videoId"]
            current = if (currentIndex.isTruthy() && videoId.isTruthy()) {
                Video(
                    id = videoId.text(),
                    client = client,
                    context = VideoContext(
                        playlistId = playlistId,
                        index = parseInt(currentIndex),
                        ctt = payload["ctt"].takeIf { it.isTruthy() }?.text(),
                        params = payload["params"].takeIf { it.isTruthy() }?.text(),
                    ),
                )
            } else {
                null
            }
        } else {
            current?.let { cur ->
                val index = videoIds.indexOf(cur.id)
                current = if (index >= 0) {
                    val params = payload["params"].takeIf { it.isTruthy() }?.text()
                    cur.copy(context = (cur.context ?: VideoContext()).copy(playlistId = playlistId, index = index, params = params))
                } else {
                    null
                }
            }
        }

        refreshPreviousNext()
        emitPlaylistEvent(name, payload["eventDetails"])
    }

    /** Back to an empty queue with autoplay unsupported (Player.reset). */
    fun reset() {
        id = null
        videoIds = emptyList()
        current = null
        previousVideo = null
        autoplayMode = AutoplayMode.UNSUPPORTED
        requestHandler.reset()
    }

    /** Moves back to the previous video and returns it, or returns null when there is none. */
    suspend fun previous(): Video? {
        val target = previousVideo ?: return null
        current = target
        refreshPreviousNext()
        return current
    }

    /** Moves on to the next video and returns it, or returns null at the end (the autoplay video is not "next"). */
    suspend fun next(): Video? {
        if (!hasNext) return null
        current = nextVideo
        refreshPreviousNext()
        return current
    }

    fun getState(): PlaylistState = PlaylistState(
        id = id,
        videoIds = videoIds,
        previous = previousVideo,
        current = current,
        next = if (hasNext) nextVideo else null,
        autoplay = autoplay,
    )

    /**
     * Makes [video] the current one without asking the handler (the player is about to play it).
     * Returns true only when this is a hop: [video] differs from the current one and is the autoplay
     * video, so the caller should run [refreshAfterHop] once playback runs.
     */
    fun setAsCurrent(video: Video): Boolean {
        if (current?.id == video.id) return false
        val hop = autoplay?.id == video.id
        if (hop) nextVideo = null
        current = video
        return hop
    }

    /**
     * Asks the handler about the autoplay video the player just hopped to. An autoplay video comes from
     * the handler and has no playlist index, so the normal refresh would skip it and leave previous
     * stale and next empty after the first hop (RESEARCH Pitfall 3); this one is forced.
     */
    suspend fun refreshAfterHop() {
        refreshPreviousNext(force = true)
    }

    private fun abortRefresh() {
        inFlight?.cancel()
        inFlight = null
    }

    /**
     * Asks the handler for the videos around the current one. A refresh that starts while another is
     * suspended cancels the first, which then returns without touching previous or next. A current video
     * without a playlist index is skipped unless [force] is true (an autoplay hop, see [refreshAfterHop]).
     */
    private suspend fun refreshPreviousNext(force: Boolean = false) {
        abortRefresh()
        previousVideo = null
        nextVideo = null
        val cur = current ?: return
        if (!force && (cur.context?.index ?: -1) < 0) return

        var aborted = false
        val nav = coroutineScope {
            val request = async { requestHandler.getPreviousNextVideos(cur, this@Playlist) }
            inFlight = request
            try {
                request.await()
            } catch (e: CancellationException) {
                // Our own request was cancelled by a newer refresh (the scope is still active): not an error.
                if (request.isCancelled && isActive) {
                    aborted = true
                    null
                } else {
                    throw e
                }
            } finally {
                if (inFlight === request) inFlight = null
            }
        }
        if (aborted) return

        previousVideo = nav?.previous
        if (!isLast || autoplayMode == AutoplayMode.ENABLED) {
            nextVideo = nav?.next
        }
    }

    /** Maps the sender's eventDetails (when present and valid) onto the event type, else playlistSet/Updated. */
    private fun emitPlaylistEvent(messageName: String, eventDetails: JsonElement?) {
        var type: String? = null
        var videoId: String? = null
        if (eventDetails.isTruthy()) {
            try {
                val details = Json.parseToJsonElement(eventDetails.text()) as? JsonObject
                type = when ((details?.get("eventType") as? JsonPrimitive)?.content) {
                    "PLAYLIST_ADDED" -> "playlistAdded"
                    "PLAYLIST_CLEARED" -> "playlistCleared"
                    "PLAYLIST_SET" -> "playlistSet"
                    "VIDEO_ADDED" -> "videoAdded"
                    "VIDEO_REMOVED" -> "videoRemoved"
                    "VIDEO_SELECTED" -> "videoSelected"
                    else -> null
                }
                videoId = details?.get("videoId").takeIf { it.isTruthy() }?.text()
            } catch (e: Exception) {
                emit("eventDetailsParseError", "error" to (e.message ?: e::class.simpleName))
                type = null
            }
        }
        if (type == null) {
            type = if (messageName == "setPlaylist") "playlistSet" else "playlistUpdated"
            videoId = null
        }
        emit(type, "videoCount" to videoIds.size, "videoId" to videoId)
    }

    private fun emit(type: String, vararg fields: Pair<String, Any?>) {
        try {
            events.event("playlist", mapOf<String, Any?>("type" to type) + fields)
        } catch (_: Exception) {
            // An event sink must not break the queue.
        }
    }
}

/** JavaScript truthiness of a JSON value: null, "", false, 0 and NaN are false; every other string, number and object is true. */
internal fun JsonElement?.isTruthy(): Boolean = when (this) {
    null, JsonNull -> false
    is JsonPrimitive -> when {
        isString -> content.isNotEmpty()
        booleanOrNull != null -> booleanOrNull == true
        else -> doubleOrNull?.let { it != 0.0 && !it.isNaN() } ?: content.isNotEmpty()
    }
    else -> true
}

private fun JsonElement?.text(): String = (this as? JsonPrimitive)?.content ?: this.toString()

/** JavaScript parseInt(x, 10): the leading integer of the text, or null (NaN). */
private fun parseInt(value: JsonElement?): Int? =
    Regex("^\\s*([+-]?\\d+)").find(value.text())?.groupValues?.get(1)?.toIntOrNull()
