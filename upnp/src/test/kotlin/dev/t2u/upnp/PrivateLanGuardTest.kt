package dev.t2u.upnp

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.serverConfig
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EngineConnectorBuilder
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.InetAddress
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** SVC-05: plain HTTP may only reach private LAN IP literals; redirects are never followed. */
class PrivateLanGuardTest {
    private fun allowed(host: String) = isPrivateLanAddress(InetAddress.getByName(host))

    @Test
    fun `parseIpv4Literal reads plain dotted decimal only`() {
        assertEquals(InetAddress.getByAddress(byteArrayOf(192.toByte(), 168.toByte(), 1, 40)), parseIpv4Literal("192.168.1.40"))
        assertEquals("0.0.0.0", parseIpv4Literal("0.0.0.0")?.hostAddress)
        for (bad in listOf("192.168.001.40", "0x7f.0.0.1", "1.2.3", "1.2.3.4.5", "256.1.1.1", "speaker.local", "", " 1.2.3.4", "1.2.3.-4", "::1")) {
            assertNull(parseIpv4Literal(bad), "'$bad' must not parse")
        }
    }

    @Test
    fun `private ranges are allowed`() {
        for (h in listOf(
            "10.0.0.1", "10.255.255.255", "172.16.0.1", "172.31.255.255", "192.168.0.1", "192.168.50.239",
            "127.0.0.1", "127.255.0.9", "169.254.1.1", "::1", "fe80::1", "fc00::1", "fd12:3456::1",
        )) {
            assertTrue(allowed(h), "$h should be allowed")
        }
    }

    @Test
    fun `boundary and public addresses are refused`() {
        for (h in listOf("172.15.255.255", "172.32.0.1", "100.64.0.1", "8.8.8.8", "11.0.0.1", "192.169.0.1", "169.253.1.1", "2001:4860:4860::8888", "fec0::1", "fbff::1")) {
            assertFalse(allowed(h), "$h should be refused")
        }
    }

    @Test
    fun `cleartext to a hostname is refused before any DNS lookup`() {
        val guard = PrivateLanGuard()
        assertFailsWith<IOException> { guard.checkCleartext("http://example.com/".toHttpUrl()) }
        val e = assertFailsWith<IOException> { guard.checkCleartext("http://localhost/".toHttpUrl()) }
        assertTrue(e.message!!.contains("localhost"), e.message)
        // through a real client: the interceptor throws, nothing is resolved or connected
        assertFailsWith<IOException> { lanHttpClient().newCall(Request.Builder().url("http://example.invalid/").build()).execute() }
    }

    @Test
    fun `cleartext to IP literals follows the private-range rule`() {
        val guard = PrivateLanGuard()
        guard.checkCleartext("http://192.168.1.10:1400/x".toHttpUrl())
        guard.checkCleartext("http://[::1]:8000/".toHttpUrl())
        guard.checkCleartext("http://[fe80::1]/".toHttpUrl())
        for (u in listOf("http://8.8.8.8/", "http://172.32.0.1/", "http://[2001:4860:4860::8888]/", "http://0x7f.0.0.1/", "http://010.0.0.1/")) {
            assertFailsWith<IOException>(u) { guard.checkCleartext(u.toHttpUrl()) }
        }
    }

    @Test
    fun `HTTPS passes the guard unchanged`() {
        PrivateLanGuard().checkCleartext("https://example.com/".toHttpUrl())
        PrivateLanGuard().checkCleartext("https://8.8.8.8/".toHttpUrl())
    }

    @Test
    fun `a 302 from a private address to a public address is returned unfollowed`() {
        val server = embeddedServer(
            CIO,
            serverConfig {
                module {
                    routing {
                        get("/go") {
                            call.response.header(HttpHeaders.Location, "http://8.8.8.8/evil")
                            call.respond(HttpStatusCode.Found)
                        }
                        get("/ok") { call.respondText("fine") }
                    }
                }
            },
        ) {
            connectors.add(EngineConnectorBuilder().apply { host = "127.0.0.1"; port = 0 })
        }
        server.start(wait = false)
        try {
            val port = runBlocking { server.engine.resolvedConnectors().first().port }
            val client = lanHttpClient()
            client.newCall(Request.Builder().url("http://127.0.0.1:$port/go").build()).execute().use { r ->
                assertEquals(302, r.code)
                assertEquals("http://8.8.8.8/evil", r.header("Location"))
            }
            client.newCall(Request.Builder().url("http://127.0.0.1:$port/ok").build()).execute().use { r ->
                assertEquals("fine", r.body.string())
            }
            assertFalse(client.followRedirects)
            assertFalse(client.followSslRedirects)
        } finally {
            server.stop(100, 500)
        }
    }
}
