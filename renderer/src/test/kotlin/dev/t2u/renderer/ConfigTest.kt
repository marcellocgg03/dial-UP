package dev.t2u.renderer

// Tests for the Phase 2 Config subset and receiverName (tube2upnp config.js + renderer.js name template), GPL-3.0-only.

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** CAST-07: receivers are named "🔊 {name}", plus the D-04 coexistence suffix while the Docker server runs. */
class ConfigTest {
    private val cfg = Config(dataDir = File("data"))

    private fun name(friendly: String, template: String = cfg.nameTemplate, suffix: String = cfg.nameSuffix, manufacturer: String = "Sonos", model: String = "Era 100") =
        receiverName(template, suffix, friendly, manufacturer, model)

    @Test
    fun defaultNameHasSpeakerEmojiAndAppSuffix() {
        assertEquals("🔊 Living Room Speaker (app)", name("Living Room Speaker"))
    }

    @Test
    fun emptySuffixGivesTheNodeValue() {
        assertEquals("🔊 Living Room Speaker", name("Living Room Speaker", suffix = ""))
    }

    @Test
    fun templatePlaceholdersAreReplacedAndTrimmed() {
        assertEquals(
            "Lamp - Sonos SYMFONISK",
            receiverName("  {name} - {manufacturer} {model}  ", "", "Lamp", "Sonos", "SYMFONISK"),
        )
    }

    @Test
    fun eachPlaceholderIsReplacedOnceLikeJsStringReplace() {
        assertEquals("Lamp {name}", receiverName("{name} {name}", "", "Lamp", "Sonos", "SYMFONISK"))
    }

    @Test
    fun replacementIsLiteral() {
        // JS String.replace treats "$&" in a string replacement specially; a friendly name is data, not a pattern.
        assertEquals("🔊 Cost \$& Co (app)", name("Cost \$& Co"))
    }

    @Test
    fun unknownRendererFallbackNeverLeavesAnEmptyName() {
        assertEquals("🔊 Unknown renderer (app)", name("Unknown renderer"))
    }

    @Test
    fun accentedAndEmojiCharactersAreKept() {
        assertEquals("🔊 Küche 🎵 (app)", name("Küche 🎵"))
    }

    @Test
    fun constantsAreTheDocumentedOnes() {
        assertEquals("🔊 {name}", DEFAULT_NAME_TEMPLATE)
        assertEquals(" (app)", DOCKER_COEXISTENCE_SUFFIX)
    }

    @Test
    fun defaultsMirrorConfigJs() {
        assertEquals(3000, cfg.receiverBasePort)
        assertEquals(8000, cfg.mediaPort)
        assertEquals(300, cfg.discoveryIntervalS)
        assertEquals(60, cfg.pruneIntervalS)
        assertEquals(1800, cfg.defaultMaxAge)
        assertEquals(2000L, cfg.pollIntervalMs)
        assertEquals(12000L, cfg.startTimeoutMs)
        assertEquals("adts", cfg.outputFormat)
        assertTrue(cfg.autoplayOnConnect)
        assertEquals(listOf("QuickShareUPnP"), cfg.excludeServers)
        assertEquals(null, cfg.advertiseIp)
        cfg.validate()
    }

    @Test
    fun onlyThePipeFormatsAreValidInPhase2() {
        Config(dataDir = File("data"), outputFormat = "mp3").validate()
        Config(dataDir = File("data"), outputFormat = "adts").validate()
        for (f in listOf("mp4", "flac", "direct", "auto", "")) {
            assertFailsWith<IllegalArgumentException>(f) { Config(dataDir = File("data"), outputFormat = f).validate() }
        }
    }
}
