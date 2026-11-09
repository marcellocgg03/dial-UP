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

/**
 * Everything a state message to the sender is built from. [buffering] is true while the player is PLAYING but the
 * speaker has not yet started after a seek (or a Play that followed a paused seek): the sender is then told state 3
 * (loading), see [senderCode]. It is a flag, not a status, so a Pause from the sender still acts on a PLAYING player.
 */
data class PlayerState(
    val status: PlayerStatus,
    val queue: PlaylistState,
    val position: Double,
    val duration: Double,
    val volume: Volume,
    val cpn: String,
    val buffering: Boolean = false,
) {
    /** The state code the sender is told: 3 (loading) while [buffering], otherwise the status's own. */
    val senderCode: Int get() = if (buffering && status == PlayerStatus.PLAYING) PlayerStatus.LOADING.code else status.code
}

/**
 * [aid] identifies the sender command (null for an unsolicited state). [fromVolumeChange] identifies
 * the operation that produced the snapshots; other state fields can also change while it runs.
 */
data class PlayerStateEvent(
    val aid: Int?,
    val current: PlayerState,
    val previous: PlayerState?,
    val fromVolumeChange: Boolean = false,
)

/** A listener must not break playback: its exceptions are caught and reported to [Player.events]. */
fun interface PlayerStateListener {
    suspend fun onState(event: PlayerStateEvent)
}

/**
 * Called by [Player.reportPlaybackFailure] after the senders were told the player stopped. Exceptions are caught and
 * reported to [Player.events] like those of a [PlayerStateListener].
 */
fun interface PlaybackFailureListener {
    suspend fun onPlaybackFailure(reason: String)
}

/**
 * The player state machine the lounge session drives. It leaves the actual playback to the do*
 * hooks (the renderer implements them against the UPnP speaker) and tells the sender about every
 * state change through [PlayerStateListener]s, and through [statusFlow] for the notification.
 *
 * Semantics are those of yt-cast-receiver's Player: pause acts only while PLAYING, resume
 * only while PAUSED (or replays the current video when stopped), stop is a no-op returning true
 * when already STOPPED or IDLE, play on a PLAYING player stops it first. One deliberate difference:
 * a seek on a PAUSED player leaves it paused (yt-cast-receiver resumes it). A second one: [getNavInfo]
 * counts the autoplay video as a next video.
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

    private val failureListeners = CopyOnWriteArrayList<PlaybackFailureListener>()

    @Volatile
    private var previousState: PlayerState? = null

    /** Epoch milliseconds. The renderer overrides it with its injected clock so tests can move time by hand. */
    protected open fun clockMs(): Long = System.currentTimeMillis()

    /**
     * True while the speaker has accepted a seek (or a Play after a paused seek) but has not started playing yet. A PLAYING
     * player is then reported to the sender as loading ([PlayerState.buffering]) instead of playing, so the sender's bar does
     * not run ahead of a silent speaker. The renderer overrides it with its pending-seek hold; the
     * default is false, which keeps yt-cast-receiver's behaviour.
     */
    protected open fun seekHoldActive(): Boolean = false

    /**
     * Where the sender's seekbar should be now: the position of the last state emitted plus the time elapsed since, while
     * that state was PLAYING. Null before the first state.
     */
    fun senderPosition(): Double? {
        val t = told ?: return null
        if (t.status != PlayerStatus.PLAYING) return t.position
        return t.position + (clockMs() - t.atMs) / 1000.0
    }

    /** The last state emitted to the senders: the position the sender's bar was given, when, and the status it showed (LOADING while buffering). */
    private class Told(val position: Double, val atMs: Long, val status: PlayerStatus)

    @Volatile
    private var told: Told? = null

    fun addStateListener(listener: PlayerStateListener) {
        listeners.add(listener)
    }

    fun removeStateListener(listener: PlayerStateListener) {
        listeners.remove(listener)
    }

    fun addFailureListener(listener: PlaybackFailureListener) {
        failureListeners.add(listener)
    }

    fun removeFailureListener(listener: PlaybackFailureListener) {
        failureListeners.remove(listener)
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
     * Empties the speaker's current track. [reset] calls it unconditionally (also when the player is
     * already STOPPED or IDLE, where [stop] never reaches doStop) when asked to clear: this is the
     * hook for "disconnect clears the speaker". renderer.js has no equivalent. The default does nothing.
     */
    open suspend fun doClear() {}

    /**
     * Reports LOADING, calls doPlay, then reports PLAYING (or STOPPED when doPlay fails, so the
     * sender does not keep a loading spinner). A doPlay that throws counts as a failure.
     */
    suspend fun play(video: Video, position: Double = 0.0, aid: Int? = null): Boolean {
        if (status == PlayerStatus.PLAYING) stop()
        log("play", "video" to video.id, "position" to position)
        val hop = queue.setAsCurrent(video)
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
        if (result && hop) {
            // Playback already runs; look up the videos around the autoplay video so previous is no longer
            // stale and the next autoplay video exists, then tell the listeners.
            try {
                queue.refreshAfterHop()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("hopRefreshFailed", "error" to (e.message ?: e::class.simpleName))
            }
            setStatusAndEmit(null, aid)
        }
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
     * yt-cast-receiver 2.1.0, which resumes; the sender presses Play when it wants sound.
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
        val before = getState()
        val result = applyVolume(volume)
        if (result) emitState(PlayerStateEvent(aid, getState(), before, fromVolumeChange = true))
        return result
    }

    /**
     * Applies a volume without reading unrelated playback state or emitting a state event. The caller must report
     * the speaker's actual volume, including after a failure.
     */
    suspend fun applyVolume(volume: Volume): Boolean {
        val v = if (zeroVolumeLevelOnMute && volume.muted && volume.level > 0) volume.copy(level = 0) else volume
        log("setVolume", "level" to v.level, "muted" to v.muted)
        val result = doSetVolume(v)
        if (!result) log("setVolumeFailed", "level" to v.level, "muted" to v.muted)
        return result
    }

    /**
     * Back to IDLE with an empty queue. With [clear] (the default) the speaker is also emptied through
     * [doClear] after the normal stop; a failing doClear never prevents the reset. Pass clear = false when
     * a new session starts playing at once (the YouTube / YouTube Music switch).
     */
    suspend fun reset(aid: Int? = null, clear: Boolean = true) {
        log("reset", "clear" to clear)
        queue.reset()
        stop(aid)
        if (clear) {
            try {
                doClear()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("clearFailed", "error" to (e.message ?: e::class.simpleName))
            }
        }
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

    /**
     * What the sender's buttons can do. Deliberate deviation from yt-cast-receiver, which reports
     * queue.hasNext only: [NavInfo.hasNext] is also true while autoplay is ENABLED and an autoplay
     * video exists, so the phone enables next whenever the queue or autoplay has one.
     */
    fun getNavInfo(): NavInfo = NavInfo(
        hasPrevious = queue.hasPrevious,
        hasNext = queue.hasNext || (autoplayMode == AutoplayMode.ENABLED && queue.autoplay != null),
        autoplayMode = autoplayMode,
    )

    suspend fun getState(): PlayerState {
        val current = status
        return PlayerState(
            status = current,
            queue = queue.getState(),
            position = getPosition(),
            duration = getDuration(),
            volume = getVolume(),
            cpn = cpn,
            buffering = current == PlayerStatus.PLAYING && seekHoldActive(),
        )
    }

    /**
     * Signals a change the player did not cause (the speaker stopped, the track ended, a position
     * moved): updates the status when [newStatus] is not null and tells the senders.
     */
    suspend fun notifyExternalStateChange(newStatus: PlayerStatus?) {
        setStatusAndEmit(newStatus)
    }

    /**
     * For the case where the player was told to play and the speaker cannot: logs it, tells the senders STOPPED (state 4)
     * and then calls the failure listeners in registration order, so the receiver can end the cast visibly. Not for a Stop
     * the sender asked for and not for a track end ([notifyExternalStateChange] covers those).
     *
     * [reason] is a fixed phrase chosen by the caller, never exception text, because exception text can carry a signed URL.
     */
    suspend fun reportPlaybackFailure(reason: String) {
        log("playbackFailed", "reason" to reason)
        setStatusAndEmit(PlayerStatus.STOPPED)
        for (listener in failureListeners) {
            try {
                listener.onPlaybackFailure(reason)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("listenerError", "error" to (e.message ?: e::class.simpleName))
            }
        }
    }

    /**
     * Tells the senders the position again, even when it equals the last one sent. A sender counts on from the last
     * state it got, so the same number can still be wrong (a seek the speaker took seconds to finish): the event
     * carries a previous state whose position is -1, which never equals a real position, so the session sends the
     * state. renderer.js has no counterpart.
     */
    suspend fun notifyPositionCorrection() {
        val previous = previousState?.copy(position = -1.0)
        val current = getState()
        previousState = current
        emitState(PlayerStateEvent(null, current, previous))
    }

    private suspend fun setStatusAndEmit(newStatus: PlayerStatus?, aid: Int? = null) {
        val previous = previousState
        if (newStatus != null) statusState.value = newStatus
        val current = getState()
        previousState = current
        emitState(PlayerStateEvent(aid, current, previous))
    }

    private suspend fun emitState(event: PlayerStateEvent) {
        // A bar that is buffering does not count on: it stays where it was told until the next state.
        told = Told(event.current.position, clockMs(), if (event.current.senderCode == PlayerStatus.LOADING.code) PlayerStatus.LOADING else event.current.status)
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
