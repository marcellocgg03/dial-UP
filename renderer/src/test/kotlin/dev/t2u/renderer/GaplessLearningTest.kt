package dev.t2u.renderer

// Kotlin original (plan 04-06, D-08, D-13), GPL-3.0-only: the renderer remembers per speaker whether it takes
// SetNextAVTransportURI, across app restarts (two harnesses on one data directory, a fresh FakeRenderer each, the same UDN),
// and corrects that memory when the speaker behaves differently.
//
// Every test is a block-bodied function: JUnit silently skips a test whose expression body returns a value.

import dev.t2u.lounge.Client
import dev.t2u.lounge.Playlist
import dev.t2u.lounge.PlaylistRequestHandler
import dev.t2u.lounge.PreviousNextVideos
import dev.t2u.lounge.Video
import dev.t2u.lounge.VideoContext
import dev.t2u.upnp.FakeRenderer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val A = "AAAAAAAAAAA"
private const val B = "BBBBBBBBBBB"

internal class GaplessLearningTest {
    private fun <T> withFake(fake: FakeRenderer = FakeRenderer(), block: suspend (FakeRenderer) -> T): T {
        fake.start()
        try {
            return runBlocking { block(fake) }
        } finally {
            fake.close()
        }
    }

    private fun newDir(): File = Files.createTempDirectory("renderer-").toFile().also { it.deleteOnExit() }

    private fun cfg(): (File) -> Config = {
        Config(
            dataDir = it, outputFormat = "adts", pollIntervalMs = 50, startTimeoutMs = 5000, nameSuffix = "",
            queueSettleTimeoutMs = 5000, prefetch = true, gapless = true,
        )
    }

    private class NextHandler : PlaylistRequestHandler {
        @Volatile var nextOf: (String) -> Video? = { id -> if (id == A) video(B, 1) else null }

        override suspend fun getPreviousNextVideos(target: Video, playlist: Playlist): PreviousNextVideos? =
            PreviousNextVideos(null, nextOf(target.id))
    }

    private fun setPayload(vararg ids: String) = buildJsonObject {
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

    private fun harness(fake: FakeRenderer, dir: File, handler: NextHandler = NextHandler(), clock: () -> Long = System::currentTimeMillis) =
        RendererHarness(fake, config = cfg(), dataDir = dir, playlistRequestHandlerFactory = { handler }, clock = clock)

    /** A renderer whose queue holds [ids] with the first one current, not yet playing. */
    private suspend fun queued(h: RendererHarness, vararg ids: String): Renderer {
        val r = h.renderer
        r.init()
        r.queue.updateByMessage("setPlaylist", setPayload(*ids), Client.YT)
        assertNotNull(r.queue.current, "the queue has a current video")
        return r
    }

    private fun stored(dir: File): JsonElement? = runBlocking { RendererDataStore(dir, "fake-renderer").get(LEARNED_GAPLESS_KEY) }

    private fun seed(dir: File, value: JsonElement?) = runBlocking { RendererDataStore(dir, "fake-renderer").set(LEARNED_GAPLESS_KEY, value) }

    private fun supportedIn(dir: File): Boolean? = ((stored(dir) as? JsonObject)?.get("supported") as? JsonPrimitive)?.booleanOrNull

    private fun atIn(dir: File): Long? = ((stored(dir) as? JsonObject)?.get("at") as? JsonPrimitive)?.longOrNull

    private fun modelOf(h: RendererHarness): String {
        val d = assertNotNull(h.renderer.description)
        return speakerModel(d.manufacturer, d.modelName, d.modelNumber)
    }

    @Test
    @DisplayName("a speaker that took SetNextAVTransportURI is remembered with its model, and a restarted renderer knows it before any call")
    fun learnedSupportSurvivesARestart() {
        val dir = newDir()
        val handler = NextHandler()
        withFake { fake ->
            harness(fake, dir, handler).use { h ->
                val r = queued(h, A, B)
                assertNull(r.gaplessSupport, "unknown before the first answer")
                assertTrue(r.doPlay(Video(A), 0.0))
                waitUntil { fake.state.nextUri.isNotEmpty() && supportedIn(dir) != null }
                assertEquals(true, supportedIn(dir))
                assertEquals(modelOf(h), ((stored(dir) as JsonObject)["model"] as JsonPrimitive).content)
                assertTrue(atIn(dir)!! > 0)
                assertEquals(true, r.gaplessSupport)
            }
        }
        withFake { fake ->
            harness(fake, dir, handler).use { h ->
                h.renderer.init()
                assertEquals(true, h.renderer.gaplessSupport, "known right after init")
                assertEquals(0, fake.count("SetNextAVTransportURI"), "nothing asked yet")
            }
        }
    }

    @Test
    @DisplayName("a refusal is stored as false and a restarted renderer sends no SetNextAVTransportURI at all, yet the next track still starts")
    fun aStoredFalseMeansNothingIsSent() {
        val dir = newDir()
        val handler = NextHandler()
        withFake(FakeRenderer(noGapless = true)) { fake ->
            harness(fake, dir, handler).use { h ->
                val r = queued(h, A, B)
                assertTrue(r.doPlay(Video(A), 0.0))
                waitUntil { supportedIn(dir) != null }
                assertEquals(false, supportedIn(dir))
                assertEquals(false, r.gaplessSupport)
                assertEquals(1, fake.count("SetNextAVTransportURI"))
            }
        }
        withFake(FakeRenderer(noGapless = true)) { fake ->
            harness(fake, dir, handler).use { h ->
                val r = queued(h, A, B)
                assertEquals(false, r.gaplessSupport)
                assertTrue(r.play(r.queue.current!!, 0.0))
                waitUntil { h.notes("Prepared next track").isNotEmpty() }
                kotlinx.coroutines.delay(500) // about ten polls
                assertEquals(0, fake.count("SetNextAVTransportURI"), "a stored false is not asked again")

                // The end of A: B starts from the prepared item.
                kotlinx.coroutines.delay(100)
                fake.nearTheEnd()
                kotlinx.coroutines.delay(200)
                fake.state.transport = "STOPPED"
                waitUntil { fake.count("SetAVTransportURI") == 2 }
                assertEquals(2, fake.count("SetAVTransportURI"))
                assertEquals(listOf(A, B), h.resolves.toList(), "B was loaded from the prepared item")
                assertEquals(0, fake.count("SetNextAVTransportURI"))
                assertEquals(false, supportedIn(dir))
            }
        }
    }

    @Test
    @DisplayName("a stored false is asked once more after seven days, and a new refusal stores the new time")
    fun aStoredFalseIsAskedAgainAfterSevenDays() {
        val dir = newDir()
        val handler = NextHandler()
        withFake(FakeRenderer(noGapless = true)) { fake ->
            harness(fake, dir, handler).use { h ->
                val r = queued(h, A, B)
                assertTrue(r.doPlay(Video(A), 0.0))
                waitUntil { supportedIn(dir) != null }
            }
        }
        val firstAt = assertNotNull(atIn(dir))
        val later = firstAt + GAPLESS_REPROBE_MS + 1
        withFake(FakeRenderer(noGapless = true)) { fake ->
            harness(fake, dir, handler, clock = { later }).use { h ->
                val r = queued(h, A, B)
                assertNull(r.gaplessSupport, "a week-old false is unknown again")
                assertTrue(r.doPlay(Video(A), 0.0))
                waitUntil { atIn(dir) == later }
                kotlinx.coroutines.delay(400)
                assertEquals(1, fake.count("SetNextAVTransportURI"), "asked exactly once")
                assertEquals(false, supportedIn(dir))
                assertEquals(later, atIn(dir), "the refusal stored the new time")
            }
        }
    }

    @Test
    @DisplayName("a stored false from less than seven days ago is still trusted")
    fun aRecentFalseIsTrusted() {
        val dir = newDir()
        withFake(FakeRenderer(noGapless = true)) { fake ->
            harness(fake, dir).use { h ->
                h.renderer.init()
                val model = modelOf(h)
                seed(dir, gaplessMemory(false, model, 1_000L))
                harness(fake, dir, clock = { 1_000L + GAPLESS_REPROBE_MS - 1 }).use { h2 ->
                    h2.renderer.init()
                    assertEquals(false, h2.renderer.gaplessSupport)
                }
            }
        }
    }

    @Test
    @DisplayName("a value stored for another model is ignored at init and removed from the store")
    fun anotherModelsValueIsIgnoredAndRemoved() {
        val dir = newDir()
        seed(dir, gaplessMemory(true, "Other|Box|1", System.currentTimeMillis()))
        withFake { fake ->
            harness(fake, dir).use { h ->
                h.renderer.init()
                assertNull(h.renderer.gaplessSupport)
                assertNull(stored(dir), "removed")
            }
        }
    }

    @Test
    @DisplayName("a malformed stored value is ignored and the speaker is simply asked")
    fun aMalformedValueIsIgnored() {
        val dir = newDir()
        seed(dir, JsonPrimitive("yes"))
        withFake { fake ->
            harness(fake, dir).use { h ->
                val r = queued(h, A, B)
                assertNull(r.gaplessSupport)
                assertTrue(r.doPlay(Video(A), 0.0))
                waitUntil { supportedIn(dir) != null }
                assertEquals(true, supportedIn(dir), "the answer replaced the junk")
            }
        }
    }

    // ---- Task 2: contradictions, the stop at the switch, the bounded retry ----

    /** The model string the fake's description gives (asserted against the renderer's own in the first test below). */
    private val fakeModel = speakerModel("Acme", "Speaker 1", "")

    private fun streamPath(n: Int) = "/stream/" + n.toString().padStart(32, '0') + ".aac"

    /** A is playing, the speaker took B as its next track, and a poll has seen A near its end. */
    private suspend fun RendererHarness.playingWithBQueued(fake: FakeRenderer): Renderer {
        val r = queued(this, A, B)
        assertTrue(r.play(r.queue.current!!, 0.0))
        waitUntil { fake.state.nextUri.isNotEmpty() && r.gaplessSupport == true }
        kotlinx.coroutines.delay(200) // a poll has seen A playing
        fake.nearTheEnd()
        kotlinx.coroutines.delay(200) // and near its end
        return r
    }

    @Test
    @DisplayName("the fake's model string is the one the renderer stores")
    fun theFakeModelMatches() {
        withFake { fake ->
            harness(fake, newDir()).use { h ->
                h.renderer.init()
                assertEquals(fakeModel, modelOf(h))
            }
        }
    }

    @Test
    @DisplayName("a stored true followed by a refusal becomes false and nothing more is sent this run")
    fun aStoredTrueThenARefusalBecomesFalse() {
        val dir = newDir()
        seed(dir, gaplessMemory(true, fakeModel, System.currentTimeMillis()))
        withFake(FakeRenderer(noGapless = true)) { fake ->
            harness(fake, dir).use { h ->
                val r = queued(h, A, B)
                assertEquals(true, r.gaplessSupport, "the stored true is trusted")
                assertTrue(r.doPlay(Video(A), 0.0))
                waitUntil { supportedIn(dir) == false }
                assertEquals(false, supportedIn(dir))
                assertEquals(false, r.gaplessSupport)
                kotlinx.coroutines.delay(400)
                assertEquals(1, fake.count("SetNextAVTransportURI"), "no further SetNext this run")
            }
        }
    }

    @Test
    @DisplayName("a speaker that stops at the end instead of playing the accepted queued track forgets gapless and the queue advances with the prepared track")
    fun aStopInsteadOfTheSwitchForgetsGapless() {
        val dir = newDir()
        withFake { fake ->
            harness(fake, dir).use { h ->
                val r = h.playingWithBQueued(fake)
                waitUntil { supportedIn(dir) == true }
                assertEquals(true, supportedIn(dir))
                // The speaker ignores its next slot: it stops with the current URI still A.
                fake.state.transport = "STOPPED"
                waitUntil { fake.count("SetAVTransportURI") == 2 }
                assertEquals(2, fake.count("SetAVTransportURI"))
                val second = fake.calls.filter { it.action == "SetAVTransportURI" }[1]
                assertTrue(second.body.contains(streamPath(2)), "B's path")
                assertEquals(listOf(A, B), h.resolves.toList())
                assertNull(stored(dir), "the key is removed")
                assertNull(r.gaplessSupport)
                assertEquals(1, h.notes("Speaker stopped at the end instead of playing the queued track - gapless support forgotten").size)
            }
        }
    }

    @Test
    @DisplayName("a STOPPED reading while the speaker holds the queued stream waits, and a following PLAYING is adopted as a normal switch")
    fun aStopAtTheSwitchThenPlayingIsAdopted() {
        val dir = newDir()
        withFake { fake ->
            harness(fake, dir).use { h ->
                val r = h.playingWithBQueued(fake)
                val loads = fake.count("SetAVTransportURI")
                val stops = fake.count("Stop")
                val plays = fake.count("Play")
                fake.state.currentUri = fake.state.nextUri
                fake.state.nextUri = ""
                fake.state.transport = "STOPPED"
                waitUntil { h.notes("Speaker stopped at the switch - waiting for it to play the queued track").isNotEmpty() }
                kotlinx.coroutines.delay(50) // about one more poll, within the grace
                fake.state.transport = "PLAYING"
                waitUntil { r.nowPlaying?.videoId == B }
                assertEquals(B, r.nowPlaying?.videoId)
                assertEquals(loads, fake.count("SetAVTransportURI"), "B was not loaded")
                assertEquals(stops, fake.count("Stop"), "no Stop for B")
                assertEquals(plays, fake.count("Play"), "B's start did not play twice")
                assertEquals(1, h.notes("Speaker stopped at the switch").size, "noted once")
                assertTrue(h.notes("Now playing").any { (it["message"] as String).contains("continued gaplessly") })
                assertNotNull(h.notes("Renderer continued into the next track").single()["via"])
                assertEquals(true, supportedIn(dir), "the memory is kept")
                assertEquals(true, r.gaplessSupport)
            }
        }
    }

    @Test
    @DisplayName("a speaker that holds the queued stream but stays STOPPED past the grace is advanced normally, once, and gapless is forgotten")
    fun aStopPastTheGraceAdvancesNormally() {
        val dir = newDir()
        withFake { fake ->
            harness(fake, dir).use { h ->
                val r = h.playingWithBQueued(fake)
                val plays = fake.count("Play")
                fake.state.currentUri = fake.state.nextUri
                fake.state.nextUri = ""
                fake.state.transport = "STOPPED"
                waitUntil { fake.count("SetAVTransportURI") == 2 && fake.count("Play") == plays + 1 }
                kotlinx.coroutines.delay(400)
                assertEquals(2, fake.count("SetAVTransportURI"), "B loaded once")
                assertEquals(plays + 1, fake.count("Play"), "one Play for B")
                assertEquals(listOf(A, B), h.resolves.toList())
                assertEquals(B, r.nowPlaying?.videoId)
                assertNull(stored(dir), "the key is removed")
                assertNull(r.gaplessSupport)
                assertTrue(h.notes("Speaker stopped at the switch").size == 1)
            }
        }
    }

    @Test
    @DisplayName("an unclear SetNextAVTransportURI failure is retried from the next polls, at most three times, and never marks the speaker unsupported")
    fun anUnclearFailureIsRetriedThreeTimes() {
        val dir = newDir()
        withFake(FakeRenderer(bareFaultOn = setOf("SetNextAVTransportURI"))) { fake ->
            harness(fake, dir).use { h ->
                val r = queued(h, A, B)
                assertTrue(r.doPlay(Video(A), 0.0))
                waitUntil { h.notes("Prepared next track").isNotEmpty() }
                kotlinx.coroutines.delay(700) // about fourteen polls
                assertEquals(3, fake.count("SetNextAVTransportURI"), "three attempts, then none")
                assertNull(r.gaplessSupport, "an unclear error never marks the speaker as unsupported")
                assertNull(stored(dir))
                assertTrue(r.doPlay(Video(B), 0.0))
                assertEquals(listOf(A, B), h.resolves.toList(), "B still starts from the prepared item")
                assertEquals(2, fake.count("SetAVTransportURI"))
            }
        }
    }

    private companion object {
        fun video(id: String, index: Int) = Video(id, Client.YT, VideoContext(playlistId = "PL", index = index))
    }
}
