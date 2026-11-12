// DIAL device and application description documents ported from the peer-dial xml/ templates
// (@patrickkfkan/peer-dial, a fork of the original). Copyright (c) 2015 Louay Bassbouss, Fraunhofer FOKUS, All rights reserved.
// peer-dial is licensed under the GNU Lesser General Public License, version 3.0 or later; fork by Patrick Kan;
// ported to Kotlin for Tube2U[PnP] and conveyed under GPL-3.0-only.
package dev.t2u.dial

import dev.t2u.upnp.stripInvalidXmlChars

/**
 * XML text escaping for every value inserted into the DIAL documents (EJS `<%= %>` escapes the same set). Characters that
 * XML 1.0 does not allow at all (controls other than tab, LF and CR, unpaired surrogates) are dropped: one of them would make
 * the whole document unparseable and the speaker would vanish from the cast menu.
 */
internal fun xmlEscape(s: String): String = buildString(s.length + 8) {
    for (c in stripInvalidXmlChars(s)) {
        when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&#39;")
            else -> append(c)
        }
    }
}

object DialXml {
    /** reference/libs/peer-dial/xml/device-desc.xml with its EJS placeholders filled in. */
    fun deviceDesc(urlBase: String, friendlyName: String, manufacturer: String, modelName: String, uuid: String): String =
        """<?xml version="1.0"?>
<root xmlns="urn:schemas-upnp-org:device-1-0">
  <specVersion>
    <major>1</major>
    <minor>0</minor>
  </specVersion>
  <URLBase>${xmlEscape(urlBase)}</URLBase>
  <device>
    <deviceType>urn:dial-multiscreen-org:device:dial:1</deviceType>
    <friendlyName>${xmlEscape(friendlyName)}</friendlyName>
    <manufacturer>${xmlEscape(manufacturer)}</manufacturer>
    <modelName>${xmlEscape(modelName)}</modelName>
    <UDN>uuid:${xmlEscape(uuid)}</UDN>
    <iconList>
      <icon>
        <mimetype>image/png</mimetype>
        <width>144</width>
        <height>144</height>
        <depth>32</depth>
        <url>/img/icon.png</url>
      </icon>
    </iconList>
    <serviceList>
      <service>
        <serviceType>urn:dial-multiscreen-org:service:dial:1</serviceType>
        <serviceId>urn:dial-multiscreen-org:serviceId:dial</serviceId>
        <controlURL>/ssdp/notfound</controlURL>
        <eventSubURL>/ssdp/notfound</eventSubURL>
        <SCPDURL>/ssdp/notfound</SCPDURL>
      </service>
    </serviceList>
  </device>
</root>
"""

    /**
     * reference/libs/peer-dial/xml/app-desc.xml with an empty namespace list. The two spaces before
     * dialVer are what the empty namespace list leaves in the EJS output; the link element appears only
     * when [runHref] is set.
     */
    fun appDesc(name: String, state: String, allowStop: Boolean = false, runHref: String?): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<service xmlns=\"urn:dial-multiscreen-org:schemas:dial\"  dialVer=\"1.7\">\n")
        append("  <name>").append(xmlEscape(name)).append("</name>\n")
        append("  <options allowStop=\"").append(allowStop).append("\"/>\n")
        append("  <state>").append(xmlEscape(state)).append("</state>\n")
        if (runHref != null) append("  <link rel=\"run\" href=\"").append(xmlEscape(runHref)).append("\" />\n")
        append("</service>\n")
    }
}
