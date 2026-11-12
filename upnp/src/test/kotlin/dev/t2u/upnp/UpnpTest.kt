package dev.t2u.upnp

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Tests for Upnp.kt, a port of tube2upnp src/upnp.js. The three @DisplayName titles are the Node titles (PAR-02). */
class UpnpTest {
    private fun service(id: String, name: String) =
        "<service><serviceType>urn:schemas-upnp-org:service:$name:1</serviceType><serviceId>$id</serviceId>" +
            "<SCPDURL>/scpd/$name.xml</SCPDURL><controlURL>/ctl/$name</controlURL><eventSubURL>/evt/$name</eventSubURL></service>"

    private fun device(type: String, name: String, inner: String = "", services: String = "") =
        "<device><deviceType>$type</deviceType><friendlyName>$name</friendlyName><UDN>uuid:$name</UDN>" +
            (if (services.isNotEmpty()) "<serviceList>$services</serviceList>" else "") +
            (if (inner.isNotEmpty()) "<deviceList>$inner</deviceList>" else "") +
            "</device>"

    private fun root(body: String, urlBase: String? = null) =
        """<?xml version="1.0"?><root xmlns="urn:schemas-upnp-org:device-1-0">""" +
            (urlBase?.let { "<URLBase>$it</URLBase>" } ?: "") + body + "</root>"

    private val mr = "urn:schemas-upnp-org:device:MediaRenderer:1"
    private val zp = "urn:schemas-upnp-org:device:ZonePlayer:1"
    private val avt = service("urn:upnp-org:serviceId:AVTransport", "AVTransport")

    @Test
    @DisplayName("description parser finds embedded MediaRenderer and normalises serviceIds")
    fun embeddedRenderer() = runBlocking {
        FakeRenderer().use { fake ->
            fake.start()
            val d = fetchDescription(lanHttpClient(), fake.location)
            assertEquals("Living Room Speaker", d.friendlyName)
            val avTransport = assertNotNull(d.services["urn:upnp-org:serviceId:AVTransport"])
            assertTrue(Regex("""^http://127\.0\.0\.1:\d+/ctl/AVTransport$""").matches(avTransport.controlURL), avTransport.controlURL)
            assertEquals("urn:schemas-upnp-org:service:AVTransport:1", avTransport.serviceType)
        }
    }

    @Test
    @DisplayName("DIDL escapes metadata")
    fun didlEscapes() {
        val x = buildDidl(
            DidlItem(
                url = "http://h/s?a=1&b=2",
                protocolInfo = "http-get:*:audio/mp4:*",
                title = "Tom & Jerry <live>",
                artist = "A\"B",
                duration = 125.0,
            ),
        )
        assertTrue(Regex("Tom &amp; Jerry &lt;live&gt;").containsMatchIn(x))
        assertTrue(Regex("a=1&amp;b=2").containsMatchIn(x))
        assertTrue(Regex("""duration="0:02:05.000"""").containsMatchIn(x))
        assertFalse(x.contains("<live>"))
    }

    @Test
    @DisplayName("sink protocol matching")
    fun sinkMatching() {
        fun p(cf: String) = SinkProtocol("http-get", "*", cf, "*")
        assertEquals(false, sinkAccepts(listOf(p("audio/mpeg")), "audio/mp4"))
        assertEquals(true, sinkAccepts(listOf(p("audio/x-m4a")), "audio/mp4"))
        assertEquals(true, sinkAccepts(listOf(p("*")), "audio/webm"))
        assertNull(sinkAccepts(null, "audio/mp4"))
        assertNull(sinkAccepts(emptyList(), "audio/mp4"))
    }

    @Test
    fun `sinkAccepts honours type wildcard and ignores non http-get protocols`() {
        assertEquals(true, sinkAccepts(listOf(SinkProtocol("http-get", "*", "AUDIO/*", "*")), "audio/flac"))
        assertEquals(false, sinkAccepts(listOf(SinkProtocol("rtsp-rtp-udp", "*", "audio/mp4", "*")), "audio/mp4"))
        assertContains(MIME_ALIASES.getValue("audio/aac"), "audio/vnd.dlna.adts")
    }

    @Test
    fun `a root device that is a MediaRenderer is used before an embedded one`() {
        val xml = root(device(mr, "RootMR", inner = device(mr, "EmbeddedMR", services = avt), services = avt))
        assertEquals("RootMR", parseDescription(xml, "http://10.0.0.2/d.xml").friendlyName)
    }

    @Test
    fun `two embedded MediaRenderers give the first in breadth-first order`() {
        val sameLevel = root(device(zp, "Zone", inner = device(mr, "First", services = avt) + device(mr, "Second", services = avt)))
        assertEquals("First", parseDescription(sameLevel, "http://10.0.0.2/d.xml").friendlyName)

        val deeperFirst = root(
            device(zp, "Zone", inner = device(zp, "Inner", inner = device(mr, "Deep", services = avt)) + device(mr, "Shallow", services = avt)),
        )
        assertEquals("Shallow", parseDescription(deeperFirst, "http://10.0.0.2/d.xml").friendlyName)
    }

    @Test
    fun `no MediaRenderer falls back to the root device`() {
        val xml = root(device(zp, "OnlyZone", services = avt))
        assertEquals("OnlyZone", parseDescription(xml, "http://10.0.0.2/d.xml").friendlyName)
    }

    @Test
    fun `no serviceList gives an empty service map`() {
        val d = parseDescription(root(device(mr, "NoServices")), "http://10.0.0.2/d.xml")
        assertTrue(d.services.isEmpty())
    }

    @Test
    fun `no device element throws`() {
        val e = assertFailsWith<IllegalArgumentException> { parseDescription(root(""), "http://10.0.0.2/d.xml") }
        assertEquals("No <device> element in description", e.message)
    }

    @Test
    fun `URLBase is honoured and urls resolve against it`() {
        val xml = root(device(mr, "R", services = avt), urlBase = "http://10.0.0.5:1400/")
        val s = parseDescription(xml, "http://10.0.0.5:1400/some/desc.xml").services.getValue("urn:upnp-org:serviceId:AVTransport")
        assertEquals("http://10.0.0.5:1400/ctl/AVTransport", s.controlURL)
        assertEquals("http://10.0.0.5:1400/scpd/AVTransport.xml", s.scpdURL)
    }

    @Test
    fun `a controlURL on another host or port than the LOCATION is refused`() {
        val location = "http://10.0.0.5:1400/xml/desc.xml"
        fun control(url: String) = service("urn:upnp-org:serviceId:AVTransport", "AVTransport")
            .replace("/ctl/AVTransport", url)
        for (bad in listOf("http://10.0.0.9:1400/ctl", "http://10.0.0.5:8080/ctl", "http://127.0.0.1:1400/ctl", "http://10.0.0.5/ctl", "http://[::1]:1400/ctl")) {
            val e = assertFailsWith<IllegalArgumentException>(bad) { parseDescription(root(device(mr, "R", services = control(bad))), location) }
            assertEquals("controlURL is not on the LOCATION host", e.message)
        }
        // a hostile URLBase moves relative URLs the same way
        assertFailsWith<IllegalArgumentException> {
            parseDescription(root(device(mr, "R", services = avt), urlBase = "http://10.0.0.9:1400/"), location)
        }
        for (ok in listOf("http://10.0.0.5:1400/other/ctl", "/ctl", "ctl", "HTTP://10.0.0.5:1400/ctl")) {
            assertEquals(1, parseDescription(root(device(mr, "R", services = control(ok))), location).services.size, ok)
        }
        // the default port counts as the explicit one
        assertEquals(1, parseDescription(root(device(mr, "R", services = control("http://10.0.0.5:80/ctl"))), "http://10.0.0.5/d.xml").services.size)
    }

    @Test
    fun `urls resolve against the description location without URLBase`() {
        val rel = service("urn:upnp-org:serviceId:AVTransport", "AVTransport").replace("/ctl/AVTransport", "ctl/AVTransport")
        val s = parseDescription(root(device(mr, "R", services = rel)), "http://10.0.0.5:1400/xml/desc.xml")
            .services.getValue("urn:upnp-org:serviceId:AVTransport")
        assertEquals("http://10.0.0.5:1400/xml/ctl/AVTransport", s.controlURL)
    }

    @Test
    fun `both serviceId prefixes map to the same key`() {
        val a = service("urn:schemas-upnp-org:serviceId:AVTransport", "AVTransport")
        val b = service("urn:upnp-org:serviceId:AVTransport", "AVTransport")
        for (svc in listOf(a, b)) {
            val d = parseDescription(root(device(mr, "R", services = svc)), "http://10.0.0.2/d.xml")
            assertEquals(setOf("urn:upnp-org:serviceId:AVTransport"), d.services.keys)
        }
    }

    @Test
    fun `friendlyName falls back to Unknown renderer`() {
        val xml = root("<device><deviceType>$mr</deviceType><UDN>uuid:x</UDN></device>")
        assertEquals("Unknown renderer", parseDescription(xml, "http://10.0.0.2/d.xml").friendlyName)
    }

    @Test
    fun `buildDidl strips XML-illegal characters from the title before escaping`() {
        val x = buildDidl(DidlItem("http://h/s", "http-get:*:audio/mp4:*", "A\u0001B￾C"))
        assertContains(x, "<dc:title>ABC</dc:title>")
        assertFalse(x.contains('\u0001'))
        assertFalse(x.contains('￾'))
    }

    @Test
    fun `stripInvalidXmlChars keeps tab, newline, supplementary characters and drops unpaired surrogates`() {
        assertEquals("a\tb\nc\rd", stripInvalidXmlChars("a\tb\nc\rd"))
        assertEquals("😀", stripInvalidXmlChars("😀"))
        assertEquals("xy", stripInvalidXmlChars("x\uD800y"))
        assertEquals("xy", stripInvalidXmlChars("x\uDC00y"))
    }

    @Test
    fun `buildDidl duration zero produces no duration attribute and video uses videoItem`() {
        val audio = buildDidl(DidlItem("http://h/s", "p", "t", duration = 0.0))
        assertFalse(audio.contains("duration="))
        assertContains(audio, "object.item.audioItem.musicTrack")
        val video = buildDidl(DidlItem("http://h/s", "p", "t", isVideo = true, artist = "Art", album = "Alb", albumArt = "http://h/a.jpg"))
        assertContains(video, "object.item.videoItem")
        assertContains(video, "<dc:creator>Art</dc:creator><upnp:artist>Art</upnp:artist>")
        assertContains(video, "<upnp:album>Alb</upnp:album>")
        assertContains(video, "<upnp:albumArtURI>http://h/a.jpg</upnp:albumArtURI>")
    }

    @Test
    fun `didlHms is unpadded hours, floored and never negative`() {
        assertEquals("0:02:05", didlHms(125.9))
        assertEquals("1:00:00", didlHms(3600.0))
        assertEquals("10:01:01", didlHms(36061.0))
        assertEquals("0:00:00", didlHms(-5.0))
    }

    @Test
    fun `xmlEsc escapes the five characters`() {
        assertEquals("&lt;&gt;&amp;&quot;&apos;", xmlEsc("<>&\"'"))
    }

    // --- Plan 02-15 (G3): Kotlin-only tests, no Node titles ---

    private val sampleDidl = DidlItem(
        url = "http://192.168.50.38:8000/stream/abc.aac",
        protocolInfo = "http-get:*:audio/aac:DLNA.ORG_PN=AAC_ADTS_320;DLNA.ORG_OP=00;DLNA.ORG_CI=1;DLNA.ORG_FLAGS=01700000000000000000000000000000",
        title = "I Hate Myself for Loving You",
        artist = "Joan Jett & the Blackhearts",
        album = "Up Your Alley",
        albumArt = "https://i.ytimg.com/vi/V6m7b5xJq7Y/maxresdefault.jpg",
        duration = 212.0,
    )

    @Test
    @DisplayName("buildDidl matches the Node upnp.js output for a music track with an ampersand in the artist")
    fun buildDidlMatchesNode() {
        // Generated with Node, not by hand: lines 68-93 of reference/tube2upnp/src/upnp.js (esc, hms, buildDidl;
        // Node commit 5bd3025) copied into a scratch .mjs without the `export`, then
        // console.log(buildDidl({url, protocolInfo, title, artist, album, albumArt, duration: 212, isVideo: false}))
        // with the values of sampleDidl. (The whole of upnp.js cannot be imported: it needs `elementtree`.)
        val node = """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/"><item id="0" parentID="-1" restricted="1"><dc:title>I Hate Myself for Loving You</dc:title><dc:creator>Joan Jett &amp; the Blackhearts</dc:creator><upnp:artist>Joan Jett &amp; the Blackhearts</upnp:artist><upnp:album>Up Your Alley</upnp:album><upnp:albumArtURI>https://i.ytimg.com/vi/V6m7b5xJq7Y/maxresdefault.jpg</upnp:albumArtURI><upnp:class>object.item.audioItem.musicTrack</upnp:class><res protocolInfo="http-get:*:audio/aac:DLNA.ORG_PN=AAC_ADTS_320;DLNA.ORG_OP=00;DLNA.ORG_CI=1;DLNA.ORG_FLAGS=01700000000000000000000000000000" duration="0:03:32.000">http://192.168.50.38:8000/stream/abc.aac</res></item></DIDL-Lite>"""
        assertEquals(node, buildDidl(sampleDidl))
    }

    @Test
    @DisplayName("summarizeDidl reads our own DIDL")
    fun summarizeOwnDidl() {
        val s = assertNotNull(summarizeDidl(buildDidl(sampleDidl)))
        assertEquals("I Hate Myself for Loving You", s.title)
        assertEquals("Joan Jett & the Blackhearts", s.creator)
        assertEquals("Joan Jett & the Blackhearts", s.artist)
        assertEquals("Up Your Alley", s.album)
        assertEquals("https://i.ytimg.com/vi/V6m7b5xJq7Y/maxresdefault.jpg", s.albumArtUri)
        assertEquals("object.item.audioItem.musicTrack", s.upnpClass)
        assertEquals("", s.streamContent)
    }

    @Test
    @DisplayName("summarizeDidl reads a Sonos-style track metadata with prefixes and r:streamContent")
    fun summarizeSonosStyle() {
        val xml = """<DIDL-Lite xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/" """ +
            """xmlns:r="urn:schemas-rinconnetworks-com:metadata-1-0/" xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/">""" +
            """<item id="-1" parentID="-1" restricted="true"><res protocolInfo="http-get:*:audio/aac:*">http://x/y.aac</res>""" +
            """<r:streamContent>Now Playing</r:streamContent><dc:title>Some title</dc:title>""" +
            """<upnp:class>object.item.audioItem.musicTrack</upnp:class><dc:creator>Some artist</dc:creator>""" +
            """<upnp:albumArtURI>/getaa?s=1</upnp:albumArtURI></item></DIDL-Lite>"""
        val s = assertNotNull(summarizeDidl(xml))
        assertEquals("Some title", s.title)
        assertEquals("Some artist", s.creator)
        assertEquals("", s.artist)
        assertEquals("Now Playing", s.streamContent)
        assertEquals("/getaa?s=1", s.albumArtUri)
    }

    @Test
    @DisplayName("summarizeDidl returns null for null, blank, NOT_IMPLEMENTED, junk and a document without an item")
    fun summarizeNulls() {
        assertNull(summarizeDidl(null))
        assertNull(summarizeDidl("   "))
        assertNull(summarizeDidl("NOT_IMPLEMENTED"))
        assertNull(summarizeDidl("not_implemented"))
        assertNull(summarizeDidl("hello <<< & junk"))
        assertNull(summarizeDidl("<DIDL-Lite></DIDL-Lite>"))
    }

    @Test
    @DisplayName("summarizeDidl caps its input and ignores DOCTYPE entities")
    fun summarizeCapsAndIgnoresEntities() {
        val big = "<DIDL-Lite><item><dc:title>" + "a".repeat(100_000) + "</dc:title><dc:creator>LATE</dc:creator></item></DIDL-Lite>"
        val capped = assertNotNull(summarizeDidl(big))
        assertTrue(capped.title.length <= 65_536, "title ${capped.title.length}")
        assertEquals("", capped.creator, "everything past the cap is ignored")

        val secret = java.io.File.createTempFile("didl-entity", ".txt").apply { writeText("SECRET-FROM-FILE"); deleteOnExit() }
        val xml = "<?xml version=\"1.0\"?><!DOCTYPE d [<!ENTITY x \"INTERNAL-VALUE\">" +
            "<!ENTITY y SYSTEM \"${secret.toURI()}\">]><DIDL-Lite><item><dc:title>[&x;][&y;]</dc:title></item></DIDL-Lite>"
        val s = summarizeDidl(xml)
        val title = s?.title ?: ""
        assertFalse(title.contains("INTERNAL-VALUE"), title)
        assertFalse(title.contains("SECRET-FROM-FILE"), title)
    }
}
