package dev.t2u.renderer

// Port of tube2upnp src/net.js, GPL-3.0-only.

import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Which of our addresses does the OS use to reach [targetIp]? Connecting a UDP socket sends nothing; it
 * only runs the routing table lookup. This picks the right interface on a multi-homed phone (Wi-Fi next
 * to mobile data), where a hostname is useless because speakers cannot resolve it.
 *
 * [targetIp] must be an IP literal (the SSDP source address): no DNS lookup happens. Blocking but
 * immediate; callers on a coroutine run it on Dispatchers.IO.
 */
fun localAddressFor(targetIp: String): String =
    DatagramSocket().use { socket ->
        socket.connect(InetAddress.getByName(targetIp), 9)
        socket.localAddress.hostAddress ?: throw IllegalStateException("no local address towards $targetIp")
    }
