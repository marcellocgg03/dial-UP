package dev.t2u.dial

import java.net.InetAddress

/**
 * Who may reach a DIAL receiver. [PHONE] is the default: only the phone's own
 * apps. [LAN] is the opt-in whole-network mode, which the author must allow.
 */
enum class DialAccessMode(val wire: String) {
    PHONE("phone"),
    LAN("lan"),
    ;

    companion object {
        fun fromWire(s: String?): DialAccessMode? = entries.firstOrNull { it.wire == s }
    }
}

/**
 * The one access decision shared by the DIAL HTTP gate and the SSDP responder. [mode] and
 * [phoneAddresses] are read on every call, so a mode change applies to the next request.
 */
class DialAccessPolicy(
    private val mode: () -> DialAccessMode,
    private val phoneAddresses: () -> Set<String>,
) {
    /** True when a request from [remote] (an address string as the HTTP engine reports it) may be served. */
    fun allowsHttp(remote: String): Boolean {
        if (mode() == DialAccessMode.LAN) return true
        val addr = normalise(remote)
        if (addr.isEmpty()) return false
        if (LOOPBACK_V4.matches(addr) || addr == "::1" || addr == "0:0:0:0:0:0:0:1") return true
        return addr in phoneAddresses().map(::normalise)
    }

    /**
     * True when [remote] is served only because the mode is [DialAccessMode.LAN]: another device on the network, not this
     * phone's own address and not loopback. In [DialAccessMode.PHONE] nobody is a guest (other devices are refused).
     */
    fun isGuest(remote: String): Boolean {
        if (mode() != DialAccessMode.LAN) return false
        val addr = normalise(remote)
        if (addr.isEmpty()) return false
        if (LOOPBACK_V4.matches(addr) || addr == "::1" || addr == "0:0:0:0:0:0:0:1") return false
        return addr !in phoneAddresses().map(::normalise)
    }

    /** True when an SSDP datagram from [address] may be answered. */
    fun allowsSsdp(address: InetAddress): Boolean = allowsHttp(address.hostAddress.orEmpty())

    /** Multicast NOTIFY alive makes every LAN device see the receivers, so it is LAN-only. */
    fun announceAllowed(): Boolean = mode() == DialAccessMode.LAN

    private fun normalise(raw: String): String {
        var a = raw.trim()
        if (a.startsWith("[") && a.endsWith("]")) a = a.substring(1, a.length - 1)
        val zone = a.indexOf('%')
        if (zone >= 0) a = a.substring(0, zone)
        if (a.startsWith("::ffff:", ignoreCase = true) && a.indexOf('.') > 0) a = a.substring(7)
        return a.lowercase()
    }

    private companion object {
        val LOOPBACK_V4 = Regex("^127\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$")
    }
}

/** Replaces the value of a `pairingCode` form field with `<redacted len N>`; other fields are untouched. */
fun redactLaunchBody(body: String?): String? {
    if (body == null) return null
    return body.split('&').joinToString("&") { field ->
        if (field.startsWith("pairingCode=")) "pairingCode=<redacted len ${field.length - "pairingCode=".length}>" else field
    }
}
