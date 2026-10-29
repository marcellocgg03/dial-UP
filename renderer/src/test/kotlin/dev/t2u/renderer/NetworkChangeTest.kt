package dev.t2u.renderer

// SVC-04 and D-07 (plan 05-08): a renderer follows the phone's move to another address. It builds stream URLs with the new
// address, still counts a URL with one of its last own addresses as its own, keeps quiet while the network is down and
// re-sets the loaded track at the speaker's position. Driven against the upnp FakeRenderer through RendererHarness.
// GPL-3.0-only.
//
// Every test is a block-bodied function: JUnit silently skips a test whose expression body returns a value.

import dev.t2u.lounge.Client
import dev.t2u.lounge.PlayerStatus
import dev.t2u.lounge.Playlist
import dev.t2u.lounge.PlaylistRequestHandler
import dev.t2u.lounge.PreviousNextVideos
import dev.t2u.lounge.Video
import dev.t2u.lounge.VideoContext
import dev.t2u.upnp.FakeRenderer
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val A = "AAAAAAAAAAA"

internal class NetworkChangeTest {
    private fun <T> withFake(fake: FakeRenderer = FakeRenderer(), block: suspend (FakeRenderer) -> T): T {
        fake.start()
        try {
            return runBlocking { block(fake) }
        } finally {
            fake.close()
        }
    }

    private fun pinned(format: String, prefetch: Boolean = false): (File) -> Config =
        { Config(dataDir = it, outputFormat = format, pollIntervalMs = 50, startTimeoutMs = 2000, nameSuffix = "", prefetch = prefetch) }

    private fun FakeRenderer.count(action: String): Int = calls.count { it.action == action }

    private fun FakeRenderer.seekTargets(): List<String> =
        calls.filter { it.action == "Seek" }.map { Regex("<Target>(.*?)</Target>").find(it.body)?.groupValues?.get(1) ?: "" }

    /** The CurrentURI of every SetAVTransportURI the speaker received, in order. */
    private fun FakeRenderer.uris(): List<String> =
        calls.filter { it.action == "SetAVTransportURI" }.map { Regex("<CurrentURI>([^<]*)</CurrentURI>").find(it.body)?.groupValues?.get(1).orEmpty() }

    private fun hostOf(uri: String): String = URI(uri).host

    @Test
    @DisplayName("a renderer remembers at most its last 4 own addresses, newest last, and never lists the current one")
    fun ownHostsAreBounded() {
        withFake { fake ->
            RendererHarness(fake, config = pinned("adts")).use { h ->
                h.renderer.init()
                for (host in listOf("127.0.0.2", "127.0.0.3", "127.0.0.4", "127.0.0.5", "127.0.0.6", "127.0.0.6")) {
                    h.renderer.onNetworkChanged(host)
                }
                assertEquals(listOf("127.0.0.2", "127.0.0.3", "127.0.0.4", "127.0.0.5"), h.renderer.ownHosts)
            }
        }
    }

    @Test
    @DisplayName("a speaker that still reports a URL with an old own address is not released as another source")
    fun anOldOwnAddressIsStillOurs() {
        withFake { fake ->
            RendererHarness(fake, config = pinned("adts")).use { h ->
                h.renderer.init()
                assertTrue(h.renderer.play(Video(A), 0.0))
                val old = fake.uris().first()
                assertEquals("127.0.0.1", hostOf(old))
                fake.state.reportedUri = old

                h.renderer.onNetworkLost()
                h.renderer.onNetworkChanged("127.0.0.2")
                delay(600) // twelve poll intervals, six times the foreign-poll debounce

                assertTrue(h.notes("the speaker was taken over").isEmpty(), "not released as another source")
                assertEquals(PlayerStatus.PLAYING, h.renderer.status)
            }
        }
    }

    @Test
    @DisplayName("a URL with an address the renderer never served from is still another source")
    fun aStrangersAddressIsStillForeign() {
        withFake { fake ->
            RendererHarness(fake, config = pinned("adts")).use { h ->
                h.renderer.init()
                assertTrue(h.renderer.play(Video(A), 0.0))
                h.renderer.onNetworkChanged("127.0.0.2")
                fake.state.reportedUri = fake.uris().first().replace("127.0.0.1", "127.0.0.9")

                waitUntil { h.notes("the speaker was taken over").isNotEmpty() }
                assertEquals(1, h.notes("the speaker was taken over").size)
            }
        }
    }

    // ------------------------------------------------------------------------------------------ the re-set (D-07)

    private fun RendererHarness.resetDone() = notes("Re-set").isNotEmpty()

    /** The actions the speaker received from its [nth] (1-based) SetAVTransportURI on, polls left out. */
    private fun FakeRenderer.actionsAfterUri(nth: Int): List<String> {
        val quiet = setOf("GetTransportInfo", "GetPositionInfo", "GetMediaInfo")
        var seen = 0
        val out = ArrayList<String>()
        for (c in calls) {
            if (c.action == "SetAVTransportURI") seen++
            if (seen >= nth && c.action !in quiet) out.add(c.action)
        }
        return out
    }

    private suspend fun move(h: RendererHarness, host: String = "127.0.0.2") {
        h.renderer.onNetworkLost()
        h.renderer.onNetworkChanged(host)
    }

    @Test
    @DisplayName("a playing pipe stream is re-set from the new address at the speaker's position and Played, with no volume call")
    fun aPlayingPipeStreamIsResetAtTheSpeakersPosition() {
        withFake { fake ->
            RendererHarness(fake, config = pinned("adts")).use { h ->
                h.renderer.init()
                assertTrue(h.renderer.play(Video(A), 0.0))
                move(h)
                waitUntil { h.resetDone() }

                val uris = fake.uris()
                assertEquals(2, uris.size)
                assertEquals("127.0.0.2", hostOf(uris[1]))
                assertEquals(listOf("adts" to 0, "adts" to 65), h.registered.map { it.format to it.startAt })
                assertEquals(listOf("SetAVTransportURI", "Play"), fake.actionsAfterUri(2))
                assertEquals(0, fake.count("SetVolume") + fake.count("SetMute"))
                assertEquals(PlayerStatus.PLAYING, h.renderer.status)
                val note = h.notes("Re-set \"Song $A\" after the address change at 65s").single()
                assertEquals(A, note["videoId"])
                assertEquals("adts", note["format"])
                assertEquals(false, note["paused"])
            }
        }
    }

    @Test
    @DisplayName("a playing direct track is re-set with SetAVTransportURI, Play and then a Seek to the speaker's position")
    fun aPlayingDirectTrackIsResetWithPlayThenSeek() {
        withFake { fake ->
            RendererHarness(fake, config = pinned("direct")).use { h ->
                h.renderer.init()
                assertTrue(h.renderer.play(Video(A), 0.0))
                move(h)
                waitUntil { h.resetDone() }

                assertEquals("127.0.0.2", hostOf(fake.uris()[1]))
                assertEquals(listOf("SetAVTransportURI", "Play", "Seek"), fake.actionsAfterUri(2))
                assertEquals(listOf("00:01:05"), fake.seekTargets())
            }
        }
    }

    @Test
    @DisplayName("a paused mp4 track is re-set as a file at the offset and stays paused (no Play)")
    fun aPausedFileFormatIsResetAtAnOffsetAndStaysPaused() {
        withFake { fake ->
            RendererHarness(fake, config = pinned("mp4")).use { h ->
                h.renderer.init()
                assertTrue(h.renderer.play(Video(A), 0.0))
                assertTrue(h.renderer.pause())
                val plays = fake.count("Play")
                move(h)
                waitUntil { h.resetDone() }

                assertEquals(listOf("mp4" to 0, "mp4" to 65), h.registered.map { it.format to it.startAt })
                assertEquals("127.0.0.2", hostOf(fake.uris()[1]))
                assertEquals(plays, fake.count("Play"), "no Play for a paused track")
                assertEquals(PlayerStatus.PAUSED, h.renderer.status)
                assertEquals(true, h.notes("Re-set").single()["paused"])
            }
        }
    }

    @Test
    @DisplayName("a paused direct track is re-set without Play and the Seek it owes is sent once the phone resumes and the speaker plays")
    fun aPausedDirectTrackOwesItsSeekUntilTheResume() {
        withFake { fake ->
            RendererHarness(fake, config = pinned("direct")).use { h ->
                h.renderer.init()
                assertTrue(h.renderer.play(Video(A), 0.0))
                assertTrue(h.renderer.pause())
                val plays = fake.count("Play")
                move(h)
                waitUntil { h.resetDone() }
                delay(300) // several polls
                assertEquals(plays, fake.count("Play"), "no Play while paused")
                assertTrue(fake.seekTargets().isEmpty(), "the Seek waits for the resume")
                assertEquals(PlayerStatus.PAUSED, h.renderer.status)

                assertTrue(h.renderer.resume())
                waitUntil { fake.seekTargets().isNotEmpty() }
                delay(300)
                assertEquals(listOf("00:01:05"), fake.seekTargets(), "sent once")
            }
        }
    }

    @Test
    @DisplayName("a renderer with no track, and one whose player is stopped, send nothing to the speaker after a move")
    fun nothingIsSentWithoutAPlayingOrPausedTrack() {
        withFake { fake ->
            RendererHarness(fake, config = pinned("adts")).use { h ->
                val acting = setOf("SetAVTransportURI", "Stop", "Play", "Seek")
                h.renderer.init()
                move(h)
                delay(300)
                assertEquals(0, fake.calls.count { it.action in acting })

                assertTrue(h.renderer.play(Video(A), 0.0))
                assertTrue(h.renderer.stop())
                assertEquals(PlayerStatus.STOPPED, h.renderer.status)
                val before = fake.calls.count { it.action in acting }
                move(h, "127.0.0.3")
                delay(300)
                assertEquals(before, fake.calls.count { it.action in acting })
                assertTrue(h.notes("Re-set").isEmpty())
            }
        }
    }

    @Test
    @DisplayName("a load in flight when the change arrives is waited for and then re-set once from the new address")
    fun aLoadInFlightIsWaitedForAndThenResetOnce() {
        withFake { fake ->
            RendererHarness(fake, config = pinned("adts"), prepareDelayMs = 800).use { h ->
                h.renderer.init()
                val play = h.scope.async { h.renderer.play(Video(A), 0.0) }
                waitUntil { h.prepared.isNotEmpty() }
                move(h)
                assertTrue(play.await())
                waitUntil(8000) { h.resetDone() }
                delay(300)

                val uris = fake.uris()
                assertEquals(2, uris.size, "the landed load was re-set once")
                assertEquals("127.0.0.1", hostOf(uris[0]))
                assertEquals("127.0.0.2", hostOf(uris[1]))
                assertEquals(1, h.notes("Re-set").size)
            }
        }
    }

    @Test
    @DisplayName("the prepared next track is dropped and prepared again with the new address")
    fun theQueuedNextTrackIsPreparedAgainOnTheNewAddress() {
        withFake { fake ->
            val handler = NextHandler()
            val cfg: (File) -> Config = {
                Config(dataDir = it, outputFormat = "adts", pollIntervalMs = 50, startTimeoutMs = 5000, nameSuffix = "", queueSettleTimeoutMs = 5000, prefetch = true, gapless = true)
            }
            RendererHarness(fake, config = cfg, playlistRequestHandlerFactory = { handler }).use { h ->
                val r = h.renderer
                r.init()
                r.queue.updateByMessage("setPlaylist", setPayload(A, B), Client.YT)
                assertTrue(r.play(r.queue.current!!, 0.0))
                waitUntil { fake.state.nextUri.isNotEmpty() }
                assertEquals("127.0.0.1", hostOf(fake.state.nextUri))
                val oldNextPath = URI(fake.state.nextUri).path

                move(h)
                waitUntil { h.resetDone() }
                waitUntil { fake.state.nextUri.isNotEmpty() && hostOf(fake.state.nextUri) == "127.0.0.2" }

                assertEquals("127.0.0.2", hostOf(fake.state.nextUri), "the next track is queued again with the new address")
                assertTrue(oldNextPath in h.released, "the old prepared item was released")
            }
        }
    }

    @Test
    @DisplayName("a move onto the same address still re-sets the loaded track once")
    fun aMoveOntoTheSameAddressStillResets() {
        withFake { fake ->
            RendererHarness(fake, config = pinned("adts")).use { h ->
                h.renderer.init()
                assertTrue(h.renderer.play(Video(A), 0.0))
                move(h, "127.0.0.1")
                waitUntil { h.resetDone() }
                delay(300)

                assertEquals(2, fake.uris().size)
                assertEquals(1, h.notes("Re-set").size)
                assertEquals("127.0.0.1", hostOf(fake.uris()[1]))
            }
        }
    }

    @Test
    @DisplayName("when the speaker's position cannot be read the last polled or started position is used, never an extrapolation")
    fun anUnreadablePositionFallsBackToTheKnownOne() {
        withFake(FakeRenderer(bareFaultOn = setOf("GetPositionInfo"))) { fake ->
            RendererHarness(fake, config = pinned("adts")).use { h ->
                h.renderer.init()
                assertTrue(h.renderer.play(Video(A), 40.0))
                move(h)
                waitUntil { h.resetDone() }

                // nothing could be read from the speaker: the position the track was started at is what is known
                assertEquals(listOf("adts" to 40, "adts" to 40), h.registered.map { it.format to it.startAt })
            }
        }
    }
}

private const val B = "BBBBBBBBBBB"

/** Answers what follows a video: B after A. */
private class NextHandler : PlaylistRequestHandler {
    override suspend fun getPreviousNextVideos(target: Video, playlist: Playlist): PreviousNextVideos? =
        PreviousNextVideos(null, if (target.id == A) Video(B, Client.YT, VideoContext(playlistId = "PL", index = 1)) else null)
}

private fun setPayload(vararg ids: String): JsonObject = buildJsonObject {
    put("listId", "PL")
    put("videoIds", ids.joinToString(","))
    put("videoId", ids.first())
    put("currentIndex", "0")
    put("currentTime", "0")
}
