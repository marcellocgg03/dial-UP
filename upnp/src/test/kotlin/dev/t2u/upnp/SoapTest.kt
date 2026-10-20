package dev.t2u.upnp

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.serverConfig
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.EngineConnectorBuilder
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Tests for Soap.kt, a port of tube2upnp src/soap.js. The Node project tests the same behaviour in
 * renderer.test.js ('renderer errors without UPnP fault body do not crash, and UTF-8 bodies have
 * byte-accurate length'); the finer cases here are Kotlin additions.
 */
class SoapTest {
    private val soap = SoapClient(OkHttpClient())

    private fun avTransport(fake: FakeRenderer) = UpnpService(
        serviceType = "urn:schemas-upnp-org:service:AVTransport:1",
        controlURL = fake.location.replace("/desc.xml", "/ctl/AVTransport"),
        scpdURL = "",
        eventSubURL = "",
    )

    private fun renderingControl(fake: FakeRenderer) = UpnpService(
        serviceType = "urn:schemas-upnp-org:service:RenderingControl:1",
        controlURL = fake.location.replace("/desc.xml", "/ctl/RenderingControl"),
        scpdURL = "",
        eventSubURL = "",
    )

    /** One-route Ktor server answering every SOAP POST with a fixed status and body. */
    private fun <T> withCannedServer(status: Int, body: String, block: (UpnpService) -> T): T {
        val server: EmbeddedServer<*, *> = embeddedServer(
            CIO,
            serverConfig {
                module {
                    routing {
                        post("/ctl") {
                            call.respondText(body, ContentType.Text.Xml, HttpStatusCode.fromValue(status))
                        }
                    }
                }
            },
        ) {
            connectors.add(
                EngineConnectorBuilder().apply {
                    host = "127.0.0.1"
                    port = 0
                },
            )
        }
        server.start(wait = false)
        try {
            val port = runBlocking { server.engine.resolvedConnectors().first().port }
            return block(
                UpnpService("urn:schemas-upnp-org:service:AVTransport:1", "http://127.0.0.1:$port/ctl", "", ""),
            )
        } finally {
            server.stop(100, 500)
        }
    }

    @Test
    fun `GetTransportInfo on a fresh FakeRenderer returns STOPPED`() = runBlocking {
        FakeRenderer().use { fake ->
            fake.start()
            val out = soap.callAction(avTransport(fake), "GetTransportInfo", listOf("InstanceID" to 0))
            assertEquals("STOPPED", out["CurrentTransportState"])
            assertEquals("OK", out["CurrentTransportStatus"])
        }
    }

    @Test
    @DisplayName("renderer errors without UPnP fault body do not crash, and UTF-8 bodies have byte-accurate length")
    fun utf8BodiesAndBareFaults() = runBlocking {
        FakeRenderer(bareFaultOn = setOf("Pause")).use { fake ->
            fake.start()
            val title = "Song & Dance – Pitypang ő ű"
            soap.callAction(
                avTransport(fake),
                "SetAVTransportURI",
                listOf("InstanceID" to 0, "CurrentURI" to "http://127.0.0.1/x", "CurrentURIMetaData" to title),
            )
            val call = fake.calls.single { it.action == "SetAVTransportURI" }
            assertEquals(call.bytes.toLong(), call.contentLength, "Content-Length must be the UTF-8 byte count")
            assertTrue(call.bytes > call.body.length, "non-ASCII text makes the byte count exceed the character count")
            assertTrue(call.body.contains("Song &amp; Dance"), "ampersand is escaped in the body")
            assertTrue(call.body.contains("Pitypang ő ű"), "non-ASCII text arrives intact")

            val err = assertFailsWith<UpnpError> { soap.callAction(avTransport(fake), "Pause", listOf("InstanceID" to 0)) }
            assertEquals("Pause: HTTP 500", err.message)
            assertEquals("EUPNP", err.code)
            assertEquals(500, err.statusCode)
            assertEquals("", err.errorCode)
        }
    }

    @Test
    fun `a fault with errorCode 714 and no errorDescription reads Illegal MIME-type`() {
        withCannedServer(
            500,
            """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><s:Fault><detail>""" +
                """<UPnPError xmlns="urn:schemas-upnp-org:control-1-0"><errorCode>714</errorCode></UPnPError>""" +
                """</detail></s:Fault></s:Body></s:Envelope>""",
        ) { svc ->
            val err = assertFailsWith<UpnpError> { runBlocking { soap.callAction(svc, "Play") } }
            assertEquals("Play: Illegal MIME-type (714)", err.message)
            assertEquals("EUPNP", err.code)
            assertEquals("714", err.errorCode)
        }
    }

    @Test
    fun `errorCode 401 maps to ENOACTION`() {
        withCannedServer(
            500,
            """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><s:Fault><detail>""" +
                """<UPnPError xmlns="urn:schemas-upnp-org:control-1-0"><errorCode>401</errorCode>""" +
                """<errorDescription>Invalid Action</errorDescription></UPnPError></detail></s:Fault></s:Body></s:Envelope>""",
        ) { svc ->
            val err = assertFailsWith<UpnpError> { runBlocking { soap.callAction(svc, "SetNextAVTransportURI") } }
            assertEquals("ENOACTION", err.code)
            assertEquals("SetNextAVTransportURI: Invalid Action (401)", err.message)
        }
    }

    @Test
    fun `a closed port gives ENETWORK`() {
        val port = ServerSocket(0).use { it.localPort }
        val svc = UpnpService("urn:schemas-upnp-org:service:AVTransport:1", "http://127.0.0.1:$port/ctl", "", "")
        val err = assertFailsWith<UpnpError> { runBlocking { soap.callAction(svc, "Stop") } }
        assertEquals("ENETWORK", err.code)
        assertTrue(err.message!!.startsWith("Stop: "), err.message)
    }

    @Test
    fun `the default client refuses a public address as ENETWORK`() {
        val svc = UpnpService("urn:schemas-upnp-org:service:AVTransport:1", "http://8.8.8.8/ctl", "", "")
        val err = assertFailsWith<UpnpError> { runBlocking { SoapClient().callAction(svc, "Stop") } }
        assertEquals("ENETWORK", err.code)
        assertTrue(err.message!!.contains("refused"), err.message)
    }

    @Test
    fun `a 200 with a body that is not XML returns an empty map`() {
        withCannedServer(200, "not xml") { svc ->
            val out = runBlocking { soap.callAction(svc, "Stop") }
            assertTrue(out.isEmpty())
        }
    }

    @Test
    fun `SetVolume and GetVolume round trip through the fake renderer`() = runBlocking {
        FakeRenderer().use { fake ->
            fake.start()
            soap.callAction(renderingControl(fake), "SetVolume", listOf("InstanceID" to 0, "Channel" to "Master", "DesiredVolume" to 55))
            val out = soap.callAction(renderingControl(fake), "GetVolume", listOf("InstanceID" to 0, "Channel" to "Master"))
            assertEquals("55", out["CurrentVolume"])
        }
    }

    @Test
    fun `parseResponse reads prefixed and unprefixed response elements`() {
        val prefixed = """<s:Envelope xmlns:s="x"><s:Body><u:GetVolumeResponse xmlns:u="y"><CurrentVolume> 12 </CurrentVolume></u:GetVolumeResponse></s:Body></s:Envelope>"""
        val bare = """<Envelope><Body><GetVolumeResponse><CurrentVolume>7</CurrentVolume></GetVolumeResponse></Body></Envelope>"""
        assertEquals(mapOf("CurrentVolume" to "12"), parseResponse(prefixed, "GetVolume"))
        assertEquals(mapOf("CurrentVolume" to "7"), parseResponse(bare, "GetVolume"))
    }

    @Test
    fun `parseFault never throws and caps the description`() {
        assertEquals(SoapFault("", ""), parseFault(""))
        val junk = parseFault("<<<")
        assertEquals("", junk.code)
        assertTrue(junk.desc.length <= 200)
        val long = parseFault("<html>" + "word  \n ".repeat(100))
        assertTrue(long.desc.length <= 200)
        assertTrue(!long.desc.contains("  "), "whitespace is collapsed")
    }

    @Test
    fun `parseSink handles null and blank entries`() {
        assertTrue(parseSink(null).isEmpty())
        val one = parseSink("http-get:*:audio/mpeg:*, ")
        assertEquals(listOf(SinkProtocol("http-get", "*", "audio/mpeg", "*")), one)
    }

    @Test
    fun `soapEscape escapes the five XML characters`() {
        assertEquals("&amp;&lt;&gt;&quot;&apos;", soapEscape("&<>\"'"))
        assertEquals("", soapEscape(null))
    }
}
