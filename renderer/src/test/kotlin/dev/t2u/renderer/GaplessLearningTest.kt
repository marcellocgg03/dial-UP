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

    private companion object {
        fun video(id: String, index: Int) = Video(id, Client.YT, VideoContext(playlistId = "PL", index = index))
    }
}
