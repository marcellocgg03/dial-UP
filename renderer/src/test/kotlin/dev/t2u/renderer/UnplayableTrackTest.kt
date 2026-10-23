package dev.t2u.renderer

// A queued track that cannot be played is retried or skipped, never the end of the playlist (plan 04-07, D-11),
// driven through the real queue against the upnp FakeRenderer. Kotlin original: Node stops at the failure. GPL-3.0-only.
//
// Every test is a block-bodied function: JUnit silently skips a test whose expression body returns a value.

import dev.t2u.lounge.AutoplayMode
import dev.t2u.lounge.Client
import dev.t2u.lounge.Playlist
import dev.t2u.lounge.PlaylistRequestHandler
import dev.t2u.lounge.PlayerStatus
import dev.t2u.lounge.PreviousNextVideos
import dev.t2u.lounge.Video
import dev.t2u.lounge.VideoContext
import dev.t2u.upnp.FakeRenderer
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val A = "AAAAAAAAAAA"
private const val B = "BBBBBBBBBBB"
private const val C = "CCCCCCCCCCC"

private fun unavailable(id: String) = IllegalStateException("yt-dlp failed for $id: ERROR: [youtube] $id: Video unavailable")

internal class UnplayableTrackTest {
    private fun <T> withFake(fake: FakeRenderer = FakeRenderer(), block: suspend (FakeRenderer) -> T): T {
        fake.start()
        try {
            return runBlocking { block(fake) }
        } finally {
            fake.close()
        }
    }

    private fun cfg(retryDelays: List<Long>): (File) -> Config = {
        // prefetch = false: the track-end load, not a prepared slot, is what these tests exercise.
        Config(
            dataDir = it, outputFormat = "adts", pollIntervalMs = 50, startTimeoutMs = 5000, nameSuffix = "",
            queueSettleTimeoutMs = 5000, prefetch = false, playRetryDelaysMs = retryDelays,
        )
    }

    /** Hands out the video after the id asked about in [order]; with [autoplayChain] every video is followed by a fresh one. */
    private class ChainHandler(val order: List<String> = listOf(A, B, C), val autoplayChain: Boolean = false) : PlaylistRequestHandler {
        override suspend fun getPreviousNextVideos(target: Video, playlist: Playlist): PreviousNextVideos? {
            if (autoplayChain) {
                val n = target.id.drop(1).toIntOrNull() ?: 0
                return PreviousNextVideos(null, chainVideo(n + 1))
            }
            val i = order.indexOf(target.id)
            val next = order.getOrNull(i + 1)?.let { Video(it, Client.YT, VideoContext(playlistId = "PL", index = i + 1)) }
            return PreviousNextVideos(null, next)
        }
    }

    private fun payload(vararg ids: String): JsonObject = buildJsonObject {
        put("listId", "PL")
        put("videoIds", ids.joinToString(","))
        put("videoId", ids.first())
        put("currentIndex", "0")
        put("currentTime", "0")
    }

    private fun FakeRenderer.count(action: String): Int = calls.count { it.action == action }

    private fun FakeRenderer.nearTheEnd() {
        state.relTime = "0:03:28"
    }

    private suspend fun polls() = delay(200)

    private fun harness(fake: FakeRenderer, handler: ChainHandler, retryDelays: List<Long> = listOf(50L, 50L)) =
        RendererHarness(fake, config = cfg(retryDelays), playlistRequestHandlerFactory = { handler })

    /** A renderer whose queue holds [ids] with the first one current, not yet playing. */
    private suspend fun queued(h: RendererHarness, vararg ids: String): Renderer {
        val r = h.renderer
        r.init()
        r.queue.updateByMessage("setPlaylist", payload(*ids), Client.YT)
        assertTrue(r.queue.current != null, "the queue has a current video")
        return r
    }

    /** A renderer playing the first of [ids], the speaker already seen PLAYING. */
    private suspend fun playingFirst(h: RendererHarness, vararg ids: String): Renderer {
        val r = queued(h, *ids)
        assertTrue(r.play(r.queue.current!!, 0.0))
        polls()
        return r
    }

    private fun streamPath(n: Int) = "/stream/" + n.toString().padStart(32, '0') + ".aac"

    @Test
    @DisplayName("only YouTube reasons that will not change are classified as permanent")
    fun classifier() {
        listOf(
            "ERROR: [youtube] x: Video unavailable",
            "ERROR: [youtube] x: Private video. Sign in if you've been granted access",
            "This video has been removed by the uploader",
            "This video is not available in your country",
            "ERROR: Sign in to confirm your age",
            "This video is members-only content",
            "Join this channel to get access to members-only content",
            "This live event will begin in 2 hours",
            "Premieres in 3 days",
            "VIDEO UNAVAILABLE",
        ).forEach { assertTrue(isPermanentlyUnavailable(it), it) }
        listOf(null, "", "HTTP Error 403: Forbidden", "timed out", "the download left no file", "ffmpeg mp4 build exited 1")
            .forEach { assertFalse(isPermanentlyUnavailable(it), it.toString()) }
    }

    @Test
    @DisplayName("a queued track YouTube reports as unavailable is skipped and the next one plays")
    fun unavailableTrackIsSkipped() {
        withFake { fake ->
            harness(fake, ChainHandler()).use { h ->
                h.resolveFailure = { id -> if (id == B) unavailable(B) else null }
                val r = playingFirst(h, A, B, C)
                fake.nearTheEnd()
                polls()
                fake.state.transport = "STOPPED"
                waitUntil { fake.count("SetAVTransportURI") == 2 && r.status == PlayerStatus.PLAYING }
                assertEquals(2, fake.count("SetAVTransportURI"), "A and C were sent; B never was")
                val last = fake.calls.last { it.action == "SetAVTransportURI" }
                assertTrue(last.body.contains(streamPath(2)), "the newest URI is C's path (the second registered stream)")
                assertEquals(C, h.registered.last().videoId)
                assertEquals(C, r.queue.current?.id)
                assertEquals(PlayerStatus.PLAYING, r.status)
                assertEquals(1, h.resolves.count { it == B }, "B was resolved once: a permanent reason is not retried")
                val note = h.notes("Skipping $B").single()
                assertEquals("unavailable", note["reason"])
                assertEquals(B, note["videoId"])
                assertFalse(note.toString().contains("http", ignoreCase = true), "the note carries no URL")
            }
        }
    }

    @Test
    @DisplayName("a failed direct play of a video that is not the queue's current one does not move the queue")
    fun directPlayDoesNotMoveTheQueue() {
        withFake { fake ->
            harness(fake, ChainHandler()).use { h ->
                h.resolveFailure = { id -> if (id == B) unavailable(B) else null }
                val r = queued(h, A, B, C)
                assertEquals(A, r.queue.current?.id)
                val other = Video(B, Client.YT, VideoContext(playlistId = "PL", index = 1))
                assertFalse(r.doPlay(other, 0.0))
                delay(500)
                assertEquals(0, fake.count("SetAVTransportURI"), "nothing was sent")
                assertEquals(A, r.queue.current?.id, "the queue stayed on A")
                assertTrue(h.notes("Skipping").isEmpty())
            }
        }
    }

    // ---- retries and the cap (task 2) ----

    @Test
    @DisplayName("an unclear failure is retried with a fresh lookup and the track plays when a retry works")
    fun unclearFailureIsRetriedAndPlays() {
        withFake { fake ->
            harness(fake, ChainHandler()).use { h ->
                val failures = java.util.concurrent.atomic.AtomicInteger()
                h.resolveFailure = { id -> if (id == B && failures.getAndIncrement() < 2) forbidden(B) else null }
                val r = playingFirst(h, A, B, C)
                fake.nearTheEnd()
                polls()
                fake.state.transport = "STOPPED"
                waitUntil { fake.count("SetAVTransportURI") == 2 && r.status == PlayerStatus.PLAYING }
                assertEquals(PlayerStatus.PLAYING, r.status)
                assertEquals(B, r.queue.current?.id)
                assertEquals(3, h.resolves.count { it == B }, "B was resolved three times")
                assertEquals(listOf(B, B), h.invalidated.toList(), "each retry asked for a fresh lookup")
                val notes = h.notes("Could not prepare $B")
                assertEquals(2, notes.size)
                assertTrue((notes[0]["message"] as String).endsWith("(1/2)"), notes[0].toString())
                assertTrue((notes[1]["message"] as String).endsWith("(2/2)"), notes[1].toString())
                assertTrue(h.notes("Skipping").isEmpty(), "nothing was skipped")
            }
        }
    }

    @Test
    @DisplayName("a track that fails every retry is skipped and the next one plays")
    fun failureAfterTheRetriesIsSkipped() {
        withFake { fake ->
            harness(fake, ChainHandler()).use { h ->
                h.resolveFailure = { id -> if (id == B) forbidden(B) else null }
                val r = playingFirst(h, A, B, C)
                fake.nearTheEnd()
                polls()
                fake.state.transport = "STOPPED"
                waitUntil { r.queue.current?.id == C && r.status == PlayerStatus.PLAYING }
                assertEquals(3, h.resolves.count { it == B }, "B was tried three times")
                assertEquals(C, r.queue.current?.id)
                assertEquals(PlayerStatus.PLAYING, r.status)
                assertEquals(C, h.registered.last().videoId)
                val note = h.notes("Skipping $B").single()
                assertEquals("IllegalStateException", note["reason"])
                assertFalse(note.toString().contains("http", ignoreCase = true), "the skip note carries no URL")
            }
        }
    }

    @Test
    @DisplayName("the end of the queue after a skipped track ends stopped without looping")
    fun endOfTheQueueStops() {
        withFake { fake ->
            harness(fake, ChainHandler(order = listOf(A, B))).use { h ->
                h.resolveFailure = { id -> if (id == B) unavailable(B) else null }
                val r = playingFirst(h, A, B)
                fake.nearTheEnd()
                polls()
                fake.state.transport = "STOPPED"
                waitUntil { h.notes("Skipping $B").isNotEmpty() }
                delay(300)
                assertEquals(PlayerStatus.STOPPED, r.status)
                val resolved = h.resolves.size
                delay(1000)
                assertEquals(resolved, h.resolves.size, "no further resolve: the run did not loop")
                assertEquals(1, fake.count("SetAVTransportURI"), "nothing but A was sent")
            }
        }
    }

    @Test
    @DisplayName("five unplayable tracks in a row stop the player instead of looping")
    fun fiveInARowStop() {
        withFake { fake ->
            harness(fake, ChainHandler(autoplayChain = true)).use { h ->
                h.resolveFailure = { id -> if (id != A) unavailable(id) else null }
                val r = queued(h, A)
                r.queue.setAutoplayMode(AutoplayMode.ENABLED)
                assertTrue(r.play(r.queue.current!!, 0.0))
                polls()
                fake.nearTheEnd()
                polls()
                fake.state.transport = "STOPPED"
                waitUntil { h.notes("5 tracks in a row").isNotEmpty() }
                delay(300)
                assertEquals(1, h.notes("5 tracks in a row could not be played - stopping").size)
                assertEquals(5, h.resolves.count { it != A }, "exactly five failing videos were tried after A")
                assertEquals(PlayerStatus.STOPPED, r.status)
                val resolved = h.resolves.size
                delay(1000)
                assertEquals(resolved, h.resolves.size, "no further resolve after the stop")
            }
        }
    }

    @Test
    @DisplayName("a newer play from the sender during a retry wait cancels the retry and the skip")
    fun newerPlayCancelsTheRetry() {
        withFake { fake ->
            harness(fake, ChainHandler(), retryDelays = listOf(600L, 600L)).use { h ->
                h.resolveFailure = { id -> if (id == B) forbidden(B) else null }
                val r = playingFirst(h, A, B, C)
                fake.nearTheEnd()
                polls()
                fake.state.transport = "STOPPED"
                waitUntil { h.notes("Could not prepare $B").isNotEmpty() }
                assertTrue(r.play(Video(C, Client.YT, VideoContext(playlistId = "PL", index = 2)), 0.0))
                delay(1500)
                assertEquals(1, h.resolves.count { it == B }, "B was not resolved again")
                assertEquals(C, r.queue.current?.id)
                assertEquals(PlayerStatus.PLAYING, r.status)
                assertEquals(2, fake.count("SetAVTransportURI"), "A and C were sent")
                assertTrue(h.notes("Skipping").isEmpty(), "no skip happened")
            }
        }
    }

    @Test
    @DisplayName("a seek restart that fails to prepare never skips the track")
    fun failedSeekRestartDoesNotSkip() {
        withFake { fake ->
            harness(fake, ChainHandler()).use { h ->
                val r = playingFirst(h, A, B, C)
                h.resolveFailure = { id -> if (id == A) unavailable(A) else null }
                assertFalse(r.doSeek(100.0), "the restart could not prepare the stream")
                delay(500)
                assertEquals(A, r.queue.current?.id, "the queue did not move")
                assertTrue(h.notes("Skipping").isEmpty())
                assertEquals(0, h.resolves.count { it == B }, "B was never looked up")
                assertEquals(1, fake.count("SetAVTransportURI"))
            }
        }
    }

    @Test
    @DisplayName("the play retry delays default to 2 s and 5 s and may not be negative")
    fun retryDelaysDefaultAndValidation() {
        val dir = File("unused")
        assertEquals(listOf(2000L, 5000L), Config(dataDir = dir).playRetryDelaysMs)
        Config(dataDir = dir).validate()
        assertFailsWith<IllegalArgumentException> { Config(dataDir = dir, playRetryDelaysMs = listOf(2000L, -1L)).validate() }
    }
}

private fun forbidden(id: String) = IllegalStateException("yt-dlp failed for $id: ERROR: unable to download video data: HTTP Error 403: Forbidden")

/** The [n]th video of an endless autoplay chain: no playlist index, as YouTube's autoplay videos have. */
private fun chainVideo(n: Int) = Video("V" + n.toString().padStart(10, '0'), Client.YT, VideoContext(playlistId = "PL"))
