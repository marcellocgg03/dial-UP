package dev.t2u.upnp

// Cleartext HTTP may only reach private LAN addresses. Sonos and DIAL traffic is plain HTTP to
// private IPs (the manifest allows cleartext app-wide because the network-security-config cannot
// express CIDR ranges), so this guard is what stops a malicious LOCATION header or redirect from
// pointing the app at a public host or a name.

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * True for 10/8, 172.16/12, 192.168/16, 127/8, 169.254/16 (IPv4) and ::1, fe80::/10, fc00::/7 (IPv6).
 * Deprecated IPv6 site-local fec0::/10 is deliberately not accepted.
 */
fun isPrivateLanAddress(a: InetAddress): Boolean = when (a) {
    is Inet4Address -> a.isLoopbackAddress || a.isSiteLocalAddress || a.isLinkLocalAddress
    is Inet6Address -> a.isLoopbackAddress || a.isLinkLocalAddress || (a.address[0].toInt() and 0xFE) == 0xFC
    else -> false
}

/**
 * Plain dotted-decimal IPv4 only (so a name or an odd spelling of an address is refused): four parts, each 0..255 in
 * decimal with no leading zero, no hex or octal
 * spelling, no empty part, no whitespace. Built with getByAddress, so DNS is never consulted. Null for anything else.
 */
fun parseIpv4Literal(text: String): Inet4Address? {
    val parts = text.split('.')
    if (parts.size != 4) return null
    val bytes = ByteArray(4)
    for ((i, p) in parts.withIndex()) {
        // plain decimal only: no hex, no octal-looking leading zeros, no empty parts
        if (p.isEmpty() || p.length > 3 || !p.all { it in '0'..'9' } || (p.length > 1 && p[0] == '0')) return null
        val v = p.toInt()
        if (v > 255) return null
        bytes[i] = v.toByte()
    }
    return InetAddress.getByAddress(bytes) as? Inet4Address
}

/**
 * Application interceptor (it runs before DNS and before any connect). Plain-HTTP requests must name
 * an IP literal that [isPrivateLanAddress] accepts; names (including localhost) are refused without a
 * lookup. HTTPS passes unchanged. The guard holds no state, so concurrent calls cannot influence each other.
 * Redirects are not followed by [lanHttpClient], so a 30x to another host never reaches this check
 * unnoticed: the caller sees the 30x itself.
 */
class PrivateLanGuard : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        checkCleartext(chain.request().url)
        return chain.proceed(chain.request())
    }

    /** Throws [IOException] naming the host and the reason when [url] is cleartext to a non-private target. */
    fun checkCleartext(url: HttpUrl) {
        if (url.isHttps) return
        val host = url.host
        val address = parseIpLiteral(host)
            ?: throw IOException("cleartext HTTP to '$host' refused: not an IP literal (SVC-05)")
        if (!isPrivateLanAddress(address)) {
            throw IOException("cleartext HTTP to '$host' refused: not a private LAN address (SVC-05)")
        }
    }

    /** Parses an IPv4 dotted quad or IPv6 literal without ever consulting DNS; null for anything else. */
    private fun parseIpLiteral(host: String): InetAddress? {
        if (host.contains(':')) {
            // A name cannot contain ':', so getByName treats this as a literal and does no lookup.
            return try {
                InetAddress.getByName(host)
            } catch (_: Exception) {
                null
            }
        }
        return parseIpv4Literal(host)
    }
}

/**
 * The only way the app builds an HTTP client: every other OkHttpClient is derived from this
 * one with newBuilder(), so the guard and the no-redirect rule are inherited.
 */
fun lanHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .addInterceptor(PrivateLanGuard())
    .followRedirects(false)
    .followSslRedirects(false)
    .connectTimeout(5, TimeUnit.SECONDS)
    .build()
