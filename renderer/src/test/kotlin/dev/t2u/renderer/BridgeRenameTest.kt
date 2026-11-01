// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for Tube2U[PnP] for Android.
package dev.t2u.renderer

// GUI rename (quick 261101-dcg, PD-4, PD-5): a rename shows on the card and as the DIAL friendly name at once, survives a bridge
// restart, resets to the described name, announces only where the access mode allows, and never changes how the renderer is matched.
//
// Every test is `= h.run<Unit> { ... }`: JUnit silently skips a test whose expression body returns a value.

import dev.t2u.dial.DialAccessMode
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BridgeRenameTest {
    private val h = BridgeHarness()

    @AfterEach
    fun tearDown() = h.close()

    private suspend fun awaitSpeakerUp(bridge: Bridge) {
        h.awaitUntil("one speaker listed") { bridge.speakers.value.size == 1 && bridge.castCount.value.total == 1 }
    }

    /** The friendlyName the DIAL device description of [bridge]'s receiver serves right now. */
    private fun friendlyName(bridge: Bridge): String {
        val reply = checkNotNull(h.dialSearchAt(bridge, h.loopback)) { "no DIAL search reply" }
        val location = checkNotNull(h.header(reply, "LOCATION"))
        val body = h.http.send(HttpRequest.newBuilder(URI(location)).GET().build(), HttpResponse.BodyHandlers.ofString()).body()
        return Regex("<friendlyName>(.*?)</friendlyName>").find(body)!!.groupValues[1]
    }

    @Test
    @DisplayName("a rename shows on the card and in the DIAL description at once")
    fun aRenameShowsOnTheCardAndInTheDescription() = h.run<Unit> {
        val names = RendererNames(h.dataDir).load()
        val bridge = h.newBridge(names = names)
        bridge.start()
        awaitSpeakerUp(bridge)
        h.awaitUntil("a DIAL search reply") { h.dialSearchAt(bridge, h.loopback) != null }

        assertTrue(bridge.rename(HARNESS_RENDERER_UDN, "Kitchen"))

        h.awaitUntil("the card renamed") { bridge.speakers.value.singleOrNull()?.name == "Kitchen" }
        assertEquals("Kitchen", friendlyName(bridge))
        assertTrue(File(h.dataDir, "names.json").readText().let { it.contains(HARNESS_RENDERER_UDN) && it.contains("Kitchen") })
        assertEquals(1, bridge.castCount.value.total, "the receiver was not rebuilt")
    }

    @Test
    @DisplayName("the name survives a bridge restart and a reset brings the described name back")
    fun theNameSurvivesARestartAndAResetBringsTheDescribedNameBack() = h.run<Unit> {
        val first = h.newBridge(names = RendererNames(h.dataDir).load())
        first.start()
        awaitSpeakerUp(first)
        val described = first.speakers.value.single().name
        assertTrue(first.rename(HARNESS_RENDERER_UDN, "Kitchen"))
        first.stop()

        val names = RendererNames(h.dataDir).load()
        val second = h.newBridge(names = names)
        second.start()
        awaitSpeakerUp(second)
        assertEquals("Kitchen", second.speakers.value.single().name)
        h.awaitUntil("a DIAL search reply") { h.dialSearchAt(second, h.loopback) != null }
        assertEquals("Kitchen", friendlyName(second))

        assertTrue(second.rename(HARNESS_RENDERER_UDN, null))

        h.awaitUntil("the described name back") { second.speakers.value.singleOrNull()?.name == described }
        assertEquals(described, friendlyName(second))
        assertFalse(File(h.dataDir, "names.json").readText().contains(HARNESS_RENDERER_UDN))
    }

    @Test
    @DisplayName("an unusable name changes nothing")
    fun anUnusableNameChangesNothing() = h.run<Unit> {
        val names = RendererNames(h.dataDir).load()
        val bridge = h.newBridge(names = names)
        bridge.start()
        awaitSpeakerUp(bridge)
        val before = bridge.speakers.value.single().name

        assertFalse(bridge.rename(HARNESS_RENDERER_UDN, ""))
        assertFalse(bridge.rename(HARNESS_RENDERER_UDN, "a".repeat(64)))
        assertFalse(bridge.rename(HARNESS_RENDERER_UDN, "Kitchen\nBath"))

        assertEquals(before, bridge.speakers.value.single().name)
        assertFalse(File(h.dataDir, "names.json").exists())
    }

    @Test
    @DisplayName("a renamed speaker is hidden and restored by its UDN")
    fun aRenamedSpeakerIsHiddenAndRestoredByItsUdn() = h.run<Unit> {
        val names = RendererNames(h.dataDir).load()
        val hidden = HiddenSpeakers(h.dataDir).load()
        val bridge = h.newBridge(hidden = hidden, names = names)
        bridge.start()
        awaitSpeakerUp(bridge)
        assertTrue(bridge.rename(HARNESS_RENDERER_UDN, "Kitchen"))
        h.awaitUntil("the card renamed") { bridge.speakers.value.singleOrNull()?.name == "Kitchen" }

        assertTrue(bridge.hide(HARNESS_RENDERER_UDN))

        h.awaitUntil("the list empty") { bridge.speakers.value.isEmpty() }
        val entry = hidden.entries.value.single()
        assertEquals(HARNESS_RENDERER_UDN, entry.udn)
        assertEquals("Kitchen", entry.name)
        assertTrue(File(h.dataDir, "hidden.json").readText().contains(HARNESS_RENDERER_UDN))

        assertTrue(bridge.unhide(HARNESS_RENDERER_UDN))

        h.awaitUntil("the speaker back, named Kitchen", timeoutMs = 15_000) { bridge.speakers.value.singleOrNull()?.name == "Kitchen" }
    }

    @Test
    @DisplayName("in LAN mode a rename announces the new name and in phone-only mode it sends no NOTIFY")
    fun lanAnnouncesAndPhoneOnlySendsNoNotify() = h.run<Unit> {
        val names = RendererNames(h.dataDir).load()
        h.accessMode = DialAccessMode.PHONE
        val bridge = h.newBridge(names = names)
        bridge.start()
        awaitSpeakerUp(bridge)
        h.awaitUntil("a DIAL search reply") { h.dialSearchAt(bridge, h.loopback) != null }

        assertTrue(bridge.rename(HARNESS_RENDERER_UDN, "Phone name"))
        h.awaitUntil("the card renamed") { bridge.speakers.value.singleOrNull()?.name == "Phone name" }
        assertFalse(
            h.events.any { it.first == "ssdp-tx" && it.second["device"] == "Phone name" },
            "no NOTIFY in phone-only mode: ${h.events.filter { it.first == "ssdp-tx" }.takeLast(5)}",
        )

        h.accessMode = DialAccessMode.LAN
        assertTrue(bridge.rename(HARNESS_RENDERER_UDN, "Lan name"))
        h.awaitUntil("an ssdp:alive for the new name") {
            h.events.any { it.first == "ssdp-tx" && it.second["device"] == "Lan name" && it.second["nts"] == "ssdp:alive" }
        }
    }
}
