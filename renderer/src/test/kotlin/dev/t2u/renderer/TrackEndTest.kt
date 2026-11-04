package dev.t2u.renderer

// Track-end handling of plan 03-09 (PLAY-06/07, the "plays through" criteria), driven against the
// upnp FakeRenderer. GPL-3.0-only.
//
// Kotlin additions over Node (plan 03-12 copies these into PARITY.md "Adaptations"):
//  * A stop seen on the speaker in the middle of a track is mirrored as STOPPED and does not advance the queue
//    (Node advances on any stop after the track was seen playing).
//  * A track end waits (bounded) for a playlist refresh in flight before it asks for the next track.
//
// Every test is a block-bodied function: JUnit silently skips a test whose expression body returns a value.

import dev.t2u.lounge.Client
import dev.t2u.lounge.Playlist
import dev.t2u.lounge.PlaylistRequestHandler
import dev.t2u.lounge.PlayerStatus
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
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private const val A = "AAAAAAAAAAA"
private const val B = "BBBBBBBBBBB"

internal class TrackEndTest {
    private fun <T> withFake(fake: FakeRenderer = FakeRenderer(), block: suspend (FakeRenderer) -> T): T {
        fake.start()
        try {
            return runBlocking { block(fake) }
        } finally {
            fake.close()
        }
    }

    private fun cfg(settleMs: Long = 5000): (File) -> Config = {
        // prefetch = false: Node's test setup() omits PREFETCH, and the Config default (true) would prepare B behind these tests.
        Config(
            dataDir = it, outputFormat = "adts", pollIntervalMs = 50, startTimeoutMs = 5000, nameSuffix = "",
            queueSettleTimeoutMs = settleMs, prefetch = false,
        )
    }

    /** Hands out B after A. [refreshDelayMs] makes every later lookup slow (a refresh in flight). */
    private class QueueHandler : PlaylistRequestHandler {
        @Volatile var refreshDelayMs = 0L

        override suspend fun getPreviousNextVideos(target: Video, playlist: Playlist): PreviousNextVideos? {
            if (target.id != A) return null
            if (refreshDelayMs > 0) delay(refreshDelayMs)
            return PreviousNextVideos(null, Video(B, Client.YT, VideoContext(playlistId = "PL", index = 1)))
        }
    }

    private fun payload(): JsonObject = buildJsonObject {
        put("listId", "PL")
        put("videoIds", "$A,$B")
        put("videoId", A)
        put("currentIndex", "0")
        put("currentTime", "0")
    }

    private fun updatePayload(): JsonObject = buildJsonObject {
        put("listId", "PL")
        put("videoIds", "$A,$B")
    }

    private fun FakeRenderer.count(action: String): Int = calls.count { it.action == action }

    private fun FakeRenderer.nearTheEnd() {
        state.relTime = "0:03:28"
    }

    /** Four poll intervals: long enough for a poll to have read what the test just set on the fake. */
    private suspend fun polls() = delay(200)

    /** A renderer playing A with B queued, the speaker already seen PLAYING. */
    private suspend fun playingA(h: RendererHarness, handler: QueueHandler): Renderer {
        val r = h.renderer
        r.init()
        r.queue.updateByMessage("setPlaylist", payload(), Client.YT)
        assertTrue(r.play(r.queue.current!!, 0.0))
        polls()
        return r
    }

    private fun harness(fake: FakeRenderer, handler: QueueHandler, settleMs: Long = 5000, duration: Double = 210.0) =
        RendererHarness(fake, config = cfg(settleMs), playlistRequestHandlerFactory = { handler }, duration = duration)

    @Test
    @DisplayName("a stop at the end of the track advances the queue")
    fun stopNearTheEndAdvances() {
        withFake { fake ->
            val handler = QueueHandler()
            harness(fake, handler).use { h ->
                val r = playingA(h, handler)
                fake.nearTheEnd()
                polls()
                fake.state.transport = "STOPPED"
                waitUntil { fake.count("SetAVTransportURI") == 2 && r.status == PlayerStatus.PLAYING }
                assertEquals(2, fake.count("SetAVTransportURI"))
                assertEquals(PlayerStatus.PLAYING, r.status)
                assertEquals(B, r.queue.current?.id)
            }
        }
    }

    @Test
    @DisplayName("a stop in the middle of a track is mirrored as stopped and does not skip")
    fun midTrackStopIsMirroredNotAdvanced() {
        withFake { fake ->
            val handler = QueueHandler()
            harness(fake, handler).use { h ->
                val r = playingA(h, handler)
                fake.state.transport = "STOPPED" // RelTime stays 0:01:05 of 3:30
                waitUntil { r.status == PlayerStatus.STOPPED }
                assertEquals(PlayerStatus.STOPPED, r.status)
                polls()
                assertEquals(1, fake.count("SetAVTransportURI"), "no second track was sent")
                assertEquals(A, r.queue.current?.id)
                assertTrue(h.notes("Track stopped on the speaker").isNotEmpty(), "the mirrored stop is noted")
            }
        }
    }

    @Test
    @DisplayName("a position that never moved counts as a track end")
    fun unreportedPositionAdvances() {
        withFake { fake ->
            val handler = QueueHandler()
            harness(fake, handler).use { h ->
                val r = playingA(h, handler)
                fake.state.relTime = "NOT_IMPLEMENTED"
                polls()
                fake.state.transport = "STOPPED"
                waitUntil { fake.count("SetAVTransportURI") == 2 && r.status == PlayerStatus.PLAYING }
                assertEquals(B, r.queue.current?.id)
                assertEquals(PlayerStatus.PLAYING, r.status)
            }
        }
    }

    @Test
    @DisplayName("an unknown length counts as a track end")
    fun unknownLengthAdvances() {
        withFake { fake ->
            fake.state.trackDuration = "NOT_IMPLEMENTED"
            val handler = QueueHandler()
            harness(fake, handler, duration = 0.0).use { h ->
                val r = playingA(h, handler)
                fake.state.transport = "STOPPED" // RelTime 0:01:05, length unknown
                waitUntil { fake.count("SetAVTransportURI") == 2 && r.status == PlayerStatus.PLAYING }
                assertEquals(B, r.queue.current?.id)
                assertEquals(PlayerStatus.PLAYING, r.status)
            }
        }
    }

    @Test
    @DisplayName("a track end waits for a playlist refresh in flight and then plays the next track")
    fun trackEndWaitsForARefreshInFlight() {
        withFake { fake ->
            val handler = QueueHandler()
            harness(fake, handler).use { h ->
                val r = playingA(h, handler)
                fake.nearTheEnd()
                polls()
                handler.refreshDelayMs = 600
                h.scope.launch { r.queue.updateByMessage("updatePlaylist", updatePayload(), Client.YT) }
                waitUntil { r.queue.isUpdating }
                assertTrue(r.queue.isUpdating, "the refresh is in flight")
                fake.state.transport = "STOPPED"
                waitUntil { fake.count("SetAVTransportURI") == 2 && r.status == PlayerStatus.PLAYING }
                assertEquals(2, fake.count("SetAVTransportURI"))
                assertEquals(PlayerStatus.PLAYING, r.status)
                assertEquals(B, r.queue.current?.id)
            }
        }
    }

    @Test
    @DisplayName("a refresh that never finishes ends the player stopped, not playing with polling dead")
    fun refreshThatNeverFinishesEndsStopped() {
        withFake { fake ->
            val handler = QueueHandler()
            harness(fake, handler, settleMs = 300).use { h ->
                val r = playingA(h, handler)
                fake.nearTheEnd()
                polls()
                handler.refreshDelayMs = 60_000
                h.scope.launch { r.queue.updateByMessage("updatePlaylist", updatePayload(), Client.YT) }
                waitUntil { r.queue.isUpdating }
                fake.state.transport = "STOPPED"
                waitUntil { r.status == PlayerStatus.STOPPED }
                assertEquals(PlayerStatus.STOPPED, r.status)
                assertEquals(1, fake.count("SetAVTransportURI"), "nothing was advanced")
                val polled = fake.count("GetTransportInfo")
                polls()
                assertEquals(polled, fake.count("GetTransportInfo"), "polling stopped")
            }
        }
    }

    @Test
    @DisplayName("the last item of the queue ends stopped")
    fun lastItemEndsStopped() {
        withFake { fake ->
            val handler = QueueHandler()
            harness(fake, handler).use { h ->
                val r = playingA(h, handler)
                fake.nearTheEnd()
                polls()
                fake.state.transport = "STOPPED"
                waitUntil { fake.count("SetAVTransportURI") == 2 && r.status == PlayerStatus.PLAYING }
                assertEquals(B, r.queue.current?.id)
                polls() // B has been seen playing

                fake.state.transport = "STOPPED"
                waitUntil { r.status == PlayerStatus.STOPPED }
                assertEquals(PlayerStatus.STOPPED, r.status)
                polls()
                assertEquals(2, fake.count("SetAVTransportURI"), "nothing follows B")
            }
        }
    }

    @Test
    @DisplayName("the queue settle timeout defaults to 10 s and may not be negative")
    fun settleTimeoutDefaultAndValidation() {
        val dir = File("unused")
        assertEquals(10_000L, Config(dataDir = dir).queueSettleTimeoutMs)
        Config(dataDir = dir).validate()
        assertFailsWith<IllegalArgumentException> { Config(dataDir = dir, queueSettleTimeoutMs = -1).validate() }
    }
}
