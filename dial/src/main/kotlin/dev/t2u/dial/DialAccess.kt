package dev.t2u.dial

import dev.t2u.upnp.isPrivateLanAddress
import dev.t2u.upnp.parseIpv4Literal
import java.net.Inet4Address
import java.net.InetAddress
import java.net.URLDecoder

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
 * The one access decision shared by the DIAL HTTP gate and the SSDP responder: [allowsHttp] and [allowsSsdp] both end in the
 * same private decision on an address, so they accept and refuse the same hosts. Loopback and the phone's own addresses are
 * served first, in both modes, also when the Wi-Fi gives the phone a public address. In [DialAccessMode.PHONE] nothing else is
 * served; in [DialAccessMode.LAN] a private LAN address on the known Wi-Fi subnet is. The refusal answer is DialServer's 403.
 * [mode] and [phoneAddresses] are read on every call, so a mode change applies to the next request.
 */
class DialAccessPolicy(
    private val mode: () -> DialAccessMode,
    private val phoneAddresses: () -> Set<String>,
    /**
     * The Wi-Fi address and its prefix length, read per call, or null when it is not known. When known, [allowsSsdp] in LAN
     * mode also wants an IPv4 source to be on that subnet.
     */
    private val lanSubnet: () -> Pair<InetAddress, Int>? = { null },
) {
    /**
     * True when a request from [remote] (an address string as the HTTP engine reports it) may be served: the same decision
     * as [allowsSsdp]. A [remote] that is not an IP literal is refused, and no name lookup is ever made.
     */
    fun allowsHttp(remote: String): Boolean {
        val address = parseRemote(normalise(remote)) ?: return false
        return decide(address)
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

    /**
     * True when an SSDP datagram from [address] may be answered: the same decision as [allowsHttp]. Unlike HTTP (bound to the
     * Wi-Fi address only) the SSDP socket listens on every interface, so in [DialAccessMode.LAN] the source must be a private
     * LAN address, and when the Wi-Fi subnet is known ([lanSubnet]) an IPv4 source must be on it, so another private network
     * that reaches the phone (a VPN, a tether) is not answered either. Loopback and the phone's own addresses come first.
     */
    fun allowsSsdp(address: InetAddress): Boolean = decide(address)

    private fun decide(address: InetAddress): Boolean {
        if (address.isLoopbackAddress) return true
        if (phoneAddresses().any { parseRemote(normalise(it)) == address }) return true
        if (mode() != DialAccessMode.LAN) return false
        if (!isPrivateLanAddress(address)) return false
        val subnet = lanSubnet() ?: return true
        if (address !is Inet4Address) return true
        return sameSubnet(address, subnet.first, subnet.second)
    }

    /**
     * An IPv4 dotted quad or an IPv6 literal, never a name: [InetAddress.getByName] is only reached for text holding a colon,
     * which cannot be a host name, so no lookup can happen. Null for anything else.
     */
    private fun parseRemote(text: String): InetAddress? {
        if (text.isEmpty()) return null
        if (!text.contains(':')) return parseIpv4Literal(text)
        return try {
            InetAddress.getByName(text)
        } catch (_: Exception) {
            null
        }
    }

    private fun sameSubnet(a: InetAddress, b: InetAddress, prefix: Int): Boolean {
        val x = a.address
        val y = b.address
        if (x.size != y.size || prefix !in 0..x.size * 8) return false
        var bits = prefix
        for (i in x.indices) {
            if (bits <= 0) break
            val mask = if (bits >= 8) 0xFF else (0xFF shl (8 - bits)) and 0xFF
            if ((x[i].toInt() and mask) != (y[i].toInt() and mask)) return false
            bits -= 8
        }
        return true
    }

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

private val REDACTION_MARKER = Regex("^<redacted len \\d+>$")

/**
 * [field] (one `name=value` pair of a form body) with the value of a `pairingCode` field replaced by `<redacted len N>`, where N
 * is the length of the value as sent. The name is percent-decoded before the comparison, the way the receiver decodes it
 * (`YouTubeApp.parseForm`, a malformed escape keeps the raw name), so a spelling the receiver accepts cannot slip through. The raw
 * name is kept in the output. A value that is already a marker is left alone, which makes the redaction idempotent. Any other
 * field, and a field without '=', is returned byte-identical.
 */
private fun redactField(field: String): String {
    val eq = field.indexOf('=')
    if (eq < 0) return field
    val rawName = field.substring(0, eq)
    val value = field.substring(eq + 1)
    val name = try {
        URLDecoder.decode(rawName, "UTF-8")
    } catch (_: IllegalArgumentException) {
        rawName
    }
    if (name != "pairingCode" || REDACTION_MARKER.matches(value)) return field
    return "$rawName=<redacted len ${value.length}>"
}

private fun redactForm(form: String): String = form.split('&').joinToString("&") { redactField(it) }

/**
 * Replaces the value of a `pairingCode` form field with `<redacted len N>`; other fields are untouched. Field names are decoded
 * like the receiver decodes them, and running it on its own output changes nothing, so a second pass cannot change the logged
 * length (N is the length of the code as sent).
 */
fun redactLaunchBody(body: String?): String? = body?.let { redactForm(it) }

/** Stub (RED): the real redaction follows in the next commit. */
fun redactLaunchUri(uri: String): String = uri
