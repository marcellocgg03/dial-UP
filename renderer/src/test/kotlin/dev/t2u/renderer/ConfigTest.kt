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

    private fun name(friendly: String, template: String = cfg.nameTemplate, manufacturer: String = "Sonos", model: String = "Era 100") =
        receiverName(template, friendly, manufacturer, model)

    @Test
    fun defaultNameHasTheSpeakerEmoji() {
        assertEquals("🔊 Living Room Speaker", name("Living Room Speaker"))
    }

    @Test
    fun aPrefixBuildsTheTemplateAndABlankPrefixLeavesOnlyTheName() {
        assertEquals("{name}", nameTemplateFor(""))
        assertEquals("{name}", nameTemplateFor("   "))
        assertEquals("🎵 {name}", nameTemplateFor("🎵"))
        assertEquals("DialUP {name}", nameTemplateFor("  DialUP "))
        assertEquals("Kitchen", name("Kitchen", template = nameTemplateFor("")))
        assertEquals("🎵 Kitchen", name("Kitchen", template = nameTemplateFor("🎵")))
    }

    @Test
    fun aPrefixIsLiteralTextNotAPlaceholder() {
        for (prefix in listOf("{name}", "{model}", "x{manufacturer}", "{name} {model}")) {
            val shown = name("Kitchen", template = nameTemplateFor(prefix))
            assertTrue(shown.endsWith(" Kitchen"), "the real name closes '$shown'")
            assertTrue("Sonos" !in shown && "Era 100" !in shown, "'$prefix' was not substituted: $shown")
            assertEquals(1, Regex("Kitchen").findAll(shown).count(), shown)
        }
        assertEquals("{x} Kitchen", name("Kitchen", template = nameTemplateFor("{x}")), "other braces stay")
    }

    @Test
    fun aSpeakerNameIsLiteralAndCleanedBeforeItIsUsed() {
        assertEquals("🔊 {model}", name("{model}"), "a speaker that calls itself {model} is not given the model")
        assertEquals("🔊 Kitchen Bath", name("Kitchen\u0000\n\u0007Bath").replace("  ", " "))
        assertEquals("🔊 Unknown renderer", name("\u0001\u0002"))
        assertEquals(63, name("x".repeat(200), template = "{name}").length)
    }

    @Test
    fun templatePlaceholdersAreReplacedAndTrimmed() {
        assertEquals(
            "Lamp - Sonos SYMFONISK",
            receiverName("  {name} - {manufacturer} {model}  ", "Lamp", "Sonos", "SYMFONISK"),
        )
    }

    @Test
    fun eachPlaceholderIsReplacedOnceLikeJsStringReplace() {
        assertEquals("Lamp {name}", receiverName("{name} {name}", "Lamp", "Sonos", "SYMFONISK"))
    }

    @Test
    fun replacementIsLiteral() {
        // JS String.replace treats "$&" in a string replacement specially; a friendly name is data, not a pattern.
        assertEquals("🔊 Cost \$& Co", name("Cost \$& Co"))
    }

    @Test
    fun unknownRendererFallbackNeverLeavesAnEmptyName() {
        assertEquals("🔊 Unknown renderer", name("Unknown renderer"))
    }

    @Test
    fun accentedAndEmojiCharactersAreKept() {
        assertEquals("🔊 Küche 🎵", name("Küche 🎵"))
    }

    @Test
    fun constantsAreTheDocumentedOnes() {
        assertEquals("🔊 {name}", DEFAULT_NAME_TEMPLATE)
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
        assertEquals("auto", cfg.outputFormat)
        assertTrue(cfg.autoplayOnConnect)
        assertEquals(listOf("QuickShareUPnP"), cfg.excludeServers)
        assertEquals(null, cfg.advertiseIp)
        cfg.validate()
    }

    @Test
    fun outputFormatWhitelistHasTheSupportedFormats() {
        for (f in listOf("auto", "streaming", "direct", "mp4", "adts", "mp3")) {
            Config(dataDir = File("data"), outputFormat = f).validate()
        }
        for (f in listOf("wav", "")) {
            val e = assertFailsWith<IllegalArgumentException>(f) { Config(dataDir = File("data"), outputFormat = f).validate() }
            assertTrue("outputFormat" in e.message.orEmpty())
        }
        assertEquals(setOf("auto", "streaming", "direct", "mp4", "adts", "mp3"), OUTPUT_FORMATS)
    }
}
