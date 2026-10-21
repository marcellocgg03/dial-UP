package dev.t2u.lounge

// Port of yt-cast-receiver 2.1.0 lib/Player.js (MIT, Copyright (c) Patrick Kan).

import dev.t2u.upnp.EventSink
import dev.t2u.upnp.NoEvents
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** Volume as the sender sees it: level 0..100 and the muted flag. */
data class Volume(val level: Int, val muted: Boolean)

/** What the sender's next/previous buttons can do. */
data class NavInfo(val hasPrevious: Boolean, val hasNext: Boolean, val autoplayMode: AutoplayMode)

/** Everything a state message to the sender is built from. */
data class PlayerState(
    val status: PlayerStatus,
    val queue: PlaylistState,
    val position: Double,
    val duration: Double,
    val volume: Volume,
    val cpn: String,
)

/** [aid] is the id of the sender command this state answers (null for a state the player raised itself). */
data class PlayerStateEvent(val aid: Int?, val current: PlayerState, val previous: PlayerState?)

/** A listener must not break playback: its exceptions are caught and reported to [Player.events]. */
fun interface PlayerStateListener {
    suspend fun onState(event: PlayerStateEvent)
}

/**
 * The player state machine the lounge session drives. It leaves the actual playback to the do*
 * hooks (the renderer implements them against the UPnP speaker) and tells the sender about every
 * state change through [PlayerStateListener]s, and through [statusFlow] for the notification (D-06).
 *
 * Semantics are those of yt-cast-receiver's Player (PLAY-05): pause acts only while PLAYING, resume
 * only while PAUSED (or replays the current video when stopped), stop is a no-op returning true
 * when already STOPPED or IDLE, play on a PLAYING player stops it first. One deliberate difference:
 * a seek on a PAUSED player leaves it paused (yt-cast-receiver resumes it).
 */
abstract class Player {
    val queue: Playlist = Playlist()

    /** Client playback nonce: 16 lowercase hex characters from a random UUID. */
    val cpn: String = UUID.randomUUID().toString().replace("-", "").substring(0, 16)

    private val statusState = MutableStateFlow(PlayerStatus.IDLE)
    val status: PlayerStatus get() = statusState.value
    val statusFlow: StateFlow<PlayerStatus> = statusState.asStateFlow()

    /** Where player and queue report what happened; kind "player" and "playlist". */
    var events: EventSink = NoEvents
        set(value) {
            field = value
            queue.events = value
        }

    var zeroVolumeLevelOnMute: Boolean = false
        private set

    val autoplayMode: AutoplayMode get() = queue.autoplayMode

    private val listeners = CopyOnWriteArrayList<PlayerStateListener>()

    @Volatile
    private var previousState: PlayerState? = null

    fun addStateListener(listener: PlayerStateListener) {
        listeners.add(listener)
    }

    fun removeStateListener(listener: PlayerStateListener) {
        listeners.remove(listener)
    }

    // Playback hooks the renderer implements.
    abstract suspend fun doPlay(video: Video, position: Double): Boolean
    abstract suspend fun doPause(): Boolean
    abstract suspend fun doResume(): Boolean
    abstract suspend fun doStop(): Boolean
    abstract suspend fun doSeek(position: Double): Boolean
    abstract suspend fun doSetVolume(volume: Volume): Boolean
    abstract suspend fun doGetVolume(): Volume
    abstract suspend fun doGetPosition(): Double
    abstract suspend fun doGetDuration(): Double

    /**
     * Reports LOADING, calls doPlay, then reports PLAYING (or STOPPED when doPlay fails, so the
     * sender does not keep a loading spinner). A doPlay that throws counts as a failure.
     */
    suspend fun play(video: Video, position: Double = 0.0, aid: Int? = null): Boolean {
        if (status == PlayerStatus.PLAYING) stop()
        log("play", "video" to video.id, "position" to position)
        queue.setAsCurrent(video)
        setStatusAndEmit(PlayerStatus.LOADING, aid)
        val result = try {
            doPlay(video, position)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("playFailed", "error" to (e.message ?: e::class.simpleName))
            false
        }
        setStatusAndEmit(if (result) PlayerStatus.PLAYING else PlayerStatus.STOPPED, aid)
        return result
    }

    /** Pauses; false without calling doPause when nothing is playing. */
    suspend fun pause(aid: Int? = null): Boolean {
        log("pause")
        if (status == PlayerStatus.PLAYING) {
            val result = doPause()
            if (result) setStatusAndEmit(PlayerStatus.PAUSED, aid)
            return result
        }
        return false
    }

    /** Resumes a paused player; replays the current video when stopped; false when there is nothing to resume. */
    suspend fun resume(aid: Int? = null): Boolean {
        log("resume")
        if (status == PlayerStatus.PLAYING) {
            setStatusAndEmit(PlayerStatus.PLAYING, aid)
            return false
        } else if (status == PlayerStatus.PAUSED) {
            val result = doResume()
            if (result) setStatusAndEmit(PlayerStatus.PLAYING, aid)
            return result
        }
        val current = queue.current
        if (current != null) return play(current)
        return false
    }

    /** Stops; true without calling doStop when already STOPPED or IDLE. */
    suspend fun stop(aid: Int? = null): Boolean {
        if (status == PlayerStatus.STOPPED || status == PlayerStatus.IDLE) return true
        log("stop")
        val result = doStop()
        if (result) setStatusAndEmit(PlayerStatus.STOPPED, aid)
        return result
    }

    /**
     * Seeks while playing or paused: reports LOADING at the target first, then PLAYING. A paused player
     * stays paused (LOADING, then PAUSED at the new position, no resume): a deliberate deviation from
     * yt-cast-receiver 2.1.0, which resumes; the sender presses Play when it wants sound (plan 02-15, G1).
     */
    suspend fun seek(position: Double, aid: Int? = null): Boolean {
        if (status != PlayerStatus.PLAYING && status != PlayerStatus.PAUSED) return false
        log("seek", "position" to position)
        val before = getState()
        val fakeState = before.copy(position = position, status = PlayerStatus.LOADING)
        emitState(PlayerStateEvent(aid, fakeState, before))
        val result = doSeek(position)
        if (result) {
            previousState = fakeState
            if (before.status == PlayerStatus.PAUSED) {
                setStatusAndEmit(PlayerStatus.PAUSED, aid)
                return true
            }
            setStatusAndEmit(PlayerStatus.PLAYING, aid)
        }
        return result
    }

    /** Plays the next queued video; at the end plays the autoplay video when enabled and available, else stops. */
    suspend fun next(aid: Int? = null): Boolean {
        if (queue.isUpdating) {
            log("nextIgnored", "reason" to "queue is updating")
            return false
        }
        log("next")
        val nextVideo = queue.next()
        if (nextVideo == null) {
            if (autoplayMode == AutoplayMode.ENABLED) {
                val autoplayVideo = queue.autoplay
                if (autoplayVideo != null) {
                    log("autoplay", "video" to autoplayVideo.id)
                    return play(autoplayVideo, 0.0, aid)
                }
                log("noAutoplayVideo")
            } else {
                log("noAutoplayVideo", "reason" to "autoplay is not enabled")
            }
            stop(aid)
            return false
        }
        return play(nextVideo)
    }

    /** Plays the previous queued video; stops and returns false when there is none. */
    suspend fun previous(aid: Int? = null): Boolean {
        if (queue.isUpdating) {
            log("previousIgnored", "reason" to "queue is updating")
            return false
        }
        log("previous")
        val previousVideo = queue.previous()
        if (previousVideo == null) {
            log("noPreviousVideo")
            stop(aid)
            return false
        }
        return play(previousVideo, 0.0, aid)
    }

    /** Sends the volume to the speaker (level 0 when muting with zeroVolumeLevelOnMute) and reports the new state. */
    suspend fun setVolume(volume: Volume, aid: Int? = null): Boolean {
        val v = if (zeroVolumeLevelOnMute && volume.muted && volume.level > 0) volume.copy(level = 0) else volume
        log("setVolume", "level" to v.level, "muted" to v.muted)
        val before = getState()
        val result = doSetVolume(v)
        if (result) emitState(PlayerStateEvent(aid, getState(), before))
        return result
    }

    /** Back to IDLE with an empty queue. */
    suspend fun reset(aid: Int? = null) {
        log("reset")
        queue.reset()
        stop(aid)
        previousState = null
        try {
            setStatusAndEmit(PlayerStatus.IDLE, aid)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("resetEmitFailed", "error" to (e.message ?: e::class.simpleName))
        }
    }

    /** The speaker's volume with the level clamped to 0..100. */
    suspend fun getVolume(): Volume {
        val v = doGetVolume()
        return Volume(v.level.coerceIn(0, 100), v.muted)
    }

    suspend fun getPosition(): Double = doGetPosition()

    suspend fun getDuration(): Double = doGetDuration()

    /** Sets the mute policy; when turned on while muted at a level above 0, sets the level to 0. */
    suspend fun setZeroVolumeLevelOnMute(value: Boolean, aid: Int? = null) {
        zeroVolumeLevelOnMute = value
        val currentVolume = getVolume()
        if (value && currentVolume.muted && currentVolume.level > 0) {
            setVolume(currentVolume, aid)
        }
    }

    fun getNavInfo(): NavInfo = NavInfo(queue.hasPrevious, queue.hasNext, autoplayMode)

    suspend fun getState(): PlayerState = PlayerState(
        status = status,
        queue = queue.getState(),
        position = getPosition(),
        duration = getDuration(),
        volume = getVolume(),
        cpn = cpn,
    )

    /**
     * Signals a change the player did not cause (the speaker stopped, the track ended, a position
     * moved): updates the status when [newStatus] is not null and tells the senders.
     */
    suspend fun notifyExternalStateChange(newStatus: PlayerStatus?) {
        setStatusAndEmit(newStatus)
    }

    private suspend fun setStatusAndEmit(newStatus: PlayerStatus?, aid: Int? = null) {
        val previous = previousState
        if (newStatus != null) statusState.value = newStatus
        val current = getState()
        previousState = current
        emitState(PlayerStateEvent(aid, current, previous))
    }

    private suspend fun emitState(event: PlayerStateEvent) {
        for (listener in listeners) {
            try {
                listener.onState(event)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("listenerError", "error" to (e.message ?: e::class.simpleName))
            }
        }
    }

    private fun log(type: String, vararg fields: Pair<String, Any?>) {
        try {
            events.event("player", mapOf<String, Any?>("type" to type) + fields)
        } catch (_: Exception) {
            // An event sink must not break playback.
        }
    }
}
