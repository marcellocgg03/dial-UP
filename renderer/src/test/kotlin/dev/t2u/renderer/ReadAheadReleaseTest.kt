package dev.t2u.renderer

// The renderer tells the media server when a stream is no longer needed, so the direct proxy's read-ahead window for it
// is dropped (plan 03-20, threat T-03-51), GPL-3.0-only. No Node counterpart (PARITY.md adaptation 28). Every test is a
// block-bodied function: JUnit silently skips a test whose expression body returns a value.

import dev.t2u.lounge.Video
import dev.t2u.upnp.FakeRenderer
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal class ReadAheadReleaseTest {
    private fun config(dir: File) = Config(dataDir = dir, outputFormat = "direct", pollIntervalMs = 50, startTimeoutMs = 5000, nameSuffix = "")

    private fun <T> rig(block: suspend RendererHarness.() -> T): T {
        val fake = FakeRenderer()
        fake.start()
        val h = RendererHarness(fake, config = ::config)
        try {
            return runBlocking {
                h.renderer.init()
                h.block()
            }
        } finally {
            h.close()
            fake.close()
        }
    }

    @Test
    @DisplayName("a new track releases the stream it replaces and a stop releases the current one")
    fun aNewTrackAndAStopReleaseTheirStreams() {
        rig {
            assertTrue(renderer.play(Video("AAAAAAAAAAA"), 0.0))
            assertTrue(released.isEmpty(), "nothing is released while the first stream plays: $released")
            val first = registered.size

            assertTrue(renderer.play(Video("BBBBBBBBBBB"), 0.0))
            assertEquals(1, released.size, "the first stream was released when the second replaced it: $released")
            assertTrue(released[0].endsWith("${first.toString().padStart(32, '0')}.m4a"), "the first stream's path: ${released[0]}")

            assertTrue(renderer.stop())
            assertEquals(2, released.size, "the stop released the second stream: $released")
        }
    }

    @Test
    @DisplayName("clearing the speaker releases the stream that was loaded")
    fun clearingTheSpeakerReleasesTheStream() {
        rig {
            assertTrue(renderer.play(Video("AAAAAAAAAAA"), 0.0))
            renderer.clearSpeaker()
            assertEquals(1, released.size, "released by the clear: $released")
        }
    }
}
