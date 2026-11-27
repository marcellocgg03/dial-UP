package dev.t2u.upnp

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SonosTopologyTest {
    /** The shape a real household answered: a group of two, and a home theatre with a nested satellite. */
    private val state =
        """<ZoneGroupState><ZoneGroups>""" +
            """<ZoneGroup Coordinator="RINCON_BBB01400" ID="RINCON_BBB01400:1">""" +
            """<ZoneGroupMember UUID="RINCON_AAA01400" ZoneName="Deck"/>""" +
            """<ZoneGroupMember UUID="RINCON_BBB01400" ZoneName="Lamp"/></ZoneGroup>""" +
            """<ZoneGroup Coordinator="RINCON_CCC01400" ID="RINCON_CCC01400:2">""" +
            """<ZoneGroupMember UUID="RINCON_CCC01400" ZoneName="Family Room">""" +
            """<Satellite UUID="RINCON_DDD01400" ZoneName="Sub" Invisible="1"/></ZoneGroupMember>""" +
            """<ZoneGroupMember UUID="RINCON_EEE01400" ZoneName="Family Room" Invisible="1"/></ZoneGroup>""" +
            """</ZoneGroups></ZoneGroupState>"""

    @Test
    fun groupsAndTheirDirectMembersAreRead() {
        val groups = parseZoneGroupState(state)
        assertEquals(listOf("rincon_bbb01400_mr", "rincon_ccc01400_mr"), groups.map { it.coordinator })
        assertEquals(
            listOf(SonosMember("rincon_aaa01400_mr", "Deck", true), SonosMember("rincon_bbb01400_mr", "Lamp", true)),
            groups[0].members,
        )
        assertEquals(listOf("rincon_ccc01400_mr" to true, "rincon_eee01400_mr" to false), groups[1].members.map { it.udn to it.visible })
    }

    @Test
    fun junkGivesNoGroups() {
        assertTrue(parseZoneGroupState("").isEmpty())
        assertTrue(parseZoneGroupState("<html>Internal Error</html>").isEmpty())
        assertTrue(parseZoneGroupState("<ZoneGroup><ZoneGroupMember UUID=\"x\"/></ZoneGroup>").isEmpty())
    }

    @Test
    fun onlySonosRenderersAreAsked() {
        assertTrue(isSonosRendererUdn("rincon_aaa01400_mr"))
        assertTrue(!isSonosRendererUdn("fake-renderer"))
        assertTrue(!isSonosRendererUdn("rincon_aaa01400"))
    }

    @Test
    fun theGroupsAreReadFromTheSpeakerOverSoap() = runBlocking<Unit> {
        FakeRenderer().use { fake ->
            fake.start()
            val soap = SoapClient(lanHttpClient(), allowLoopback = true)
            assertFailsWith<UpnpError> { soap.sonosGroups(fake.location) }
            fake.state.zoneGroupState = state
            assertEquals(parseZoneGroupState(state), soap.sonosGroups(fake.location))
        }
    }
}
