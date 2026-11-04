// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.dialup.app

import dev.dialup.app.model.FetchResult
import dev.dialup.app.model.UpdateRecord
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UpdateCheckerTest {
    @TempDir
    lateinit var dir: File

    private val servers = mutableListOf<ServerSocket>()
    private val requests = CopyOnWriteArrayList<String>()

    @AfterEach
    fun stop() {
        servers.forEach { runCatching { it.close() } }
    }

    /** A loopback server that answers each connection with [response] (head text plus an optional body of [bodyBytes]). */
    private fun serve(head: String, body: ByteArray = ByteArray(0)): String {
        val ss = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        servers += ss
        thread(isDaemon = true) {
            while (!ss.isClosed) {
                val s = try { ss.accept() } catch (_: Exception) { return@thread }
                try {
                    val input = s.getInputStream()
                    val sb = StringBuilder()
                    while (!sb.endsWith("\r\n\r\n")) {
                        val c = input.read()
                        if (c < 0) break
                        sb.append(c.toChar())
                    }
                    requests += sb.toString()
                    val out = s.getOutputStream()
                    out.write((head + "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray())
                    out.write(body)
                    out.flush()
                } catch (_: Exception) {
                } finally {
                    runCatching { s.close() }
                }
            }
        }
        return "http://127.0.0.1:${ss.localPort}/repos/x/releases?per_page=30"
    }

    private fun fetch(url: String) = fetchReleases(githubReleasesClient(), url, userAgentFor("0.9.0-beta.1"))

    @Test
    fun a200AnswerIsTheBodyAndTheRequestCarriesTheHeaders() {
        val url = serve("HTTP/1.1 200 OK\r\n", "[{\"tag_name\":\"v1\"}]".toByteArray())
        val r = fetch(url)
        assertEquals(FetchResult.Body("[{\"tag_name\":\"v1\"}]"), r)
        val req = requests.single()
        assertTrue(req.startsWith("GET /repos/x/releases?per_page=30 HTTP/1.1"), req)
        assertTrue(req.contains("User-Agent: DialUP/0.9.0-beta.1\r\n"), req)
        assertTrue(req.contains("Accept: application/vnd.github+json\r\n"), req)
    }

    @Test
    fun rateLimitingIsToldApartFromOtherErrors() {
        assertEquals(FetchResult.RateLimited, fetch(serve("HTTP/1.1 403 Forbidden\r\nx-ratelimit-remaining: 0\r\n")))
        assertEquals(FetchResult.RateLimited, fetch(serve("HTTP/1.1 429 Too Many Requests\r\nretry-after: 60\r\n")))
        assertEquals(FetchResult.Http(403), fetch(serve("HTTP/1.1 403 Forbidden\r\n")))
        assertEquals(FetchResult.Http(500), fetch(serve("HTTP/1.1 500 Internal Server Error\r\n")))
    }

    @Test
    fun aRedirectIsNotFollowed() {
        val url = serve("HTTP/1.1 302 Found\r\nLocation: http://127.0.0.1:1/elsewhere\r\n")
        assertEquals(FetchResult.Http(302), fetch(url))
        assertEquals(1, requests.size)
    }

    @Test
    fun aBodyOverOneMebibyteIsTooLarge() {
        val url = serve("HTTP/1.1 200 OK\r\n", ByteArray((MAX_RELEASES_BODY + 1).toInt()) { 'a'.code.toByte() })
        assertEquals(FetchResult.TooLarge, fetch(url))
    }

    @Test
    fun aClosedPortIsOffline() {
        val port = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        assertEquals(FetchResult.Offline, fetch("http://127.0.0.1:$port/x"))
    }

    @Test
    fun theRecordFileRoundTripsAndToleratesJunk() {
        val file = UpdateRecordFile(dir)
        assertEquals(UpdateRecord(), file.load())
        val r = UpdateRecord(10L, 20L, "v1.0.0", "https://github.com/marcellocgg03/dial-UP/releases/tag/v1.0.0")
        file.save(r)
        assertEquals(r, file.load())
        File(dir, "update-check.json").writeText("not json")
        assertEquals(UpdateRecord(), file.load())
    }
}
