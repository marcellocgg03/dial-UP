package dev.t2u.renderer

// CAST-06, speaker side (plan 03-08): after a disconnect or a service Stop the speaker is left with nothing
// loaded, and only when the speaker still holds one of this app's own streams. GPL-3.0-only.
//
// Every test method has a block body that returns Unit: JUnit Jupiter silently skips a test whose expression
// body returns a value (the lesson of plan 03-07).

import dev.t2u.lounge.Video
import dev.t2u.upnp.FakeCall
import dev.t2u.upnp.FakeRenderer
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val DQW = "dQw4w9WgXcQ"
private const val OTHER = "http://192.168.1.50/other.mp3"

class ClearSpeakerTest {
    private fun withFake(fake: FakeRenderer = FakeRenderer(), block: suspend (FakeRenderer) -> Unit) {
        fake.start()
        try {
            runBlocking<Unit> { block(fake) }
        } finally {
            fake.close()
        }
    }

    private fun List<FakeCall>.actions(): List<String> = map { it.action }

    private fun clearsSent(fake: FakeRenderer): List<FakeCall> =
        fake.calls.filter { it.action == "SetAVTransportURI" && it.body.contains("<CurrentURI></CurrentURI>") }

    @Test
    @DisplayName("reset after a cast stops the speaker, empties its transport URI and stops polling")
    fun resetEmptiesTheTransportUri() {
        withFake { fake ->
            RendererHarness(fake).use { h ->
                h.renderer.init()
                assertTrue(h.renderer.doPlay(Video(DQW), 0.0))
                assertTrue(fake.state.currentUri.contains("/stream/"), fake.state.currentUri)
                val before = fake.calls.size

                h.renderer.reset()

                val after = fake.calls.drop(before)
                val stopAt = after.indexOfFirst { it.action == "Stop" }
                val clearAt = after.indexOfFirst { it.action == "SetAVTransportURI" }
                assertTrue(stopAt >= 0 && clearAt > stopAt, "Stop then SetAVTransportURI expected, got ${after.actions()}")
                assertTrue(after[clearAt].body.contains("<CurrentURI></CurrentURI>"), after[clearAt].body)
                assertTrue(after[clearAt].body.contains("<CurrentURIMetaData></CurrentURIMetaData>"), after[clearAt].body)
                assertEquals("", fake.state.currentUri)
                assertNull(h.renderer.nowPlaying)
                assertTrue(h.notes("Cleared the speaker").isNotEmpty())

                val mark = fake.calls.size
                delay(300)
                val polled = fake.calls.drop(mark).filter { it.action == "GetTransportInfo" || it.action == "GetPositionInfo" }
                assertTrue(polled.isEmpty(), "polling went on after the clear: ${polled.actions()}")
            }
        }
    }

    @Test
    @DisplayName("a speaker that moved to another source is left alone")
    fun foreignUriIsNotCleared() {
        withFake { fake ->
            RendererHarness(fake).use { h ->
                h.renderer.init()
                assertTrue(h.renderer.doPlay(Video(DQW), 0.0))
                fake.state.currentUri = OTHER
                val before = fake.calls.size

                h.renderer.reset()

                val after = fake.calls.drop(before)
                assertTrue(after.none { it.action == "Stop" || it.action == "SetAVTransportURI" }, "sent ${after.actions()}")
                assertEquals(OTHER, fake.state.currentUri)
                assertTrue(h.notes("clear skipped").isNotEmpty())
            }
        }
    }

    @Test
    @DisplayName("a renderer that loaded nothing this session sends nothing, even if the speaker holds an app-looking URL")
    fun neverLoadedSendsNothing() {
        withFake { fake ->
            RendererHarness(fake).use { h ->
                h.renderer.init()
                fake.state.currentUri = "http://127.0.0.1:8000/stream/00000000000000000000000000000001.m4a"
                val before = fake.calls.size

                h.renderer.reset()

                val after = fake.calls.drop(before)
                assertTrue(after.none { it.action == "Stop" || it.action == "SetAVTransportURI" }, "sent ${after.actions()}")
            }
        }
    }

    @Test
    @DisplayName("the clear runs after a sender Stop, which alone leaves the track loaded")
    fun clearRunsAfterSenderStop() {
        withFake { fake ->
            RendererHarness(fake).use { h ->
                h.renderer.init()
                assertTrue(h.renderer.play(Video(DQW), 0.0))
                assertTrue(h.renderer.stop())
                assertTrue(fake.state.currentUri.contains("/stream/"), "a plain Stop must leave the track loaded")
                assertTrue(clearsSent(fake).isEmpty())

                h.renderer.reset()

                assertEquals(1, clearsSent(fake).size)
                assertEquals("", fake.state.currentUri)
            }
        }
    }

    @Test
    @DisplayName("a Stop the speaker answers with a bare fault does not stop the empty URI from being sent")
    fun stopFaultDoesNotBlockTheClear() {
        withFake(FakeRenderer(bareFaultOn = setOf("Stop"))) { fake ->
            RendererHarness(fake).use { h ->
                h.renderer.init()
                assertTrue(h.renderer.doPlay(Video(DQW), 0.0))

                h.renderer.reset()

                assertEquals(1, clearsSent(fake).size)
                assertEquals("", fake.state.currentUri)
            }
        }
    }

    @Test
    @DisplayName("after destroy, reset sends nothing and does not throw")
    fun resetAfterDestroyDoesNothing() {
        withFake { fake ->
            RendererHarness(fake).use { h ->
                h.renderer.init()
                assertTrue(h.renderer.doPlay(Video(DQW), 0.0))
                h.renderer.destroy()
                val before = fake.calls.size

                h.renderer.reset()

                assertTrue(fake.calls.drop(before).none { it.action == "Stop" || it.action == "SetAVTransportURI" })
            }
        }
    }

    @Test
    @DisplayName("a speaker that is gone does not hold the clear past its budget")
    fun unreachableSpeakerIsBounded() {
        withFake { fake ->
            RendererHarness(fake).use { h ->
                h.renderer.init()
                assertTrue(h.renderer.doPlay(Video(DQW), 0.0))
                fake.close()

                val t0 = System.nanoTime()
                h.renderer.clearSpeaker()
                val ms = (System.nanoTime() - t0) / 1_000_000

                assertTrue(ms < 5000, "took $ms ms")
            }
        }
    }

    @Test
    @DisplayName("a speaker that accepts the connection and never answers is cut at the budget")
    fun silentSpeakerIsBounded() {
        withFake { fake ->
            RendererHarness(fake).use { h ->
                h.renderer.init()
                assertTrue(h.renderer.doPlay(Video(DQW), 0.0))
                fake.onAction = { action -> if (action == "GetMediaInfo") Thread.sleep(15_000) }

                val t0 = System.nanoTime()
                h.renderer.clearSpeaker()
                val ms = (System.nanoTime() - t0) / 1_000_000

                assertTrue(ms in 3500..5000, "took $ms ms")
            }
        }
    }

    @Test
    @DisplayName("a speaker that refuses the empty URI is recorded in the evidence log")
    fun refusalIsRecorded() {
        val faults = java.util.concurrent.CopyOnWriteArraySet<String>()
        withFake(FakeRenderer(bareFaultOn = faults)) { fake ->
            RendererHarness(fake).use { h ->
                h.renderer.init()
                assertTrue(h.renderer.doPlay(Video(DQW), 0.0))
                faults.add("SetAVTransportURI")

                h.renderer.reset()

                assertTrue(h.notes("the speaker refused an empty transport URI").isNotEmpty(), h.events.toString())
            }
        }
    }
}
