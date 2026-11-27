// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.t2u.renderer

// Sonos groups through a running bridge: the coordinator takes the group's name, a member leaves the cast menu and comes back.
//
// Every test is `= h.run<Unit> { ... }`: JUnit silently skips a test whose expression body returns a value.

import dev.t2u.lounge.Volume
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val SELF = "RINCON_AAA01400"
private const val OTHER = "RINCON_BBB01400"

class BridgeSonosGroupTest {
    private val h = BridgeHarness(rendererUdn = "${SELF}_MR")
    private val udn = "${SELF.lowercase()}_mr"
    private val otherUdn = "${OTHER.lowercase()}_mr"

    @AfterEach
    fun tearDown() = h.close()

    private fun member(uuid: String, room: String) = """<ZoneGroupMember UUID="$uuid" ZoneName="$room"/>"""

    private fun state(coordinator: String, vararg members: String) =
        """<ZoneGroupState><ZoneGroups><ZoneGroup Coordinator="$coordinator">${members.joinToString("")}</ZoneGroup></ZoneGroups></ZoneGroupState>"""

    private val alone = state(SELF, member(SELF, "Lamp"))

    @Test
    @DisplayName("a coordinator takes the group's name and gets its own back after the split")
    fun aCoordinatorTakesTheGroupsName() = h.run<Unit> {
        h.fakeRenderer.state.zoneGroupState = alone
        val bridge = h.newBridge(namePrefix = { "🔊" }, sonosGroupPollMs = 100)
        bridge.start()
        h.awaitUntil("the speaker listed") { bridge.speakers.value.size == 1 }
        val own = bridge.speakers.value.single().name

        h.fakeRenderer.state.zoneGroupState = state(SELF, member(OTHER, "Deck"), member(SELF, "Lamp"))
        h.awaitUntil("the group name") { bridge.speakers.value.singleOrNull()?.name == "🔊 Deck + Lamp" }
        assertTrue(bridge.speakers.value.single().grouped)

        // the volume of a group is the group's: the speaker's own stays as it was
        val renderer = checkNotNull(bridge.renderer(udn))
        val ownVolume = h.fakeRenderer.state.volume
        assertTrue(renderer.doSetVolume(Volume(42, true)))
        assertEquals(42, h.fakeRenderer.state.groupVolume)
        assertTrue(h.fakeRenderer.state.groupMute)
        assertEquals(ownVolume, h.fakeRenderer.state.volume)
        assertEquals(Volume(42, true), renderer.doGetVolume())
        assertEquals(1, h.speakerCount("SnapshotGroupVolume"))

        h.fakeRenderer.state.zoneGroupState = alone
        h.awaitUntil("its own name back") { bridge.speakers.value.singleOrNull()?.name == own }
        assertFalse(bridge.speakers.value.single().grouped)
        assertTrue(renderer.doSetVolume(Volume(17, false)))
        assertEquals(17, h.fakeRenderer.state.volume)
        assertEquals(42, h.fakeRenderer.state.groupVolume)
        assertEquals(1, bridge.castCount.value.total)
    }

    @Test
    @DisplayName("a member leaves the cast menu and comes back after the split")
    fun aMemberLeavesTheCastMenuAndComesBack() = h.run<Unit> {
        // the coordinator is a speaker the author hid, which keeps its group out of the menu without a second fake speaker
        val hidden = HiddenSpeakers(h.dataDir).load()
        hidden.add(HiddenSpeaker(otherUdn, "Deck", "127.0.0.1"))
        h.fakeRenderer.state.zoneGroupState = alone
        val bridge = h.newBridge(hidden = hidden, sonosGroupPollMs = 100)
        bridge.start()
        h.awaitUntil("the speaker listed") { bridge.speakers.value.size == 1 }

        h.fakeRenderer.state.zoneGroupState = state(OTHER, member(OTHER, "Deck"), member(SELF, "Lamp"))
        h.awaitUntil("the member gone") { bridge.speakers.value.isEmpty() }
        // the card goes before the removal is logged: the speaker is destroyed in between
        h.awaitUntil("the grouped event") { h.events.any { it.second["event"] == "grouped" && it.second["udn"] == udn } }

        h.fakeRenderer.state.zoneGroupState = alone
        h.awaitUntil("the speaker back") { bridge.speakers.value.singleOrNull()?.udn == udn }
    }
}
