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
}

/** The [n]th video of an endless autoplay chain: no playlist index, as YouTube's autoplay videos have. */
private fun chainVideo(n: Int) = Video("V" + n.toString().padStart(10, '0'), Client.YT, VideoContext(playlistId = "PL"))
