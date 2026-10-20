package dev.t2u.dial

import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteReadChannel
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DialServerTest {
    private val uuid = "11652cc9-8e9f-4431-9bdd-49a5bf04dd57"
    private val config = DialConfig("T2U-SPIKE A base", uuid, "127.0.0.1", 3000)

    private fun golden(name: String): String = DialServerTest::class.java.getResource("/golden/$name")!!.readText()

    private fun norm(s: String): String = s.replace(Regex("\\s+"), " ").trim()

    private fun dialTest(cfg: DialConfig = config, seen: MutableList<DialRequest> = CopyOnWriteArrayList(), block: suspend ApplicationTestBuilder.() -> Unit) =
        testApplication {
            application { DialServer(cfg) { seen.add(it) }.configure(this) }
            block()
        }

    private suspend fun ApplicationTestBuilder.get(path: String, host: String = "192.168.50.239:3000"): HttpResponse =
        client.get(path) { header(HttpHeaders.Host, host) }

    // ----- GET routes -------------------------------------------------------------------------

    @Test
    fun deviceDescriptionCarriesApplicationUrlAndMatchesGolden() = dialTest {
        val r = get("/ytcr/ssdp/device-desc.xml")
        assertEquals(HttpStatusCode.OK, r.status)
        assertEquals("http://192.168.50.239:3000/ytcr/apps", r.headers["Application-URL"])
        assertTrue(r.headers[HttpHeaders.ContentType]!!.startsWith("application/xml"), r.headers[HttpHeaders.ContentType])
        assertEquals(norm(golden("device-desc.xml")), norm(r.bodyAsText()))
    }

    @Test
    fun applicationUrlFollowsTheRequestHostHeader() = dialTest {
        val r = get("/ytcr/ssdp/device-desc.xml", host = "10.1.2.3")
        assertEquals("http://10.1.2.3:3000/ytcr/apps", r.headers["Application-URL"])
        assertTrue(r.bodyAsText().contains("<URLBase>http://10.1.2.3:3000/ytcr</URLBase>"))
    }

    @Test
    fun appsRootAnswers204() = dialTest {
        assertEquals(HttpStatusCode.NoContent, get("/ytcr/apps").status)
    }

    @Test
    fun youTubeAppDescriptionMatchesGoldenExceptThePid() = dialTest {
        val r = get("/ytcr/apps/YouTube")
        assertEquals(HttpStatusCode.OK, r.status)
        val body = r.bodyAsText()
        val pid = Regex("href=\"([^\"]+)\"").find(body)?.groupValues?.get(1)
        assertNotNull(pid, body)
        assertEquals(norm(golden("app-desc-running.xml")), norm(body.replace(pid, "3a8650d4-a45b-498c-8dd2-ebee30afa3c3")))
    }

    @Test
    fun stoppedStateHasNoRunLink() = dialTest(config.copy(appState = "stopped")) {
        val body = get("/ytcr/apps/YouTube").bodyAsText()
        assertTrue(body.contains("<state>stopped</state>"))
        assertTrue(!body.contains("<link"), body)
    }

    @Test
    fun unknownAppIs404() = dialTest {
        assertEquals(HttpStatusCode.NotFound, get("/ytcr/apps/Netflix").status)
    }

    @Test
    fun ssdpNotFoundAndUnknownPathsAre404() = dialTest {
        assertEquals(HttpStatusCode.NotFound, get("/ytcr/ssdp/notfound").status)
        assertEquals(HttpStatusCode.NotFound, get("/ytcr/nothing").status)
    }

    // ----- POST launch ------------------------------------------------------------------------

    @Test
    fun launchWhileRunningAnswers200WithLocationAndIsReportedWithItsBody() {
        val seen = CopyOnWriteArrayList<DialRequest>()
        dialTest(seen = seen) {
            val r = client.post("/ytcr/apps/YouTube") {
                header(HttpHeaders.Host, "192.168.50.239:3000")
                header(HttpHeaders.UserAgent, "t2u-test")
                contentType(ContentType.Application.FormUrlEncoded)
                setBody("pairingCode=abc&theme=cl")
            }
            assertEquals(HttpStatusCode.OK, r.status)
            val loc = r.headers["LOCATION"]
            assertNotNull(loc)
            assertTrue(loc.startsWith("http://192.168.50.239:3000/ytcr/apps/YouTube/"), loc)
        }
        val post = seen.single { it.method == "POST" }
        assertEquals("pairingCode=<redacted len 3>&theme=cl", post.body)
        assertEquals(200, post.status)
        assertEquals("/ytcr/apps/YouTube", post.uri)
        assertEquals("t2u-test", post.headers["User-Agent"])
        assertTrue(post.httpVersion.startsWith("HTTP/"), post.httpVersion)
    }

    @Test
    fun launchWhileStoppedAnswers201() = dialTest(config.copy(appState = "stopped")) {
        val r = client.post("/ytcr/apps/YouTube") { setBody("pairingCode=abc") }
        assertEquals(HttpStatusCode.Created, r.status)
        assertNotNull(r.headers["LOCATION"])
    }

    @Test
    fun bodyOf4096BytesIsAcceptedAnd4097IsRejectedWith413() = dialTest {
        val ok = client.post("/ytcr/apps/YouTube") { setBody("a".repeat(4096)) }
        assertEquals(HttpStatusCode.OK, ok.status)
        val tooBig = client.post("/ytcr/apps/YouTube") { setBody("a".repeat(4097)) }
        assertEquals(HttpStatusCode.PayloadTooLarge, tooBig.status)
    }

    @Test
    fun bodyWithoutDeclaredLengthIsCappedAt4096Too() = dialTest {
        // a ByteReadChannel body has no known length, so the cap must hold on the bytes that arrive
        val r = client.post("/ytcr/apps/YouTube") { setBody(ByteReadChannel("b".repeat(5000).toByteArray())) }
        assertEquals(HttpStatusCode.PayloadTooLarge, r.status)
    }

    @Test
    fun unknownAppPostIs404AndDialDataIs501() = dialTest {
        assertEquals(HttpStatusCode.NotFound, client.post("/ytcr/apps/Netflix") { setBody("x") }.status)
        assertEquals(HttpStatusCode.NotImplemented, client.post("/ytcr/apps/YouTube/dial_data") { setBody("x") }.status)
        assertEquals(HttpStatusCode.NotFound, client.post("/ytcr/apps/Netflix/dial_data") { setBody("x") }.status)
    }

    // ----- DELETE and misc --------------------------------------------------------------------

    @Test
    fun deleteIs405BecauseAllowStopIsFalse() = dialTest {
        assertEquals(HttpStatusCode.MethodNotAllowed, client.delete("/ytcr/apps/YouTube/some-pid").status)
        assertEquals(HttpStatusCode.NotFound, client.delete("/ytcr/apps/Netflix/some-pid").status)
    }

    @Test
    fun responsesCarryExactlyOneContentLengthHeader() = dialTest {
        for (path in listOf("/ytcr/ssdp/device-desc.xml", "/ytcr/apps/YouTube")) {
            val r = get(path)
            assertEquals(1, r.headers.getAll(HttpHeaders.ContentLength)?.size, "Content-Length count for $path")
        }
    }

    @Test
    fun everyCallIs404IncludedReportedToTheCallback() {
        val seen = CopyOnWriteArrayList<DialRequest>()
        dialTest(seen = seen) {
            get("/ytcr/apps/Netflix")
            get("/ytcr/other")
        }
        assertEquals(listOf(404, 404), seen.map { it.status })
        assertNull(seen[0].body)
    }

    // ----- spike variants ---------------------------------------------------------------------

    private fun variants() = spikeVariants("6.1.0-test", "16") { java.util.UUID.randomUUID().toString() }

    @Test
    fun fourVariantsWithAsciiSpikeNamesOnPorts3100To3103() {
        val v = variants()
        assertEquals(listOf("A", "B", "C", "D"), v.map { it.id })
        assertEquals(listOf(3100, 3101, 3102, 3103), v.map { it.port })
        assertEquals(
            listOf("T2U-SPIKE A base", "T2U-SPIKE B server", "T2U-SPIKE C uuid", "T2U-SPIKE D loc"),
            v.map { it.name },
        )
        for (x in v) {
            assertTrue(x.name.startsWith("T2U-SPIKE "), x.name)
            assertTrue(x.name.all { it.code in 0x20..0x7e }, "ASCII only: ${x.name}")
        }
        assertEquals(4, v.map { it.uuid }.toSet().size, "every variant has its own UUID")
    }

    @Test
    fun variantCUuidIsStableAndAAndBChangeBetweenCalls() {
        val first = variants()
        val second = variants()
        assertEquals(first[2].uuid, second[2].uuid)
        assertEquals(java.util.UUID.nameUUIDFromBytes("t2u-spike-C".toByteArray()).toString(), first[2].uuid)
        assertTrue(first[0].uuid != second[0].uuid)
        assertTrue(first[1].uuid != second[1].uuid)
    }

    @Test
    fun serverHeadersAndLocationHosts() {
        val v = variants()
        assertEquals("Linux/6.1.0-test UPnP/1.1 famium/0.0.1", v[0].serverHeader)
        assertTrue(v[1].serverHeader.startsWith("Android/"), v[1].serverHeader)
        assertEquals(LocationHost.WifiIp, v[0].locationHost)
        assertEquals(LocationHost.Fixed("127.0.0.1"), v[3].locationHost)
        assertEquals(LocationHost.Fixed("127.0.0.1"), v[3].bindHost)
        assertEquals(LocationHost.WifiIp, v[2].bindHost)
    }
}
