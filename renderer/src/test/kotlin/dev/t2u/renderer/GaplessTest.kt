package dev.t2u.renderer

// Port of the gapless/prefetch tests of reference/tube2upnp/test/renderer.test.js (lines 208-344), GPL-3.0-only.
// Wave 0 of Phase 4 (D-16): the six Node titles exist here, under their exact Node titles, before any gapless code
// is written. Each is @Disabled and names the plan that turns it on (04-04 prefetch, 04-05 gapless hand-off); the
// enabling plan may adjust a test body when the real timing demands it, keeping the title and the assertions.
//
// Kotlin differences from the Node tests (the traps D-16 names):
//  * Node assigns `r.getUpcomingVideo = () => ({ id: B })`. Here the queue is the real lounge queue: a setPlaylist
//    message sets the current video and a PlaylistRequestHandler (NextHandler) answers what follows it, so the
//    renderer finds its upcoming video the way production does.
//  * Node assigns `r.next = ...` to stand for yt-cast-receiver's Player.next(). Here Player.next() is final; it
//    runs against the real queue, so the hand-off's next() reaches doPlay (or play -> stop -> doStop) for real.
//  * Node's setup() omits PREFETCH, so most Node tests run with it off; these tests set prefetch and gapless on
//    through cfg(), and every other renderer test helper sets prefetch = false (RESEARCH Pitfall 6).
//  * Node's regex /stream/0{31}2\.m4a$ becomes 0{31}2\.aac$ because cfg() pins outputFormat to adts.
//
// Every test is a block-bodied function: JUnit silently skips a test whose expression body returns a value.

import dev.t2u.lounge.Client
import dev.t2u.lounge.Playlist
import dev.t2u.lounge.PlaylistRequestHandler
import dev.t2u.lounge.PreviousNextVideos
import dev.t2u.lounge.Video
import dev.t2u.lounge.VideoContext
import dev.t2u.upnp.FakeRenderer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val A = "AAAAAAAAAAA"
private const val B = "BBBBBBBBBBB"
private const val C = "CCCCCCCCCCC"

internal class GaplessTest {
    private fun <T> withFake(fake: FakeRenderer = FakeRenderer(), block: suspend (FakeRenderer) -> T): T {
        fake.start()
        try {
            return runBlocking { block(fake) }
        } finally {
            fake.close()
        }
    }

    /** adts is pinned so registered paths end `.aac` (Node's `.m4a` regexes become `.aac`). */
    private fun cfg(prefetch: Boolean = true, gapless: Boolean = true): (File) -> Config = {
        Config(
            dataDir = it, outputFormat = "adts", pollIntervalMs = 50, startTimeoutMs = 5000, nameSuffix = "",
            queueSettleTimeoutMs = 5000, prefetch = prefetch, gapless = gapless,
        )
    }

    /**
     * Answers what follows a video. [nextOf] gives the video after the id asked about (null: nothing follows);
     * [refreshDelayMs] makes every lookup slow, so a playlist refresh stays in flight.
     */
    private class NextHandler : PlaylistRequestHandler {
        @Volatile var nextOf: (String) -> Video? = { id -> if (id == A) video(B, 1) else null }

        @Volatile var refreshDelayMs = 0L

        override suspend fun getPreviousNextVideos(target: Video, playlist: Playlist): PreviousNextVideos? {
            if (refreshDelayMs > 0) delay(refreshDelayMs)
            return PreviousNextVideos(null, nextOf(target.id))
        }
    }

    private fun setPayload(vararg ids: String): JsonObject = buildJsonObject {
        put("listId", "PL")
        put("videoIds", ids.joinToString(","))
        put("videoId", ids.first())
        put("currentIndex", "0")
        put("currentTime", "0")
    }

    private fun updatePayload(vararg ids: String): JsonObject = buildJsonObject {
        put("listId", "PL")
        put("videoIds", ids.joinToString(","))
    }

    private fun FakeRenderer.count(action: String): Int = calls.count { it.action == action }

    private fun harness(fake: FakeRenderer, handler: NextHandler, prefetch: Boolean = true, gapless: Boolean = true) =
        RendererHarness(fake, config = cfg(prefetch, gapless), playlistRequestHandlerFactory = { handler })

    /** A renderer whose queue holds [ids] with the first one current, not yet playing. */
    private suspend fun queued(h: RendererHarness, vararg ids: String): Renderer {
        val r = h.renderer
        r.init()
        r.queue.updateByMessage("setPlaylist", setPayload(*ids), Client.YT)
        assertNotNull(r.queue.current, "the queue has a current video")
        return r
    }

    @Test
    @Disabled("PLAY-08, PLAY-09: enabled by plan 04-05")
    @DisplayName("gapless: next track is prepared, queued with SetNextAVTransportURI, and adopted without reloading")
    fun nextTrackIsPreparedQueuedAndAdoptedWithoutReloading() {
        withFake { fake ->
            val handler = NextHandler()
            harness(fake, handler).use { h ->
                val r = queued(h, A, B)
                // doPlay directly: the player status stays IDLE, so the later next() plays B without a stop,
                // like Node's `r.next = doPlay(B)`.
                assertTrue(r.doPlay(Video(A), 0.0))
                waitUntil { fake.state.nextUri.isNotEmpty() }

                assertTrue(fake.count("SetNextAVTransportURI") > 0, "next track handed to renderer")
                assertEquals(B, h.registered[1].videoId)
                assertTrue(Regex("/stream/0{31}2\\.aac$").containsMatchIn(fake.state.nextUri), fake.state.nextUri)

                // The renderer finishes A and plays on into B by itself. The queue moves on, as the poll's hand-off does.
                handler.nextOf = { null }
                val loadsBefore = fake.count("SetAVTransportURI")
                fake.advance()
                waitUntil { r.nowPlaying?.videoId == B }
                assertEquals(B, r.nowPlaying?.videoId)
                assertEquals(loadsBefore, fake.count("SetAVTransportURI"), "B was not reloaded")
                assertEquals("PLAYING", fake.state.transport)
            }
        }
    }

    @Test
    @Disabled("PLAY-08, PLAY-09: enabled by plan 04-05")
    @DisplayName("gapless: yt-cast-receiver stopping the player before play() does not cut off or reload the track")
    fun stoppingThePlayerBeforePlayDoesNotCutOffOrReload() {
        withFake { fake ->
            val handler = NextHandler()
            harness(fake, handler).use { h ->
                val r = queued(h, A, B)
                // Through Player.play() like a real cast, so the player is PLAYING when the next track comes and the
                // hand-off's next() goes play() -> stop() -> doStop().
                assertTrue(r.play(r.queue.current!!, 0.0))
                waitUntil { fake.state.nextUri.isNotEmpty() }

                handler.nextOf = { null }
                val loadsBefore = fake.count("SetAVTransportURI")
                val stopsBefore = fake.count("Stop")
                fake.advance()
                waitUntil { r.nowPlaying?.videoId == B }
                assertEquals(B, r.nowPlaying?.videoId)
                assertEquals(loadsBefore, fake.count("SetAVTransportURI"), "B was not reloaded")
                assertEquals(stopsBefore, fake.count("Stop"), "renderer was not stopped")
                assertEquals("PLAYING", fake.state.transport)
            }
        }
    }

    @Test
    @Disabled("PLAY-08, PLAY-09: enabled by plan 04-05")
    @DisplayName("gapless: if the queue turns out to be over, the track the renderer played on into is stopped")
    fun ifTheQueueTurnsOutToBeOverThePlayedOnTrackIsStopped() {
        withFake { fake ->
            val handler = NextHandler()
            harness(fake, handler).use { h ->
                val r = queued(h, A, B)
                assertTrue(r.play(r.queue.current!!, 0.0))
                waitUntil { fake.state.nextUri.isNotEmpty() }

                // Nothing is left after A: a refresh that lands now finds the queue over. The refresh is held in flight, and
                // during it the queue's next is empty (plan 04-04 pauses prefetch while a refresh runs so the queued
                // track stays on the speaker).
                handler.nextOf = { null }
                handler.refreshDelayMs = 300
                h.scope.launch { r.queue.updateByMessage("updatePlaylist", updatePayload(A, B), Client.YT) }
                waitUntil { r.queue.isUpdating }
                assertTrue(r.queue.isUpdating, "the refresh is in flight")

                fake.advance()
                waitUntil { fake.state.transport == "STOPPED" }
                assertEquals("STOPPED", fake.state.transport)
            }
        }
    }

    @Test
    @DisplayName("no gapless support: falls back cleanly and uses the prepared track at end of song")
    fun noGaplessSupportFallsBackAndUsesThePreparedTrack() {
        withFake(FakeRenderer(noGapless = true)) { fake ->
            val handler = NextHandler()
            harness(fake, handler).use { h ->
                val r = queued(h, A, B)
                assertTrue(r.doPlay(Video(A), 0.0))
                waitUntil { h.resolves.size >= 2 }
                assertEquals(listOf(A, B), h.resolves.toList(), "B resolved ahead of time")
                // The resolve is recorded first; the item counts as prepared once it is registered and queued.
                waitUntil { h.notes("Prepared next track").isNotEmpty() }

                // The song ends, the speaker stops, YouTube asks for B.
                assertTrue(r.doPlay(Video(B), 0.0))
                assertEquals(listOf(A, B), h.resolves.toList(), "B not resolved again - prepared item reused")
                assertTrue(Regex("0{31}2\\.aac$").containsMatchIn(fake.state.currentUri), fake.state.currentUri)
            }
        }
    }

    @Test
    @Disabled("PLAY-08, PLAY-09: enabled by plan 04-05")
    @DisplayName("queue change replaces the prepared next track")
    fun queueChangeReplacesThePreparedNextTrack() {
        withFake { fake ->
            val handler = NextHandler()
            harness(fake, handler).use { h ->
                val r = queued(h, A, B)
                assertTrue(r.doPlay(Video(A), 0.0))
                waitUntil { fake.count("SetNextAVTransportURI") >= 1 }
                assertEquals(1, fake.count("SetNextAVTransportURI"))

                handler.nextOf = { id -> if (id == A) video(C, 1) else null }
                r.queue.updateByMessage("updatePlaylist", updatePayload(A, C), Client.YT)
                waitUntil { fake.count("SetNextAVTransportURI") >= 2 }
                delay(200) // four polls
                assertEquals(2, fake.count("SetNextAVTransportURI"))

                // End of queue, autoplay off: the speaker's next slot is cleared.
                handler.nextOf = { null }
                r.queue.updateByMessage("updatePlaylist", updatePayload(A), Client.YT)
                waitUntil { fake.state.nextUri == "" }
                assertEquals("", fake.state.nextUri)
            }
        }
    }

    @Test
    @Disabled("PLAY-08: enabled by plan 04-04 task 2")
    @DisplayName("a next track that cannot be prepared is not retried on every poll")
    fun aNextTrackThatCannotBePreparedIsNotRetriedOnEveryPoll() {
        withFake { fake ->
            val handler = NextHandler()
            harness(fake, handler).use { h ->
                h.resolveFailure = { id -> if (id == B) IllegalStateException("ERROR: [youtube] $B: Video unavailable") else null }
                val r = queued(h, A, B)
                assertTrue(r.doPlay(Video(A), 0.0))
                delay(500) // about ten polls
                assertEquals(listOf(A, B), h.resolves.toList(), "one attempt, then back off")
                assertEquals(0, fake.count("SetNextAVTransportURI"))

                // When the song ends YouTube asks for it anyway: a normal load, not blocked by the back-off. It fails here
                // because B cannot be resolved, but it is tried again.
                r.doPlay(Video(B), 0.0)
                assertEquals(listOf(A, B, B), h.resolves.toList())
            }
        }
    }

    // ---- Kotlin-only prefetch tests (plan 04-04) ----

    /** The path the harness registers for its [n]th registered stream (adts config). */
    private fun streamPath(n: Int) = "/stream/" + n.toString().padStart(32, '0') + ".aac"

    private fun FakeRenderer.nearTheEnd() {
        state.relTime = "0:03:28"
    }

    @Test
    @DisplayName("the track-end load plays the prepared next track without a second lookup")
    fun theTrackEndLoadPlaysThePreparedNextTrackWithoutASecondLookup() {
        withFake { fake ->
            val handler = NextHandler()
            harness(fake, handler).use { h ->
                val r = queued(h, A, B)
                assertTrue(r.play(r.queue.current!!, 0.0))
                waitUntil { h.notes("Prepared next track").isNotEmpty() }
                assertEquals(2, h.registered.size, "A and B registered")
                assertEquals(B, h.registered[1].videoId)
                val note = h.notes("Prepared next track").single()
                assertEquals(B, note["videoId"])
                assertEquals("adts", note["format"])

                // A is playing (seen by a poll) and then the speaker reports A near its end and STOPPED.
                delay(200)
                fake.nearTheEnd()
                delay(200)
                fake.state.transport = "STOPPED"
                waitUntil { fake.count("SetAVTransportURI") == 2 }
                assertEquals(2, fake.count("SetAVTransportURI"))
                val second = fake.calls.filter { it.action == "SetAVTransportURI" }[1]
                assertTrue(second.body.contains(streamPath(2)), "the second URI is B's registered path")
                assertEquals(listOf(A, B), h.resolves.toList(), "B was resolved once")
                assertEquals(1, h.prepared.count { it.videoId == B }, "B was prepared once")
                assertTrue(h.notes("Using prepared track").isNotEmpty(), "the load says it used the prepared track")
            }
        }
    }

    @Test
    @DisplayName("prefetch off prepares nothing ahead")
    fun prefetchOffPreparesNothingAhead() {
        withFake { fake ->
            val handler = NextHandler()
            harness(fake, handler, prefetch = false).use { h ->
                val r = queued(h, A, B)
                assertTrue(r.play(r.queue.current!!, 0.0))
                delay(500) // ten polls
                assertEquals(listOf(A), h.resolves.toList())
                assertEquals(1, h.registered.size)
                assertTrue(h.notes("Prepared next track").isEmpty())
            }
        }
    }

    private companion object {
        fun video(id: String, index: Int) = Video(id, Client.YT, VideoContext(playlistId = "PL", index = index))
    }
}
