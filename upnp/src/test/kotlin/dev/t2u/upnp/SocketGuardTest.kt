package dev.t2u.upnp

import org.junit.jupiter.api.Test
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketAddress
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** WR-01: a multicast socket whose setup throws is closed before the error is reported. */
class SocketGuardTest {
    private val loopback = InetAddress.getByName("127.0.0.1")

    @Test
    fun failingSetupRethrowsTheSameExceptionAndClosesTheSocket() {
        val sock = MulticastSocket(InetSocketAddress(loopback, 0))
        val boom = IllegalStateException("boom")
        val thrown = assertFailsWith<IllegalStateException> { sock.setupOrClose { throw boom } }
        assertSame(boom, thrown)
        assertTrue(sock.isClosed)
    }

    @Test
    fun successfulSetupReturnsTheValueAndLeavesTheSocketOpen() {
        val sock = MulticastSocket(InetSocketAddress(loopback, 0))
        try {
            assertEquals(42, sock.setupOrClose { 42 })
            assertFalse(sock.isClosed)
        } finally {
            sock.close()
        }
    }

    @Test
    fun failedSetupReleasesABoundPort() {
        val sock = MulticastSocket(null as SocketAddress?)
        sock.reuseAddress = true
        var port = -1
        assertFailsWith<IllegalStateException> {
            sock.setupOrClose {
                bind(InetSocketAddress(loopback, 0))
                port = localPort
                error("join failed")
            }
        }
        assertTrue(port > 0)
        // No reuseAddress on the plain socket: this only binds if the first socket really let go of the port.
        DatagramSocket(null as SocketAddress?).use { plain ->
            plain.reuseAddress = false
            plain.bind(InetSocketAddress(loopback, port))
            assertEquals(port, plain.localPort)
        }
    }

    private class FailingInterfaceSocket(addr: SocketAddress) : MulticastSocket(addr) {
        var closeCalls = 0

        override fun setNetworkInterface(netIf: NetworkInterface?) = throw java.net.SocketException("no such interface")

        override fun close() {
            closeCalls++
            super.close()
        }
    }

    @Test
    fun replySocketIsClosedWhenTheInterfaceAssignmentFails() {
        val iface = NetworkInterface.getNetworkInterfaces().toList().first()
        var created: FailingInterfaceSocket? = null
        assertFailsWith<java.net.SocketException> {
            defaultReplySocket(loopback, iface) { addr ->
                FailingInterfaceSocket(addr).also { created = it }
            }
        }
        val sock = created!!
        assertTrue(sock.isClosed)
        assertTrue(sock.closeCalls >= 1)
    }
}
