package dev.t2u.media

// WR-05 (plan 08-05): isListening is the truth about the socket. isRunning only says the server object was started, so it
// stays true after a rebind whose bind failed. Loopback stands in for Wi-Fi. GPL-3.0-only.

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.BindException
import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MediaServerListeningTest {
    @TempDir lateinit var dir: File

    private var server: MediaServer? = null

    @AfterEach
    fun tearDown() {
        server?.stop()
    }

    @Test
    fun listeningFollowsTheSocketThroughAFailedAndASuccessfulRebind() {
        val s = MediaServer("127.0.0.1", 0, null, {}, File(dir, "buffer").also { it.mkdirs() })
        server = s
        assertFalse(s.isListening(), "not before start")
        s.start()
        assertTrue(s.isListening())
        val port = s.resolvedPort()

        val blocker = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress("127.0.0.2", port))
        }
        blocker.use {
            assertFailsWith<BindException> { s.rebindHost("127.0.0.2") }
            assertFalse(s.isListening(), "a failed bind leaves nothing listening")
            assertTrue(s.isRunning(), "the server object is still there, so the call can be repeated")
        }

        s.rebindHost("127.0.0.2")
        assertTrue(s.isListening())
        assertEquals(port, s.resolvedPort())

        s.stop()
        assertFalse(s.isListening(), "false after stop")
    }
}
