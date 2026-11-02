package dev.dialup.app

import java.net.Inet4Address
import java.net.NetworkInterface

// Quick 261102-jmg: when the phone is the hotspot provider it has no Wi-Fi client address, so the bridge binds to the
// tether interface instead. Pure selection so the JVM tests cover it. GPL-3.0-only.

/** One network interface with a private IPv4, as [selectTether] sees it. */
data class TetherCandidate(
    val name: String,
    val ipv4: String,
    val up: Boolean,
    val multicast: Boolean,
    val loopback: Boolean,
    val pointToPoint: Boolean,
)

// mobile data, VPN and virtual interfaces are never a LAN the speakers are on
private val NOT_A_LAN_PREFIXES = listOf("rmnet", "ccmni", "wwan", "v4-", "tun", "tap", "ppp", "dummy", "lo", "p2p")

// where Android puts a Wi-Fi hotspot; every other candidate (USB rndis, bluetooth pan, ethernet) ranks after them
private val HOTSPOT_PREFIXES = listOf("ap", "swlan", "softap", "wlan")

/** The tether interface to bind to, or null. Wi-Fi hotspot interfaces win over USB, bluetooth and ethernet. */
fun selectTether(candidates: List<TetherCandidate>): TetherCandidate? =
    candidates
        .filter { it.up && it.multicast && !it.loopback && !it.pointToPoint }
        .filter { c -> NOT_A_LAN_PREFIXES.none { c.name.startsWith(it) } }
        .minByOrNull { c -> if (HOTSPOT_PREFIXES.any { c.name.startsWith(it) }) 0 else 1 }

/** Reads the device's interfaces; each one with a private IPv4 becomes a candidate. Never throws. */
fun tetherCandidates(): List<TetherCandidate> = runCatching {
    NetworkInterface.getNetworkInterfaces().toList().flatMap { nif ->
        val v4 = nif.inetAddresses.toList().filterIsInstance<Inet4Address>().firstOrNull { it.isSiteLocalAddress }
            ?: return@flatMap emptyList()
        listOf(
            TetherCandidate(
                name = nif.name,
                ipv4 = v4.hostAddress ?: return@flatMap emptyList(),
                up = nif.isUp,
                multicast = nif.supportsMulticast(),
                loopback = nif.isLoopback,
                pointToPoint = nif.isPointToPoint,
            ),
        )
    }
}.getOrDefault(emptyList())
