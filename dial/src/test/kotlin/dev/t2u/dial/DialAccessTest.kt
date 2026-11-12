package dev.t2u.dial

import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** SVC-09 (D-01): the access policy, the HTTP gate on DialServer, the launch hook and the pairing-code redaction. */
class DialAccessTest {
    private val phoneIp = "192.168.50.38"
    private val uuid = "11652cc9-8e9f-4431-9bdd-49a5bf04dd57"

    private fun policy(mode: DialAccessMode, phone: Set<String> = setOf(phoneIp)) = DialAccessPolicy({ mode }, { phone })

    // ----- policy -----------------------------------------------------------------------------

    @Test
    fun wireNamesRoundTripAndUnknownIsNull() {
        assertEquals("phone", DialAccessMode.PHONE.wire)
        assertEquals("lan", DialAccessMode.LAN.wire)
        assertEquals(DialAccessMode.PHONE, DialAccessMode.fromWire("phone"))
        assertEquals(DialAccessMode.LAN, DialAccessMode.fromWire("lan"))
        assertNull(DialAccessMode.fromWire("everyone"))
        assertNull(DialAccessMode.fromWire(null))
    }

    @Test
    fun onlyAnotherDeviceInLanModeIsAGuest() {
        val lan = policy(DialAccessMode.LAN)
        assertTrue(lan.isGuest("192.168.50.45"))
        assertTrue(lan.isGuest("::ffff:192.168.50.45"))
        for (own in listOf("192.168.50.38", "::ffff:192.168.50.38", "127.0.0.1", "::1", "[::1]", "")) {
            assertFalse(lan.isGuest(own), "'$own' is not a guest")
        }
        val phone = policy(DialAccessMode.PHONE)
        assertFalse(phone.isGuest("192.168.50.45"), "phone mode never has guests")
    }

    @Test
    fun phoneModeAllowsOnlyThePhonesOwnAddresses() {
        val p = policy(DialAccessMode.PHONE)
        for (ok in listOf("192.168.50.38", "::ffff:192.168.50.38", "::FFFF:192.168.50.38", "127.0.0.1", "127.5.5.5", "::1", "[::1]", "0:0:0:0:0:0:0:1")) {
            assertTrue(p.allowsHttp(ok), "should allow $ok")
        }
        for (no in listOf("192.168.50.45", "10.0.0.1", "fe80::1%wlan0", "", "localhost", "127.0.0", "1127.0.0.1", "128.0.0.1", "::2")) {
            assertFalse(p.allowsHttp(no), "should refuse '$no'")
        }
    }

    @Test
    fun lanModeAllowsEveryoneAndPhoneModeFollowsTheLiveMode() {
        assertTrue(policy(DialAccessMode.LAN).allowsHttp("192.168.50.45"))
        var mode = DialAccessMode.PHONE
        val live = DialAccessPolicy({ mode }, { setOf(phoneIp) })
        assertFalse(live.allowsHttp("192.168.50.45"))
        mode = DialAccessMode.LAN
        assertTrue(live.allowsHttp("192.168.50.45"))
    }

    @Test
    fun phoneAddressesAreReadPerRequest() {
        var phone = setOf(phoneIp)
        val p = DialAccessPolicy({ DialAccessMode.PHONE }, { phone })
        assertFalse(p.allowsHttp("192.168.50.50"))
        phone = setOf("192.168.50.50")
        assertTrue(p.allowsHttp("192.168.50.50"))
    }

    @Test
    fun ssdpUsesTheSameDecisionOnTheAddressObject() {
        val p = policy(DialAccessMode.PHONE)
        assertTrue(p.allowsSsdp(InetAddress.getByName(phoneIp)))
        assertTrue(p.allowsSsdp(InetAddress.getByName("127.0.0.1")))
        assertTrue(p.allowsSsdp(InetAddress.getByName("::1")))
        assertFalse(p.allowsSsdp(InetAddress.getByName("192.168.50.45")))
        assertTrue(policy(DialAccessMode.LAN).allowsSsdp(InetAddress.getByName("192.168.50.45")))
    }

    @Test
    fun lanModeAnswersSsdpOnlyFromPrivateLanSources() {
        val lan = policy(DialAccessMode.LAN)
        for (ok in listOf("192.168.50.45", "10.1.2.3", "172.16.0.9", "169.254.1.1", "127.0.0.1", "::1", "fe80::1", "fd12:3456::1", "::ffff:192.168.50.45", "::ffff:10.0.0.1")) {
            assertTrue(lan.allowsSsdp(InetAddress.getByName(ok)), "should answer $ok")
        }
        for (no in listOf("8.8.8.8", "203.0.113.7", "100.64.0.1", "172.32.0.1", "2001:db8::1", "2606:4700::1111", "::ffff:8.8.8.8")) {
            assertFalse(lan.allowsSsdp(InetAddress.getByName(no)), "should refuse $no")
        }
        val phone = policy(DialAccessMode.PHONE)
        assertFalse(phone.allowsSsdp(InetAddress.getByName("8.8.8.8")))
    }

    @Test
    fun announceIsAllowedOnlyInLanMode() {
        assertFalse(policy(DialAccessMode.PHONE).announceAllowed())
        assertTrue(policy(DialAccessMode.LAN).announceAllowed())
    }

    // ----- redaction --------------------------------------------------------------------------

    @Test
    fun redactReplacesOnlyThePairingCodeValue() {
        assertEquals("pairingCode=<redacted len 3>&theme=cl", redactLaunchBody("pairingCode=abc&theme=cl"))
        assertEquals("theme=cl&pairingCode=<redacted len 12>", redactLaunchBody("theme=cl&pairingCode=abcdefghijkl"))
        assertEquals("pairingCode=<redacted len 0>", redactLaunchBody("pairingCode="))
        assertEquals("theme=cl&v=2", redactLaunchBody("theme=cl&v=2"))
        assertEquals("", redactLaunchBody(""))
        assertNull(redactLaunchBody(null))
    }

    // ----- HTTP gate --------------------------------------------------------------------------

    private fun gateTest(
        accessPolicy: (String) -> Boolean,
        seen: MutableList<DialRequest> = CopyOnWriteArrayList(),
        onLaunch: (suspend (String, String) -> String?)? = null,
        block: suspend ApplicationTestBuilder.() -> Unit,
    ) = testApplication {
        val cfg = DialConfig("T2U gate", uuid, "127.0.0.1", 3000, accessPolicy = accessPolicy)
        application { DialServer(cfg, { seen.add(it) }, onLaunch).configure(this) }
        block()
    }

    @Test
    fun deniedRequestsGet403OnEveryRouteAndNeverReachTheLaunchCallback() {
        val seen = CopyOnWriteArrayList<DialRequest>()
        val launches = AtomicInteger()
        gateTest({ false }, seen, onLaunch = { _, _ -> launches.incrementAndGet(); "pid" }) {
            assertEquals(HttpStatusCode.Forbidden, client.get("/ytcr/ssdp/device-desc.xml").status)
            assertEquals(HttpStatusCode.Forbidden, client.get("/ytcr/apps/YouTube").status)
            val post = client.post("/ytcr/apps/YouTube") {
                contentType(ContentType.Application.FormUrlEncoded)
                setBody("pairingCode=abc&theme=cl")
            }
            assertEquals(HttpStatusCode.Forbidden, post.status)
            assertNull(post.headers["LOCATION"])
            assertEquals(HttpStatusCode.Forbidden, client.delete("/ytcr/apps/YouTube/x").status)
        }
        assertEquals(0, launches.get(), "launch callback must not run")
        assertEquals(listOf(403, 403, 403, 403), seen.map { it.status })
        assertTrue(seen.all { it.body == null }, "a denied body is never read")
    }

    @Test
    fun allowedRequestsGetThePhase1Answers() {
        gateTest({ true }) {
            assertEquals(HttpStatusCode.OK, client.get("/ytcr/ssdp/device-desc.xml").status)
            assertEquals(HttpStatusCode.OK, client.get("/ytcr/apps/YouTube").status)
            val post = client.post("/ytcr/apps/YouTube") { setBody("pairingCode=abc") }
            assertEquals(HttpStatusCode.OK, post.status)
            assertNotNull(post.headers["LOCATION"])
            assertEquals(HttpStatusCode.MethodNotAllowed, client.delete("/ytcr/apps/YouTube/x").status)
        }
    }

    @Test
    fun theGateReceivesTheRemoteAddressString() {
        val addresses = CopyOnWriteArrayList<String>()
        gateTest({ addresses.add(it); true }) {
            client.get("/ytcr/apps")
        }
        assertEquals(1, addresses.size)
        assertTrue(addresses.single().isNotBlank(), "remote address passed to the policy")
    }

    // ----- launch hook ------------------------------------------------------------------------

    @Test
    fun onLaunchPidBecomesTheLocation() {
        val calls = CopyOnWriteArrayList<Pair<String, String>>()
        gateTest({ true }, onLaunch = { app, body -> calls.add(app to body); "pid-1" }) {
            val r = client.post("/ytcr/apps/YouTube") {
                header(HttpHeaders.Host, "192.168.50.239:3000")
                setBody("pairingCode=abc&theme=cl")
            }
            assertEquals(HttpStatusCode.OK, r.status)
            assertTrue(r.headers["LOCATION"]!!.endsWith("/apps/YouTube/pid-1"), r.headers["LOCATION"])
        }
        // the callback gets the real body: the receiver needs the pairing code
        assertEquals(listOf("YouTube" to "pairingCode=abc&theme=cl"), calls.toList())
    }

    @Test
    fun onLaunchNullOrEmptyPidSendsNoLocation() {
        for (pid in listOf<String?>(null, "")) {
            gateTest({ true }, onLaunch = { _, _ -> pid }) {
                val r = client.post("/ytcr/apps/YouTube") { setBody("pairingCode=abc") }
                assertEquals(HttpStatusCode.OK, r.status)
                assertNull(r.headers["LOCATION"], "pid=$pid")
            }
        }
    }

    @Test
    fun onLaunchThrowingAnswers503() {
        gateTest({ true }, onLaunch = { _, _ -> error("boom") }) {
            val r = client.post("/ytcr/apps/YouTube") { setBody("pairingCode=abc") }
            assertEquals(HttpStatusCode.ServiceUnavailable, r.status)
            assertNull(r.headers["LOCATION"])
        }
    }

    @Test
    fun theReportedBodyHasThePairingCodeRedacted() {
        val seen = CopyOnWriteArrayList<DialRequest>()
        gateTest({ true }, seen, onLaunch = { _, _ -> "pid-1" }) {
            client.post("/ytcr/apps/YouTube") {
                contentType(ContentType.Application.FormUrlEncoded)
                setBody("pairingCode=abc&theme=cl")
            }
        }
        assertEquals("pairingCode=<redacted len 3>&theme=cl", seen.single { it.method == "POST" }.body)
    }

    @Test
    fun theReportedBodyIsRedactedWithoutALaunchCallbackToo() {
        val seen = CopyOnWriteArrayList<DialRequest>()
        gateTest({ true }, seen) {
            client.post("/ytcr/apps/YouTube") { setBody("pairingCode=abcdef&theme=cl") }
        }
        assertEquals("pairingCode=<redacted len 6>&theme=cl", seen.single { it.method == "POST" }.body)
    }

    @Test
    fun aConfiguredPidIsUsedInTheRunLinkAndTheLocation() {
        testApplication {
            val cfg = DialConfig("T2U pid", uuid, "127.0.0.1", 3000, pid = "fixed-pid-7")
            application { DialServer(cfg) {}.configure(this) }
            val app = client.get("/ytcr/apps/YouTube")
            val appBody = app.bodyAsText()
            assertTrue(appBody.contains("fixed-pid-7"), appBody)
            val post = client.post("/ytcr/apps/YouTube") { setBody("x") }
            assertTrue(post.headers["LOCATION"]!!.endsWith("/apps/YouTube/fixed-pid-7"), post.headers["LOCATION"])
        }
    }

    // ----- real sockets -----------------------------------------------------------------------

    @Test
    fun aPhoneOnlyServerAnswersLoopbackAndRefusesTheHostsOtherAddress() {
        val hostIp = NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
        Assumptions.assumeTrue(hostIp != null, "host has no non-loopback IPv4 address")

        val policy = DialAccessPolicy({ DialAccessMode.PHONE }, { emptySet() })
        val cfg = DialConfig("T2U real", uuid, "0.0.0.0", 0, accessPolicy = policy::allowsHttp)
        val server = DialServer(cfg) {}
        server.start()
        try {
            val port = server.resolvedPort()
            val http = HttpClient.newHttpClient()
            fun status(host: String): Int = http.send(
                HttpRequest.newBuilder(URI.create("http://$host:$port/ytcr/ssdp/device-desc.xml")).GET().build(),
                HttpResponse.BodyHandlers.discarding(),
            ).statusCode()
            assertEquals(200, status("127.0.0.1"))
            assertEquals(403, status(hostIp!!.hostAddress))
        } finally {
            server.stop()
        }
    }
}
