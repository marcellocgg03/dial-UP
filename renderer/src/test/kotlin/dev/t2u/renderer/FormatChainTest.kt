package dev.t2u.renderer

// Port of the renderer.test.js format-chain titles plus the Kotlin additions of plan 03-06 (PLAY-04, DISC-04),
// driven against the upnp FakeRenderer. GPL-3.0-only.
//
// Adaptations from the Node tests (plan 03-12 copies these into PARITY.md "Adaptations"):
//  * Node's setup passes outputFormat "auto"; each test here sets it explicitly (the app default is auto too).
//  * The media server is a recording stub with a prepare hook (Node's fake server had no prepare step).

import dev.t2u.lounge.Video
import dev.t2u.upnp.FakeRenderer
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val DQW = "dQw4w9WgXcQ"

class FormatChainTest {
    private fun <T> withFake(fake: FakeRenderer = FakeRenderer(), block: suspend (FakeRenderer) -> T): T {
        fake.start()
        try {
            return runBlocking { block(fake) }
        } finally {
            fake.close()
        }
    }

    private fun auto(dir: File) = Config(dataDir = dir, pollIntervalMs = 50, startTimeoutMs = 200, nameSuffix = "", outputFormat = "auto")

    @Test
    @DisplayName("picks the first format the renderer advertises (lossless ADTS before MP3)")
    fun picksTheFirstFormatTheRendererAdvertises() = withFake(FakeRenderer(sink = "http-get:*:audio/vnd.dlna.adts:*")) { fake ->
        RendererHarness(fake, config = ::auto).use { h ->
            h.renderer.init()
            assertTrue(h.renderer.doPlay(Video(DQW), 0.0))
            assertEquals("adts", h.registered.first().format)
        }
    }

    @Test
    @DisplayName("prefers lossless FLAC over MP3 when the renderer lists FLAC but no AAC")
    fun prefersFlacOverMp3() = withFake(FakeRenderer(sink = "http-get:*:audio/mpeg:*,http-get:*:audio/x-flac:*")) { fake ->
        RendererHarness(fake, config = ::auto).use { h ->
            h.renderer.init()
            assertTrue(h.renderer.doPlay(Video(DQW), 0.0))
            assertEquals("flac", h.registered.first().format)
            val set = fake.calls.first { it.action == "SetAVTransportURI" }
            assertTrue(set.body.contains("audio/flac:DLNA.ORG_OP=01"), set.body)
        }
    }

    @Test
    @DisplayName("uses MP3 only when the renderer advertises nothing better")
    fun usesMp3OnlyWhenNothingBetter() = withFake(FakeRenderer(sink = "http-get:*:audio/mpeg:*")) { fake ->
        RendererHarness(fake, config = ::auto).use { h ->
            h.renderer.init()
            assertTrue(h.renderer.doPlay(Video(DQW), 0.0))
            assertEquals("mp3", h.registered.first().format)
            val set = fake.calls.first { it.action == "SetAVTransportURI" }
            assertTrue(set.body.contains("audio/mpeg"), set.body)
        }
    }

    @Test
    @DisplayName("direct comes first when the speaker takes the source container")
    fun directComesFirst() = withFake { fake ->
        RendererHarness(fake, config = ::auto).use { h ->
            h.renderer.init()
            assertTrue(h.renderer.doPlay(Video(DQW), 0.0))
            val entry = h.registered.first()
            assertEquals("direct", entry.format)
            assertEquals("m4a", entry.ext)
            assertEquals("audio/mp4", entry.sourceMime)
            val set = fake.calls.first { it.action == "SetAVTransportURI" }
            assertTrue(Regex("/stream/\\d+\\.m4a").containsMatchIn(set.body), set.body)
            assertTrue(set.body.contains("audio/mp4:DLNA.ORG_OP=01;DLNA.ORG_CI=0"), set.body)
        }
    }

    @Test
    @DisplayName("a speaker that lists no sink protocols is tried with direct first")
    fun emptySinkTriesDirectFirst() = withFake(FakeRenderer(sink = "")) { fake ->
        RendererHarness(fake, config = ::auto).use { h ->
            h.renderer.init()
            assertTrue(h.renderer.doPlay(Video(DQW), 0.0))
            assertEquals("direct", h.registered.first().format)
        }
    }

    @Test
    @DisplayName("media.prepare runs once per load, before registerVideo")
    fun prepareRunsBeforeRegister() = withFake { fake ->
        RendererHarness(fake, config = ::auto).use { h ->
            h.renderer.init()
            assertTrue(h.renderer.doPlay(Video(DQW), 0.0))
            assertEquals(1, h.prepared.size)
            assertEquals(listOf("prepare:direct", "register:direct"), h.order.toList())
        }
    }
}
