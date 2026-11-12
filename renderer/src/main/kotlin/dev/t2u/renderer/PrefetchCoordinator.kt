package dev.t2u.renderer

// The prefetch of the next track and its hand-over to the speaker, GPL-3.0-only.

import dev.t2u.lounge.Video
import dev.t2u.upnp.UpnpError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keeps the next track prepared while the current one plays and hands it to the speaker as its next one (renderer.js
 * `#maintainNext` and `#sendNextToRenderer`). It owns the prepared-next-track slot and the lock that guards it, the
 * one-at-a-time prefetch flag, the prefetch backoff and what is known about gapless support, so every access to them is
 * in this class. What it reads from the Renderer comes in as suppliers, read at every use: [config], the current load
 * sequence, the current track, [destroyed], the queue's updating flag and next video, and [store] and [model] for the gapless
 * memory. [prepareItem] and [releaseStream] are the Renderer's own; [emit] is its event helper.
 */
internal class PrefetchCoordinator(
    private val scope: CoroutineScope,
    private val configSource: () -> Config,
    private val clock: () -> Long,
    private val loadSeq: () -> Int,
    private val currentSource: () -> Current?,
    private val destroyedSource: () -> Boolean,
    private val queueUpdating: () -> Boolean,
    private val upcomingVideo: () -> Video?,
    private val prepareItem: suspend (Video, Double, String?) -> PreparedItem,
    private val releaseStream: (Current?) -> Unit,
    private val soap: SoapTransport,
    private val storeSource: () -> RendererDataStore?,
    private val modelSource: () -> String,
    private val emit: (level: String, message: String, fields: Array<out Pair<String, Any?>>) -> Unit,
) {
    private val config: Config get() = configSource()
    private val current: Current? get() = currentSource()
    private val destroyed: Boolean get() = destroyedSource()
    private val store: RendererDataStore? get() = storeSource()
    private val model: String get() = modelSource()

    private fun note(level: String, message: String, vararg fields: Pair<String, Any?>) = emit(level, message, fields)

    /**
     * The upcoming track, prepared in the background while the current one plays. Taken by a load when it is
     * the video asked for; every other fate (replaced, dropped, not adopted) releases its stream. Guarded by [queueLock].
     */
    @Volatile var queued: Queued? = null
        private set

    private val queueLock = Any()

    /**
     * Does this speaker take SetNextAVTransportURI? Null until a send has been answered; false after a refusal that
     * [isGaplessRefusal] names. Read from the speaker's store at init and written back when it changes.
     */
    @Volatile var gaplessSupported: Boolean? = null

    private val prefetchBackoff = PrefetchBackoff(PREFETCH_RETRY_MS, PREFETCH_RETRY_MAX_MS, PREFETCH_FAILURES_KEPT)

    /** True while one prefetch coroutine runs: they go one at a time, and the poll never waits for one. */
    private val prefetching = AtomicBoolean(false)

    /** Empties the slot and returns what it held, without freeing the stream. */
    fun takeQueued(): Queued? = synchronized(queueLock) { queued.also { queued = null } }

    /** Empties the slot when it still holds [candidate] and returns it; null when it was taken or replaced meanwhile. */
    fun takeIfQueued(candidate: Queued): Queued? = synchronized(queueLock) {
        if (queued !== candidate) null else queued.also { queued = null }
    }

    /** Takes the prepared next track out of the slot and frees its stream; returns it. */
    fun dropQueued(): Queued? {
        val q = synchronized(queueLock) { queued.also { queued = null } }
        releaseStream(q?.item?.current)
        return q
    }

    /**
     * Keeps the next track prepared while this one plays (renderer.js `#maintainNext`). Called from the poll, so it only
     * launches: the preparing can take many seconds (a download, an ffmpeg build) and runs in its own coroutine, one at a time,
     * so the poll keeps detecting the track end, the start failure and drift meanwhile.
     * While a playlist refresh is in flight the queue's next is empty, so nothing is done then (it would read as end of queue).
     */
    fun maintainNext() {
        if (!config.prefetch || destroyed || current == null || queueUpdating()) return
        if (!prefetching.compareAndSet(false, true)) return
        val seq = loadSeq()
        val currentId = current?.video?.id
        if (currentId == null) {
            prefetching.set(false)
            return
        }
        scope.launch {
            try {
                prefetchOnce(seq, currentId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                note("debug", "Prefetch failed: ${e.message ?: e::class.java.simpleName}")
            } finally {
                prefetching.set(false)
            }
        }
    }

    private suspend fun prefetchOnce(seq: Int, currentId: String) {
        val upcoming = upcomingVideo()
        if (queueUpdating()) return // a refresh began since the poll looked: the empty next is not the end of the queue
        if (upcoming == null || upcoming.id == currentId) {
            // Nothing upcoming any more: a track the speaker holds as its next one is taken back (renderer.js line 441).
            val dropped = dropQueued()
            if (dropped?.sentToRenderer == true) {
                soap.safeCall(AV_TRANSPORT, "SetNextAVTransportURI", "InstanceID" to 0, "NextURI" to "", "NextURIMetaData" to "")
            }
            return
        }
        val held = queued
        if (held?.video?.id == upcoming.id) {
            // An unclear SetNextAVTransportURI failure (a bare 500, a timeout) is tried again from the next polls, at most 3
            // attempts per queued track. A classified refusal made gaplessSupported false, so it is not retried.
            if (!held.sentToRenderer && held.sendAttempts < 3 && config.prefetch && config.gapless && gaplessSupported != false) {
                sendNextToRenderer(held)
            }
            return
        }
        if (prefetchBackoff.blockedUntil(upcoming.id) > clock()) return
        val t0 = clock()
        val item = try {
            prepareItem(upcoming, 0.0, null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The track-end load still tries this video with its normal handling; only the poll backs off.
            val delayMs = prefetchBackoff.failed(upcoming.id, clock())
            note(
                "warn",
                "Preparing next track ${upcoming.id} failed: ${e.message ?: e::class.java.simpleName} - next try in ${Math.round(delayMs / 1000.0)} s",
            )
            return
        }
        prefetchBackoff.succeeded(upcoming.id)
        var queuedNow: Queued? = null
        synchronized(queueLock) {
            // The world may have moved on while this prepared: a newer load, a removal, another track, another next video.
            val stillWanted = !destroyed && loadSeq() == seq && current?.video?.id == currentId &&
                upcomingVideo()?.id == upcoming.id && !queueUpdating()
            if (!stillWanted) {
                releaseStream(item.current)
                return
            }
            val old = queued
            queuedNow = Queued(upcoming, item)
            queued = queuedNow
            releaseStream(old?.item?.current)
        }
        note(
            "info",
            "Prepared next track \"${item.title}\" (${item.format})",
            "videoId" to upcoming.id,
            "format" to item.format,
            "ms" to (clock() - t0),
        )
        queuedNow?.let { sendNextToRenderer(it) }
    }

    /**
     * Hands the prepared [q] to the speaker as its next track (renderer.js `#sendNextToRenderer`): the speaker then plays on
     * into it by itself and the poll follows. Needs prefetch and gapless and a speaker not known to refuse. A refusal that
     * [isGaplessRefusal] names is remembered for this run and logged once; any other failure is only noted. NextURI and DIDL go
     * through the same SOAP escaping as SetAVTransportURI; the URL is never logged.
     */
    private suspend fun sendNextToRenderer(q: Queued) {
        if (!config.prefetch || !config.gapless || gaplessSupported == false || destroyed) return
        // Taken by a load, or replaced, while this was on its way: nothing to hand over.
        if (synchronized(queueLock) { queued } !== q) return
        q.sendAttempts++
        // What the speaker answered; the in-memory flag and the store are updated after the call, outside its try.
        var learned: Boolean? = null
        try {
            soap.call(
                AV_TRANSPORT,
                "SetNextAVTransportURI",
                "InstanceID" to 0,
                "NextURI" to q.item.url,
                "NextURIMetaData" to q.item.metadata,
            )
            q.sentToRenderer = true
            note("info", "Queued the next track on the speaker", "videoId" to q.video.id, "format" to q.item.format)
            if (gaplessSupported != true) note("info", "Renderer supports gapless playback (SetNextAVTransportURI)")
            learned = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: UpnpError) {
            if (isGaplessRefusal(e)) {
                learned = false
                // A speaker that was believed to do gapless and now refuses is a contradiction: the answer flips.
                if (gaplessSupported != false) {
                    note("info", "Renderer has no SetNextAVTransportURI - next tracks start right after the current one ends")
                }
            } else {
                note("debug", "SetNextAVTransportURI failed: ${e.message}")
            }
        } catch (e: Exception) {
            note("debug", "SetNextAVTransportURI failed: ${e.message ?: e::class.java.simpleName}")
        }
        when (learned) {
            true -> {
                val before = gaplessSupported
                gaplessSupported = true
                if (before != true) store?.set(LEARNED_GAPLESS_KEY, gaplessMemory(true, model, clock()))
            }
            false -> {
                gaplessSupported = false
                store?.set(LEARNED_GAPLESS_KEY, gaplessMemory(false, model, clock()))
            }
            null -> Unit
        }
    }
}
