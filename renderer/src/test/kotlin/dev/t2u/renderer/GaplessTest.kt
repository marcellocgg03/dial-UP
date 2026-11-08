package dev.t2u.renderer

// Port of the gapless/prefetch tests of reference/tube2upnp/test/renderer.test.js (lines 208-344), GPL-3.0-only.
// Wave 0 of Phase 4 (D-16): the six Node titles exist here, under their exact Node titles, before any gapless code
// is written. Each started disabled and was turned on by the plan that implements it (04-04 prefetch, 04-05 gapless
// hand-off; all six are on now); the enabling plan may adjust a test body when the real timing demands it, keeping the
// title and the assertions.
//
// Kotlin differences from the Node tests (the traps D-16 names):
//  * Node assigns `r.getUpcomingVideo = () => ({ id: B })`. Here the queue is the real lounge queue: a setPlaylist
//    message sets the current video and a PlaylistRequestHandler (NextHandler) answers what follows it, so the
//    renderer finds its upcoming video the way production does.
//  * Node assigns `r.next = ...` to stand for yt-cast-receiver's Player.next(). Here Player.next() is final; it
//    runs against the real queue, so the hand-off's next() reaches doPlay (or play -> stop -> doStop) for real.
//  * Node's setup() omits PREFETCH, so most Node tests run with it off; these tests set prefetch and gapless on
//    through cfg(), and every other renderer test helper sets prefetch = false.
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
import dev.t2u.upnp.UpnpError
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
    private fun cfg(prefetch: Boolean = true, gapless: Boolean = true, startTimeoutMs: Long = 5000): (File) -> Config = {
        Config(
            dataDir = it, outputFormat = "adts", pollIntervalMs = 50, startTimeoutMs = startTimeoutMs, nameSuffix = "",
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

    private fun harness(
        fake: FakeRenderer,
        handler: NextHandler,
        prefetch: Boolean = true,
        gapless: Boolean = true,
        startTimeoutMs: Long = 5000,
    ) = RendererHarness(fake, config = cfg(prefetch, gapless, startTimeoutMs), playlistRequestHandlerFactory = { handler })

    /** A renderer whose queue holds [ids] with the first one current, not yet playing. */
    private suspend fun queued(h: RendererHarness, vararg ids: String): Renderer {
        val r = h.renderer
        r.init()
        r.queue.updateByMessage("setPlaylist", setPayload(*ids), Client.YT)
        assertNotNull(r.queue.current, "the queue has a current video")
        return r
    }

    @Test
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
    fun offsetFallbackReturnsToDirectOnGaplessNextTrack() {
        withFake { fake ->
            val handler = NextHandler()
            RendererHarness(
                fake,
                config = { cfg()(it).copy(outputFormat = "auto") },
                playlistRequestHandlerFactory = { handler },
            ).use { h ->
                val r = queued(h, A, B)
                assertTrue(r.play(r.queue.current!!, 60.0))
                waitUntil { fake.state.nextUri.isNotEmpty() }
                assertEquals("mp3" to 60, h.registered.first().let { it.format to it.startAt })
                assertEquals("direct" to 0, h.registered[1].let { it.format to it.startAt })
                assertTrue(fake.state.nextUri.endsWith(".m4a"), fake.state.nextUri)

                handler.nextOf = { null }
                val loadsBefore = fake.count("SetAVTransportURI")
                fake.advance()
                waitUntil { r.nowPlaying?.videoId == B }
                assertEquals("direct", r.nowPlaying?.format)
                assertEquals(loadsBefore, fake.count("SetAVTransportURI"))
            }
        }
    }

    @Test
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

    @Test
    @DisplayName("a failed prepare is backed off per video: 30 s, doubling")
    fun aFailedPrepareIsBackedOffPerVideo() {
        val now = AtomicLong(1_000_000L)
        withFake { fake ->
            val handler = NextHandler()
            RendererHarness(
                fake, config = cfg(), playlistRequestHandlerFactory = { handler }, clock = { now.get() },
            ).use { h ->
                h.resolveFailure = { id -> if (id == B) IllegalStateException("ERROR: [youtube] $B: Video unavailable") else null }
                val r = queued(h, A, B)
                assertTrue(r.doPlay(Video(A), 0.0))
                fun attempts() = h.resolves.count { it == B }
                waitUntil { attempts() == 1 }
                delay(200)
                assertEquals(1, attempts())

                now.addAndGet(29_000)
                delay(300)
                assertEquals(1, attempts(), "29 s after the failure: no new attempt")
                now.addAndGet(2_000) // 31 s
                waitUntil { attempts() == 2 }
                assertEquals(2, attempts(), "31 s after the failure: a second attempt")

                now.addAndGet(59_000)
                delay(300)
                assertEquals(2, attempts(), "59 s after the second failure: none")
                now.addAndGet(2_000) // 61 s
                waitUntil { attempts() == 3 }
                assertEquals(3, attempts(), "61 s after the second failure: a third attempt")

                val message = h.notes("Preparing next track").first()["message"] as String
                assertTrue(message.startsWith("Preparing next track $B failed: "), message)
                assertTrue(message.endsWith(" - next try in 30 s"), message)
                assertTrue(!message.contains("http"), "the note carries no URL")
            }
        }
    }

    @Test
    @DisplayName("the prefetch back-off doubles to a ten minute cap, forgets a success and keeps 50 ids")
    fun prefetchBackoffArithmetic() {
        val b = PrefetchBackoff(PREFETCH_RETRY_MS, PREFETCH_RETRY_MAX_MS, PREFETCH_FAILURES_KEPT)
        var now = 0L
        val delays = (1..9).map { b.failed(B, now) }
        assertEquals(listOf(30_000L, 60_000L, 120_000L, 240_000L, 480_000L, 600_000L, 600_000L, 600_000L, 600_000L), delays)
        assertTrue(b.blockedUntil(B) > now)
        now += 600_001
        assertTrue(b.blockedUntil(B) <= now, "10 min and 1 ms after the last failure one more attempt is allowed")
        b.succeeded(B)
        assertEquals(0L, b.blockedUntil(B))
        assertEquals(30_000L, b.failed(B, now), "a success cleared the count")

        val many = PrefetchBackoff(PREFETCH_RETRY_MS, PREFETCH_RETRY_MAX_MS, PREFETCH_FAILURES_KEPT)
        for (i in 0 until 60) many.failed("id$i", 1)
        assertEquals(50, many.size)
        assertEquals(0L, many.blockedUntil("id0"), "the oldest ids were dropped")
        assertTrue(many.blockedUntil("id59") > 0)
        many.failed("id10", 1) // updated: moves to the newest end
        for (i in 60 until 70) many.failed("id$i", 1)
        assertTrue(many.blockedUntil("id10") > 0, "an updated id survives later drops")
        assertEquals(50, many.size)
    }

    @Test
    @DisplayName("a slow prepare does not hold up the poll: the track end is acted on at once")
    fun aSlowPrepareDoesNotHoldUpThePoll() {
        withFake { fake ->
            val handler = NextHandler()
            harness(fake, handler).use { h ->
                h.prepareDelayFor = { e -> if (e.videoId == B) 3000L else 0L }
                val r = queued(h, A, B)
                assertTrue(r.play(r.queue.current!!, 0.0))
                waitUntil { h.prepared.any { it.videoId == B } }
                assertTrue(h.prepared.any { it.videoId == B }, "the prepare of B has started")
                delay(200) // the poll has seen A playing
                fake.nearTheEnd()
                delay(200)
                val at = System.currentTimeMillis()
                fake.state.transport = "STOPPED"
                waitUntil { r.queue.current?.id == B }
                val took = System.currentTimeMillis() - at
                assertEquals(B, r.queue.current?.id)
                assertTrue(took < 1000, "the track end was acted on within 1 s of the STOPPED (took $took ms)")
            }
        }
    }

    @Test
    @DisplayName("a playlist refresh in flight keeps the prepared next track")
    fun aPlaylistRefreshInFlightKeepsThePreparedTrack() {
        withFake { fake ->
            val handler = NextHandler()
            harness(fake, handler).use { h ->
                val r = queued(h, A, B)
                assertTrue(r.doPlay(Video(A), 0.0))
                waitUntil { h.notes("Prepared next track").isNotEmpty() }
                assertEquals(1, h.prepared.count { it.videoId == B })

                handler.refreshDelayMs = 400
                h.scope.launch { r.queue.updateByMessage("updatePlaylist", updatePayload(A, B), Client.YT) }
                waitUntil { r.queue.isUpdating }
                assertTrue(r.queue.isUpdating, "the refresh is in flight")
                delay(300) // polls during the refresh
                assertEquals(1, h.prepared.count { it.videoId == B }, "no new prepare of B")
                assertTrue(h.released.none { it == streamPath(2) }, "B was not released")

                waitUntil { !r.queue.isUpdating }
                delay(200)
                assertEquals(1, h.prepared.count { it.videoId == B })
                assertTrue(r.doPlay(Video(B), 0.0))
                assertEquals(listOf(A, B), h.resolves.toList(), "B is still the prepared one")
                assertTrue(h.notes("Using prepared track").isNotEmpty())
            }
        }
    }

    @Test
    @DisplayName("a replaced or unused prepared track is released")
    fun replacedAndUnusedPreparedTracksAreReleased() {
        withFake { fake ->
            val handler = NextHandler()
            harness(fake, handler).use { h ->
                val r = queued(h, A, B)
                assertTrue(r.doPlay(Video(A), 0.0))
                waitUntil { h.notes("Prepared next track").size == 1 }

                // The upcoming video becomes C: C is prepared and B's stream is released.
                handler.nextOf = { id -> if (id == A) video(C, 1) else null }
                r.queue.updateByMessage("updatePlaylist", updatePayload(A, C), Client.YT)
                waitUntil { h.notes("Prepared next track").size == 2 }
                assertEquals(C, h.registered[2].videoId)
                assertTrue(h.released.contains(streamPath(2)), "B's stream was released: ${h.released}")

                // A load of another video does not adopt the prepared C: C is released.
                assertTrue(r.doPlay(Video(B), 0.0))
                assertTrue(h.released.contains(streamPath(3)), "the unused C was released: ${h.released}")
            }
        }
    }

    @Test
    @DisplayName("destroy releases the prepared next track")
    fun destroyReleasesThePreparedTrack() {
        withFake { fake ->
            val handler = NextHandler()
            harness(fake, handler).use { h ->
                val r = queued(h, A, B)
                assertTrue(r.doPlay(Video(A), 0.0))
                waitUntil { h.notes("Prepared next track").isNotEmpty() }
                r.destroy()
                assertTrue(h.released.contains(streamPath(2)), "B's stream was released: ${h.released}")
            }
        }
    }

    // ---- Kotlin-only hand-off tests (plan 04-05) ----

    /** Text of every renderer event, to look for a stream path or a URL where none may be. */
    private fun RendererHarness.allText(): String = events.joinToString("\n") { it.second.toString() }

    @Test
    @DisplayName("the switch note names the field that showed it and never the stream URI")
    fun theSwitchNoteNamesTheFieldAndNeverTheUri() {
        withFake { fake ->
            val handler = NextHandler()
            harness(fake, handler).use { h ->
                val r = queued(h, A, B)
                assertTrue(r.doPlay(Video(A), 0.0))
                // The fake sets nextUri before it answers; the note follows the answer.
                waitUntil { fake.state.nextUri.isNotEmpty() && h.notes("Queued the next track on the speaker").isNotEmpty() }
                val queuedNote =h.notes("Queued the next track on the speaker").single()
                assertEquals(B, queuedNote["videoId"])

                handler.nextOf = { null }
                fake.advance()
                waitUntil { h.notes("Now playing").any { (it["message"] as String).contains("continued gaplessly") } }

                val switched = h.notes("Renderer continued into the next track").single()
                assertEquals(B, switched["videoId"])
                assertEquals("TrackURI", switched["via"])
                assertTrue(h.notes("Renderer supports gapless playback").isNotEmpty())
                val text = h.allText()
                assertTrue(!text.contains("http://"), "no URL in any note")
                assertTrue(!text.contains(streamPath(2)), "no stream path in any note")
            }
        }
    }

    @Test
    @DisplayName("a speaker that reports no TrackURI is read through GetMediaInfo to see the switch")
    fun aSpeakerWithoutTrackUriIsReadThroughGetMediaInfo() {
        withFake { fake ->
            val handler = NextHandler()
            harness(fake, handler).use { h ->
                val r = queued(h, A, B)
                assertTrue(r.doPlay(Video(A), 0.0))
                waitUntil { fake.state.nextUri.isNotEmpty() }

                handler.nextOf = { null }
                fake.state.reportedUri = ""
                fake.advance()
                waitUntil { r.nowPlaying?.videoId == B }
                assertEquals(B, r.nowPlaying?.videoId)
                assertEquals("GetMediaInfo.CurrentURI", h.notes("Renderer continued into the next track").single()["via"])
            }
        }
    }

    @Test
    @DisplayName("an adopted track counts as already playing: its position is the speaker's and the start watchdog is off")
    fun anAdoptedTrackCountsAsAlreadyPlaying() {
        withFake { fake ->
            val handler = NextHandler()
            harness(fake, handler, startTimeoutMs = 300).use { h ->
                val r = queued(h, A, B)
                assertTrue(r.play(r.queue.current!!, 0.0))
                waitUntil { fake.state.nextUri.isNotEmpty() }

                handler.nextOf = { null }
                fake.advance()
                waitUntil { r.nowPlaying?.videoId == B }
                fake.state.relTime = "0:00:07"
                assertTrue(kotlin.math.abs(r.doGetPosition() - 7.0) < 0.5, "the position is the speaker's RelTime, not 0")

                // The speaker stops reporting PLAYING for longer than the start timeout: a track that was never seen
                // playing would be given up on; the adopted one was playing already.
                fake.state.transport = "TRANSITIONING"
                delay(900)
                assertTrue(h.notes("Renderer ").none { it["level"] == "error" }, "the start watchdog did not fire")
                assertEquals(dev.t2u.lounge.PlayerStatus.PLAYING, r.status)
            }
        }
    }

    @Test
    @DisplayName("a play after the speaker moved on is adopted without a Stop and says so")
    fun aPlayAfterTheSpeakerMovedOnSendsNoStop() {
        withFake { fake ->
            val handler = NextHandler()
            harness(fake, handler).use { h ->
                val r = queued(h, A, B)
                assertTrue(r.play(r.queue.current!!, 0.0))
                waitUntil { fake.state.nextUri.isNotEmpty() }

                handler.nextOf = { null }
                val stopsBefore = fake.count("Stop")
                fake.advance()
                waitUntil { r.nowPlaying?.videoId == B }
                assertEquals(1, h.notes("stop not sent: the speaker already moved on to the queued track").size)
                assertEquals(stopsBefore, fake.count("Stop"))
            }
        }
    }

    @Test
    @DisplayName("a speaker that refuses SetNextAVTransportURI is asked once and its next track still starts after the current one")
    fun aRefusingSpeakerIsAskedOnceAndStillAdvances() {
        withFake(FakeRenderer(noGapless = true)) { fake ->
            val handler = NextHandler()
            handler.nextOf = { id -> if (id == A) video(B, 1) else if (id == B) video(C, 2) else null }
            harness(fake, handler).use { h ->
                val r = queued(h, A, B, C)
                assertTrue(r.doPlay(Video(A), 0.0))
                waitUntil { h.notes("Prepared next track").isNotEmpty() }
                waitUntil { fake.count("SetNextAVTransportURI") >= 1 }
                delay(500) // about ten polls
                assertEquals(1, fake.count("SetNextAVTransportURI"), "refused once, not asked again")
                assertEquals(1, h.notes("Renderer has no SetNextAVTransportURI").size)
                assertTrue(h.notes("Renderer supports gapless").isEmpty())
                assertTrue(h.notes("Queued the next track").isEmpty())
                assertEquals(listOf(A, B), h.resolves.toList())

                // The end of A: B is loaded from the prepared item; C is then prepared but never offered to the speaker.
                assertTrue(r.doPlay(Video(B), 0.0))
                assertEquals(listOf(A, B), h.resolves.toList(), "B not resolved again")
                waitUntil { h.notes("Prepared next track").size == 2 }
                delay(300)
                assertEquals(1, fake.count("SetNextAVTransportURI"), "no second SetNextAVTransportURI for the following track")
                assertEquals(1, h.notes("Renderer has no SetNextAVTransportURI").size)
            }
        }
    }

    @Test
    @DisplayName("gapless off: the next track is prepared but never handed to the speaker, and starts after the current one ends")
    fun gaplessOffPreparesButNeverSendsNext() {
        withFake { fake ->
            val handler = NextHandler()
            harness(fake, handler, gapless = false).use { h ->
                val r = queued(h, A, B)
                assertTrue(r.play(r.queue.current!!, 0.0))
                waitUntil { h.notes("Prepared next track").isNotEmpty() }
                assertEquals(listOf(A, B), h.resolves.toList())
                delay(300)
                assertEquals(0, fake.count("SetNextAVTransportURI"))
                assertEquals("", fake.state.nextUri)

                fake.nearTheEnd()
                delay(200)
                fake.state.transport = "STOPPED"
                waitUntil { fake.count("SetAVTransportURI") == 2 }
                assertEquals(2, fake.count("SetAVTransportURI"))
                assertEquals(listOf(A, B), h.resolves.toList(), "B loaded from the prepared item")
                assertEquals(0, fake.count("SetNextAVTransportURI"))
            }
        }
    }

    @Test
    @DisplayName("gapless without prefetch is ignored: nothing is prepared and nothing is sent")
    fun gaplessWithoutPrefetchIsIgnored() {
        withFake { fake ->
            val handler = NextHandler()
            harness(fake, handler, prefetch = false, gapless = true).use { h ->
                val r = queued(h, A, B)
                assertTrue(r.play(r.queue.current!!, 0.0))
                delay(500) // ten polls
                assertEquals(listOf(A), h.resolves.toList())
                assertEquals(0, fake.count("SetNextAVTransportURI"))
            }
        }
    }

    @Test
    @DisplayName("a Stop from the phone while the hand-off waits for a refresh still stops the speaker and the track is not adopted")
    fun aStopWhileTheHandOffWaitsStillStopsTheSpeaker() {
        withFake { fake ->
            val handler = NextHandler()
            harness(fake, handler).use { h ->
                val r = queued(h, A, B)
                assertTrue(r.play(r.queue.current!!, 0.0))
                waitUntil { fake.state.nextUri.isNotEmpty() }

                handler.refreshDelayMs = 800
                h.scope.launch { r.queue.updateByMessage("updatePlaylist", updatePayload(A, B), Client.YT) }
                waitUntil { r.queue.isUpdating }
                assertTrue(r.queue.isUpdating, "the refresh is in flight")

                val stopsBefore = fake.count("Stop")
                fake.advance()
                waitUntil { h.notes("Renderer continued into the next track").isNotEmpty() }
                assertTrue(r.queue.isUpdating, "the hand-off is waiting")
                assertTrue(r.stop())
                assertTrue(fake.count("Stop") > stopsBefore, "the Stop reached the speaker")
                assertEquals("STOPPED", fake.state.transport)
                assertEquals(null, r.nowPlaying)

                waitUntil { !r.queue.isUpdating }
                delay(300)
                assertTrue(h.notes("Now playing").none { (it["message"] as String).contains("continued gaplessly") })
                assertEquals(null, r.nowPlaying)
                assertTrue(h.released.contains(streamPath(2)), "B's stream was released: ${h.released}")
            }
        }
    }

    @Test
    @DisplayName("a playlist refresh in flight at the switch is waited for, then the queued track is adopted without a reload")
    fun aRefreshInFlightAtTheSwitchIsWaitedFor() {
        withFake { fake ->
            val handler = NextHandler()
            harness(fake, handler).use { h ->
                val r = queued(h, A, B)
                assertTrue(r.play(r.queue.current!!, 0.0))
                waitUntil { fake.state.nextUri.isNotEmpty() }

                handler.refreshDelayMs = 400
                h.scope.launch { r.queue.updateByMessage("updatePlaylist", updatePayload(A, B), Client.YT) }
                waitUntil { r.queue.isUpdating }
                val loadsBefore = fake.count("SetAVTransportURI")
                val stopsBefore = fake.count("Stop")
                fake.advance()
                waitUntil { h.notes("Renderer continued into the next track").isNotEmpty() }
                assertTrue(r.nowPlaying?.videoId != B || !r.queue.isUpdating, "not adopted before the refresh ends")

                waitUntil { r.nowPlaying?.videoId == B }
                assertEquals(B, r.nowPlaying?.videoId)
                assertEquals(loadsBefore, fake.count("SetAVTransportURI"), "B was not loaded")
                assertEquals(stopsBefore, fake.count("Stop"), "no Stop")
                assertEquals("PLAYING", fake.state.transport)
            }
        }
    }

    @Test
    @DisplayName("an unchanged queue hands the next track to the speaker only once")
    fun anUnchangedQueueSendsNextOnlyOnce() {
        withFake { fake ->
            val handler = NextHandler()
            harness(fake, handler).use { h ->
                val r = queued(h, A, B)
                assertTrue(r.doPlay(Video(A), 0.0))
                waitUntil { fake.count("SetNextAVTransportURI") >= 1 }
                delay(300) // six polls
                assertEquals(1, fake.count("SetNextAVTransportURI"))
                assertEquals(1, h.notes("Queued the next track on the speaker").size)
            }
        }
    }

    @Test
    @DisplayName("only a 401, a 602 or a missing service counts as a refusal of SetNextAVTransportURI")
    fun onlyTheRefusalCodesMeanNoGaplessSupport() {
        assertTrue(isGaplessRefusal(UpnpError("x", "EUPNP", 500, "401")))
        assertTrue(isGaplessRefusal(UpnpError("x", "EUPNP", 500, "602")))
        assertTrue(isGaplessRefusal(UpnpError("x", "ENOACTION", 500, "")))
        assertTrue(isGaplessRefusal(UpnpError("x", "ENOSERVICE")))
        assertTrue(!isGaplessRefusal(UpnpError("x", "EUPNP", 500, "501")))
        assertTrue(!isGaplessRefusal(UpnpError("x", "EUPNP", 500, "")))
        assertTrue(!isGaplessRefusal(UpnpError("x", "ENETWORK")))
    }

    private companion object {
        fun video(id: String, index: Int) = Video(id, Client.YT, VideoContext(playlistId = "PL", index = index))
    }
}
