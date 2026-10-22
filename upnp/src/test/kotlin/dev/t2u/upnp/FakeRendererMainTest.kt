package dev.t2u.upnp

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Plan 03-26: the LAN-capable FakeRenderer and its runner. The fake is driven over loopback with real SOAP, the way the
 * other upnp tests drive it; the option parser is a pure function.
 */
class FakeRendererMainTest {
    private val http = OkHttpClient()
    private val secret = "SECRETTOKEN0123456789abcdef"
    private val streamUrl = "http://192.168.50.45:8000/stream/abcdef0123456789.bin?t=$secret"

    private fun base(fake: FakeRenderer) = fake.location.removeSuffix("/desc.xml")

    private fun soap(fake: FakeRenderer, svc: String, action: String, args: String = ""): String {
        val ns = "urn:schemas-upnp-org:service:$svc:1"
        val body = """<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body>""" +
            """<u:$action xmlns:u="$ns"><InstanceID>0</InstanceID>$args</u:$action></s:Body></s:Envelope>"""
        val request = Request.Builder().url("${base(fake)}/ctl/$svc")
            .header("SOAPACTION", "\"$ns#$action\"")
            .post(body.toRequestBody("text/xml; charset=\"utf-8\"".toMediaType()))
            .build()
        return http.newCall(request).execute().use { it.body.string() }
    }

    /** SetAVTransportURI with a DIDL whose res carries [mime] (null: an empty DIDL). */
    private fun setUri(fake: FakeRenderer, mime: String?, url: String = streamUrl) {
        val didl = if (mime == null) "" else buildDidl(DidlItem(url, "http-get:*:$mime:DLNA.ORG_OP=01", "A Song", "An Artist"))
        soap(fake, "AVTransport", "SetAVTransportURI", "<CurrentURI>${xmlEsc(url)}</CurrentURI><CurrentURIMetaData>${xmlEsc(didl)}</CurrentURIMetaData>")
    }

    private fun play(fake: FakeRenderer) = soap(fake, "AVTransport", "Play", "<Speed>1</Speed>")

    private fun transportOnTheWire(fake: FakeRenderer): String =
        Regex("<CurrentTransportState>(.*?)</CurrentTransportState>").find(soap(fake, "AVTransport", "GetTransportInfo"))?.groupValues?.get(1) ?: ""

    private fun get(url: String): Pair<Int, String> =
        http.newCall(Request.Builder().url(url).build()).execute().use { it.code to it.body.string() }

    /** Polls until [cond] holds or [timeoutMs] passes; returns the last answer. */
    private fun awaitTrue(timeoutMs: Long, cond: () -> Boolean): Boolean {
        val end = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < end) {
            if (cond()) return true
            Thread.sleep(10)
        }
        return cond()
    }

    // ---- option parsing -------------------------------------------------------------------------------

    @Test
    fun theDefaultsAreTheDocumentedOnes() {
        val parsed = parseFakeRendererOptions(emptyList()) { "192.168.50.45" }
        val run = assertIs<FakeRendererParse.Run>(parsed)
        assertEquals("Fake Test Speaker", run.options.name)
        assertEquals(49200, run.options.port)
        assertEquals("192.168.50.45", run.options.bind)
        assertNull(run.options.playMimes)
        assertEquals(800L, run.options.startDelayMs)
        for (mime in listOf("audio/mpeg", "audio/mp4", "audio/flac", "audio/aac")) {
            assertContains(run.options.sink, "http-get:*:$mime:*")
        }
    }

    @Test
    fun everyOptionIsParsed() {
        val parsed = parseFakeRendererOptions(
            listOf(
                "--name", "Kitchen", "--port", "49201", "--bind", "10.1.2.3",
                "--sink", "audio/flac, http-get:*:audio/mpeg:*",
                "--play-mimes", "Audio/X-FLAC,audio/mpeg", "--start-delay-ms", "250",
            ),
        ) { "192.168.50.45" }
        val run = assertIs<FakeRendererParse.Run>(parsed)
        assertEquals("Kitchen", run.options.name)
        assertEquals(49201, run.options.port)
        assertEquals("10.1.2.3", run.options.bind)
        assertEquals("http-get:*:audio/flac:*,http-get:*:audio/mpeg:*", run.options.sink)
        assertEquals(setOf("audio/x-flac", "audio/mpeg"), run.options.playMimes)
        assertEquals(250L, run.options.startDelayMs)
    }

    @Test
    fun aBadOptionFailsWithAMessage() {
        val bad = listOf(
            listOf("--colour", "red") to "--colour",
            listOf("--port") to "--port",
            listOf("--port", "70000") to "--port",
            listOf("--port", "abc") to "--port",
            listOf("--start-delay-ms", "-5") to "--start-delay-ms",
            listOf("--play-mimes", "flac") to "--play-mimes",
            listOf("--name", "") to "--name",
        )
        for ((args, mention) in bad) {
            val parsed = parseFakeRendererOptions(args) { "192.168.50.45" }
            val invalid = assertIs<FakeRendererParse.Invalid>(parsed, "args $args")
            assertContains(invalid.message, mention)
        }
        val noAddress = assertIs<FakeRendererParse.Invalid>(parseFakeRendererOptions(emptyList()) { null })
        assertContains(noAddress.message, "--bind")
    }

    @Test
    fun aNonPrivateBindIsRefused() {
        for (ip in listOf("8.8.8.8", "127.0.0.1", "0.0.0.0", "172.32.0.1", "192.169.1.1", "example.com", "300.1.1.1")) {
            val invalid = assertIs<FakeRendererParse.Invalid>(parseFakeRendererOptions(listOf("--bind", ip)) { "192.168.50.45" }, ip)
            assertContains(invalid.message, "refusing")
        }
        for (ip in listOf("10.0.0.1", "172.16.0.1", "172.31.255.254", "192.168.50.45", "169.254.1.2")) {
            assertIs<FakeRendererParse.Run>(parseFakeRendererOptions(listOf("--bind", ip)) { null }, ip)
        }
        assertTrue(isPrivateIpv4("192.168.0.1"))
        assertFalse(isPrivateIpv4("192.167.0.1"))
        assertFalse(isPrivateIpv4("172.15.0.1"))
    }

    @Test
    fun helpIsRecognisedAnywhereInTheArguments() {
        assertEquals(FakeRendererParse.Help, parseFakeRendererOptions(listOf("--help")) { null })
        assertEquals(FakeRendererParse.Help, parseFakeRendererOptions(listOf("--name", "x", "-h")) { null })
    }

    @Test
    fun theUdnIsStablePerNameAndDiffersBetweenNames() {
        val a = fakeRendererUdn("Fake Test Speaker")
        assertEquals(a, fakeRendererUdn("Fake Test Speaker"))
        assertNotEquals(a, fakeRendererUdn("Another Fake"))
        assertTrue(Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$").matches(a), a)
    }

    // ---- the mime rule ---------------------------------------------------------------------------------

    @Test
    fun aStreamWhoseMimeIsNotInPlayMimesNeverLeavesTransitioning() {
        FakeRenderer(playMimes = setOf("audio/flac"), startDelayMs = 50).use { fake ->
            fake.start()
            setUri(fake, "audio/mp4")
            play(fake)
            assertEquals("TRANSITIONING", fake.state.transport)
            Thread.sleep(400)
            assertEquals("TRANSITIONING", fake.state.transport)
            assertEquals("TRANSITIONING", transportOnTheWire(fake))
            // the speaker accepted both calls: the app sees no fault, only a start that never comes
            assertEquals(listOf("SetAVTransportURI", "Play", "GetTransportInfo"), fake.calls.map { it.action })
        }
    }

    @Test
    fun aStreamWhoseMimeIsInPlayMimesReachesPlayingAfterTheDelay() {
        FakeRenderer(playMimes = setOf("audio/flac"), startDelayMs = 300).use { fake ->
            fake.start()
            setUri(fake, "audio/flac")
            val started = System.nanoTime()
            play(fake)
            assertEquals("TRANSITIONING", fake.state.transport)
            assertTrue(awaitTrue(3000) { fake.state.transport == "PLAYING" }, "never reached PLAYING")
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            assertTrue(elapsedMs >= 290, "PLAYING came after $elapsedMs ms, before the 300 ms delay")
            assertEquals("PLAYING", transportOnTheWire(fake))
        }
    }

    @Test
    fun anUnknownMimeIsNotPlayableWhenPlayMimesIsGiven() {
        FakeRenderer(playMimes = setOf("audio/flac"), startDelayMs = 20).use { fake ->
            fake.start()
            // no SetAVTransportURI at all, then an empty DIDL, then a DIDL that is not XML
            play(fake)
            assertEquals("TRANSITIONING", fake.state.transport)
            setUri(fake, null)
            play(fake)
            soap(fake, "AVTransport", "SetAVTransportURI", "<CurrentURI>${xmlEsc(streamUrl)}</CurrentURI><CurrentURIMetaData>not xml at all</CurrentURIMetaData>")
            play(fake)
            Thread.sleep(300)
            assertEquals("TRANSITIONING", fake.state.transport)
        }
    }

    @Test
    fun withoutPlayMimesTheFakeBehavesAsBefore() {
        FakeRenderer().use { fake ->
            fake.start()
            assertEquals("STOPPED", fake.state.transport)
            setUri(fake, "audio/never-heard-of")
            play(fake)
            // no delay, no mime rule: Play answers with the transport already PLAYING
            assertEquals("PLAYING", fake.state.transport)
            soap(fake, "AVTransport", "Stop")
            assertEquals("STOPPED", fake.state.transport)
            soap(fake, "AVTransport", "Pause")
            assertEquals("PAUSED_PLAYBACK", fake.state.transport)
        }
        FakeRenderer(neverPlay = true).use { fake ->
            fake.start()
            setUri(fake, "audio/mp4")
            play(fake)
            assertEquals("STOPPED", fake.state.transport)
        }
    }

    @Test
    fun aMimeListedUnderAnAliasCountsAsListed() {
        assertTrue(mimeIsListed("audio/flac", setOf("audio/x-flac")))
        assertTrue(mimeIsListed("audio/x-flac", setOf("audio/flac")))
        assertTrue(mimeIsListed("AUDIO/MPEG", setOf("audio/mp3")))
        assertTrue(mimeIsListed("audio/mp4", setOf("audio/mp4")))
        assertFalse(mimeIsListed("audio/mp4", setOf("audio/flac")))
        assertFalse(mimeIsListed("audio/webm", setOf("audio/mp4", "audio/flac")))
        FakeRenderer(playMimes = setOf("audio/x-flac")).use { fake ->
            fake.start()
            setUri(fake, "audio/flac")
            play(fake)
            assertEquals("PLAYING", fake.state.transport)
        }
    }

    @Test
    fun theMimeIsReadFromTheDidlIncludingTheEscapedForm() {
        val didl = buildDidl(DidlItem(streamUrl, "http-get:*:audio/flac:DLNA.ORG_OP=01;DLNA.ORG_CI=1", "T & T <live>", "A", duration = 125.0))
        assertEquals("audio/flac", mimeOfDidl(didl))
        // the form a SOAP body carries: escaped once, and (a sender that escapes twice) escaped twice
        assertEquals("audio/flac", mimeOfDidl(xmlEsc(didl)))
        assertEquals("audio/flac", mimeOfDidl(xmlEsc(xmlEsc(didl))))
        assertEquals("audio/mp4", mimeOfDidl("""<res protocolInfo='http-get:*:Audio/MP4:*'>x</res>"""))
        assertNull(mimeOfDidl(""))
        assertNull(mimeOfDidl("not xml"))
        assertNull(mimeOfDidl("""<res protocolInfo="rtsp-rtp-udp:*:audio/flac:*">x</res>"""))
        assertNull(mimeOfDidl("""<res protocolInfo="http-get:*:*:*">x</res>"""))
    }

    @Test
    fun stopBeforeTheDelayEndsKeepsTheSpeakerStopped() {
        FakeRenderer(playMimes = setOf("audio/flac"), startDelayMs = 200).use { fake ->
            fake.start()
            setUri(fake, "audio/flac")
            play(fake)
            soap(fake, "AVTransport", "Stop")
            Thread.sleep(500)
            assertEquals("STOPPED", fake.state.transport)
        }
    }

    // ---- the log -----------------------------------------------------------------------------------------

    @Test
    fun theLogCallbackNeverReceivesAUrlOrADidlBody() {
        val lines = CopyOnWriteArrayList<String>()
        FakeRenderer(playMimes = setOf("audio/flac"), startDelayMs = 30, log = { lines.add(it) }).use { fake ->
            fake.start()
            setUri(fake, "audio/mp4")
            play(fake)
            soap(fake, "AVTransport", "Stop")
            setUri(fake, "audio/flac")
            play(fake)
            assertTrue(awaitTrue(3000) { lines.any { it.startsWith("PLAYING reached") } }, "no PLAYING line: $lines")
            soap(fake, "AVTransport", "Seek", "<Unit>REL_TIME</Unit><Target>0:01:05</Target>")
            soap(fake, "AVTransport", "Stop")
        }
        assertContains(lines, "SetAVTransportURI mime=audio/mp4")
        assertContains(lines, "SetAVTransportURI mime=audio/flac")
        assertTrue(lines.any { it.startsWith("Play mime=audio/mp4") }, "$lines")
        assertTrue(lines.any { it.startsWith("Play mime=audio/flac") }, "$lines")
        assertTrue(lines.any { Regex("^PLAYING reached \\d+ ms after Play$").matches(it) }, "$lines")
        assertTrue(lines.any { it.startsWith("Seek") }, "$lines")
        assertTrue(lines.count { it == "Stop" } >= 2, "$lines")
        for (line in lines) {
            assertFalse("http" in line.lowercase(), "a URL reached the log: $line")
            assertFalse(secret in line, "a token reached the log: $line")
            assertFalse('<' in line || "DIDL" in line || "protocolInfo" in line, "a DIDL body reached the log: $line")
        }
    }

    // ---- the description and the address ----------------------------------------------------------------

    @Test
    fun theDescriptionCarriesTheNameAndAStableUdn() {
        FakeRenderer(friendlyName = "Kitchen & Co", udn = "1234abcd-0000-4000-8000-000000000001").use { fake ->
            fake.start()
            val (status, xml) = get(fake.location)
            assertEquals(200, status)
            assertContains(xml, "<friendlyName>Kitchen &amp; Co</friendlyName>")
            assertContains(xml, "<UDN>uuid:1234abcd-0000-4000-8000-000000000001</UDN>")
            assertContains(xml, "urn:schemas-upnp-org:device:MediaRenderer:1")
            val d = parseDescription(xml, fake.location)
            assertEquals("Kitchen & Co", d.friendlyName)
            assertEquals("uuid:1234abcd-0000-4000-8000-000000000001", d.udn)
            for (svc in listOf("AVTransport", "RenderingControl", "ConnectionManager")) {
                assertTrue("urn:upnp-org:serviceId:$svc" in d.services, "no $svc in ${d.services.keys}")
            }
        }
    }

    @Test
    fun withoutANameAndAUdnTheDescriptionIsUnchanged() {
        FakeRenderer().use { fake ->
            fake.start()
            assertTrue(Regex("""^http://127\.0\.0\.1:\d+/desc\.xml$""").matches(fake.location), fake.location)
            val d = parseDescription(get(fake.location).second, fake.location)
            assertEquals("Living Room Speaker", d.friendlyName)
            assertEquals("uuid:fake-renderer", d.udn)
            assertContains(get(fake.location).second, "<friendlyName>Living Room</friendlyName>")
            assertContains(get(fake.location).second, "<UDN>uuid:root</UDN>")
        }
    }

    @Test
    fun theLocationFollowsTheBindHostAndPort() {
        val port = ServerSocket(0).use { it.localPort }
        FakeRenderer(bindHost = "127.0.0.2", bindPort = port).use { fake ->
            fake.start()
            assertEquals("http://127.0.0.2:$port/desc.xml", fake.location)
            assertEquals(200, get(fake.location).first)
        }
    }

    @Test
    fun theSinkIsWhatGetProtocolInfoAnswers() {
        val sink = expandSink("audio/flac,audio/mpeg")
        assertEquals("http-get:*:audio/flac:*,http-get:*:audio/mpeg:*", sink)
        FakeRenderer(sink = sink).use { fake ->
            fake.start()
            assertContains(soap(fake, "ConnectionManager", "GetProtocolInfo"), "<Sink>$sink</Sink>")
        }
    }
}
