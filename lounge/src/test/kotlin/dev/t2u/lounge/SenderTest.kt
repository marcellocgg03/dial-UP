package dev.t2u.lounge

// Tests for Sender (port of yt-cast-receiver 2.1.0 lib/app/Sender.js, MIT, Copyright (c) Patrick Kan), GPL-3.0-only.

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** T-02-29: sender payloads come off the network; a bad one is rejected with a typed exception. */
class SenderTest {
    private fun entry(block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}): JsonObject = buildJsonObject {
        put("id", "sender-1")
        put("name", "Pixel")
        put("theme", "cl")
        block()
    }

    @Test
    fun parsesAFullRemoteControlEntryAndToleratesAMalformedDeviceString() {
        val sender = Sender.parse(
            buildJsonObject {
                put("id", "sender-1")
                put("name", "Pixel")
                put("theme", "cl")
                put("capabilities", "que,atp")
                put("app", "youtube.m-desktop")
                put("device", "{bad json")
            },
        )
        assertEquals("sender-1", sender.id)
        assertEquals("Pixel", sender.name)
        assertEquals(Client.YT, sender.client)
        assertEquals(listOf("que", "atp"), sender.capabilities)
        assertEquals("youtube.m-desktop", sender.app)
        assertTrue(sender.supportsAutoplay())
        assertTrue(sender.supportsMute())
        assertEquals(JsonObject(emptyMap()), sender.device)
    }

    @Test
    fun aValidDeviceJsonStringBecomesAnObject() {
        val sender = Sender.parse(entry { put("device", """{"brand":"Google","model":"Pixel 9"}""") })
        assertEquals(JsonPrimitive("Google"), sender.device["brand"])
        assertEquals(JsonPrimitive("Pixel 9"), sender.device["model"])
    }

    @Test
    fun aDeviceThatIsNotAnObjectBecomesEmpty() {
        val sender = Sender.parse(entry { put("device", "5") })
        assertEquals(JsonObject(emptyMap()), sender.device)
    }

    @Test
    fun clientNameStandsInForName() {
        val sender = Sender.parse(
            buildJsonObject {
                put("id", "sender-2")
                put("clientName", "Living room")
                put("theme", "m")
            },
        )
        assertEquals("Living room", sender.name)
        assertEquals(Client.YTMUSIC, sender.client)
    }

    @Test
    fun userAndGaiaIdsAreKeptWhenPresent() {
        val sender = Sender.parse(
            entry {
                put("user", "Alice")
                put("userAvatarUri", "https://example.invalid/a.png")
                put("obfuscatedGaiaId", "g1")
            },
        )
        assertEquals(SenderUser("Alice", "https://example.invalid/a.png"), sender.user)
        assertEquals("g1", sender.obfuscatedGaiaId)
        assertNull(sender.ownerObfuscatedGaiaId)
    }

    @Test
    fun withoutCapabilitiesOrAppNothingIsSupported() {
        val sender = Sender.parse(entry())
        assertTrue(sender.capabilities.isEmpty())
        assertNull(sender.app)
        assertNull(sender.user)
        assertFalse(sender.supportsAutoplay())
        assertFalse(sender.supportsMute())
    }

    @Test
    fun mobileAppsDoNotSupportMute() {
        assertFalse(Sender.parse(entry { put("app", "youtube-android") }).supportsMute())
        assertTrue(Sender.parse(entry { put("app", "youtube-desktop") }).supportsMute())
    }

    @Test
    fun missingOrEmptyIdIsRejected() {
        assertFailsWith<SenderParseException> {
            Sender.parse(buildJsonObject { put("name", "Pixel"); put("theme", "cl") })
        }
        assertFailsWith<SenderParseException> {
            Sender.parse(entry { put("id", "") })
        }
    }

    @Test
    fun missingNameAndClientNameIsRejected() {
        assertFailsWith<SenderParseException> {
            Sender.parse(buildJsonObject { put("id", "sender-1"); put("theme", "cl") })
        }
    }

    @Test
    fun anUnknownOrMissingThemeIsRejected() {
        assertFailsWith<SenderParseException> { Sender.parse(entry { put("theme", "x") }) }
        assertFailsWith<SenderParseException> {
            Sender.parse(buildJsonObject { put("id", "sender-1"); put("name", "Pixel") })
        }
    }
}
