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
        val s = parseDescription(xml, "http://127.0.0.1:9/some/desc.xml").services.getValue("urn:upnp-org:serviceId:AVTransport")
        assertEquals("http://10.0.0.5:1400/ctl/AVTransport", s.controlURL)
        assertEquals("http://10.0.0.5:1400/scpd/AVTransport.xml", s.scpdURL)
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
}
