// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]). The group listing follows what Metrolist
// (github.com/MetrolistGroup/Metrolist, GPL-3.0) does for Sonos casting.
package dev.t2u.upnp

import org.jsoup.Jsoup
import org.jsoup.parser.Parser

/** One speaker of a Sonos group. [udn] is its MediaRenderer UDN in the form [udnFromUsn] gives; [visible] is false for a bonded one. */
data class SonosMember(val udn: String, val zoneName: String, val visible: Boolean)

/** A Sonos group: the [coordinator]'s MediaRenderer UDN and its members in the order the speaker lists them. */
data class SonosGroup(val coordinator: String, val members: List<SonosMember>)

private const val TOPOLOGY_SERVICE = "urn:schemas-upnp-org:service:ZoneGroupTopology:1"
private const val TOPOLOGY_CONTROL_PATH = "/ZoneGroupTopology/Control"

/** Largest group list read from one speaker. */
private const val MAX_GROUPS = 128

/** True for a Sonos MediaRenderer UDN (`rincon_..._mr`), the only kind that is asked about groups. */
fun isSonosRendererUdn(udn: String): Boolean = udn.startsWith("rincon_") && udn.endsWith("_mr")

/** The MediaRenderer UDN of the player the topology names [uuid] (`RINCON_...`). */
private fun rendererUdnOf(uuid: String): String = uuid.trim().lowercase() + "_mr"

/** The groups in a `ZoneGroupState` document; a group or member without its id is left out, and junk gives an empty list. */
fun parseZoneGroupState(xml: String): List<SonosGroup> {
    val doc = Jsoup.parse(xml, "", Parser.xmlParser())
    return doc.getElementsByTag("ZoneGroup").take(MAX_GROUPS).mapNotNull { group ->
        val coordinator = group.attr("Coordinator").ifBlank { return@mapNotNull null }
        // direct children only: a home theatre's satellites are nested inside their player
        val members = group.children().filter { it.tagName() == "ZoneGroupMember" }.mapNotNull { member ->
            val uuid = member.attr("UUID")
            if (uuid.isBlank()) {
                null
            } else {
                SonosMember(rendererUdnOf(uuid), cleanSpeakerText(member.attr("ZoneName")), member.attr("Invisible") != "1")
            }
        }
        SonosGroup(rendererUdnOf(coordinator), members)
    }
}

/**
 * Asks the Sonos speaker whose device description is at [location] for the household's groups. The control URL is on the
 * description's host and port, so [SoapClient]'s speaker rule applies. Throws [UpnpError] when the speaker does not answer.
 */
suspend fun SoapClient.sonosGroups(location: String, timeoutMs: Long = 3000): List<SonosGroup> {
    val control = try {
        java.net.URI(location).resolve(TOPOLOGY_CONTROL_PATH).toString()
    } catch (_: Exception) {
        throw UpnpError("GetZoneGroupState: bad location", "ENETWORK")
    }
    val service = UpnpService(TOPOLOGY_SERVICE, control, "", "")
    val reply = callAction(service, "GetZoneGroupState", timeoutMs = timeoutMs)
    return parseZoneGroupState(reply["ZoneGroupState"].orEmpty())
}
