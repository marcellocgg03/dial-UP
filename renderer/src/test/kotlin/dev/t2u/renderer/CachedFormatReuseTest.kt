package dev.t2u.renderer

// Auto mode follows the streaming chain regardless of cached formats.
// Not a port of a Node title: the Kotlin cache keeps every built format side by side. GPL-3.0-only.

import dev.t2u.lounge.Video
import dev.t2u.upnp.FakeRenderer
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val ID = "dQw4w9WgXcQ"

class CachedFormatReuseTest {
    private fun cfg(format: String): (File) -> Config = { Config(dataDir = it, pollIntervalMs = 50, startTimeoutMs = 200, outputFormat = format) }

    private fun run(format: String, cached: List<String>, fake: FakeRenderer = FakeRenderer()): String {
        fake.start()
        try {
            return runBlocking {
                RendererHarness(fake, config = cfg(format)).use { h ->
                    h.cachedFormats = cached
                    h.renderer.init()
                    assertTrue(h.renderer.doPlay(Video(ID), 0.0))
                    h.registered.first().format
                }
            }
        } finally {
            fake.close()
        }
    }

    @Test
    @DisplayName("auto mode ignores a cached mp4")
    fun autoIgnoresCachedMp4() = assertEquals("direct", run("auto", listOf("mp4")))

    @Test
    @DisplayName("auto mode ignores cached formats even when several are available")
    fun autoIgnoresCachedFormats() = assertEquals("direct", run("auto", listOf("mp4", "mp3")))

    @Test
    @DisplayName("auto mode ignores a cached format the speaker does not accept")
    fun autoIgnoresACachedFormatTheSpeakerRefuses() =
        assertEquals("mp3", run("auto", listOf("mp4"), FakeRenderer(sink = "http-get:*:audio/mpeg:*")))

    @Test
    @DisplayName("auto mode with nothing cached follows the priority list")
    fun autoWithNothingCachedFollowsThePriorityList() = assertEquals("direct", run("auto", emptyList()))

    @Test
    @DisplayName("a forced format wins over a cached one")
    fun forcedFormatWins() = assertEquals("mp3", run("mp3", listOf("mp4")))
}
