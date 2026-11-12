// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.t2u.renderer

// Adding a speaker by the IPv4 address the author types. Two layers keep the typed text from
// aiming the app at anything but a device on the home network: [parseManualIp] for the text, and
// [manualTargetAllowed] inside Bridge.addManual, so no caller can make the bridge send to a public address.

import dev.t2u.upnp.isPrivateLanAddress
import dev.t2u.upnp.parseIpv4Literal
import java.net.Inet4Address
import java.net.InetAddress
import java.net.URI

/** The UDP port a speaker listens on for SSDP searches. */
const val MANUAL_SSDP_PORT = 1900

/** How often the bridge asks every saved manual speaker again (prune alone takes 45 minutes). */
const val MANUAL_PROBE_INTERVAL_MS = 60_000L

/** How long an add waits for the speaker to answer the probe and its description to load. */
const val MANUAL_ANSWER_TIMEOUT_MS = 5_000L

/** How long an add waits for an answer to the unicast search before it asks for the HTTP description instead. */
const val MANUAL_SSDP_GRACE_MS = 1_500L

/** The HTTP port a Sonos speaker serves its UPnP description on. */
const val MANUAL_DESCRIPTION_PORT = 1400

/** Where a Sonos speaker at [host] serves its device description; [host] is the typed IPv4 literal. */
fun sonosDescriptionUrl(host: String): String = "http://$host:$MANUAL_DESCRIPTION_PORT/xml/device_description.xml"

/**
 * Whether the fallback may request [url] for the typed address [ip]. Only a plain http URL whose host is
 * exactly the typed literal, with no user-info and a real port, passes. The text is parsed with [URI], which never resolves a name.
 */
fun descriptionUrlAllowed(url: String, ip: InetAddress): Boolean {
    val uri = try {
        URI(url)
    } catch (_: Exception) {
        return false
    }
    if (uri.scheme != "http" || uri.userInfo != null) return false
    if (uri.host == null || uri.host != ip.hostAddress) return false
    return uri.port == -1 || uri.port in 1..65535
}

/**
 * The typed text as a private LAN IPv4 address, or null. Only plain dotted decimal is read (no name, no hex or octal or
 * short form, never DNS); a public address, loopback, the any-local address and multicast are refused.
 */
fun parseManualIp(text: String): Inet4Address? {
    val ip = parseIpv4Literal(text.trim()) ?: return null
    if (ip.isLoopbackAddress || ip.isAnyLocalAddress || ip.isMulticastAddress) return null
    return if (isPrivateLanAddress(ip)) ip else null
}

/**
 * The bridge's own guard: an IPv4 address on the private LAN that is not multicast or any-local. Loopback passes here
 * because it is private and the JVM tests run on it; the typed text never gets that far ([parseManualIp] refuses it).
 */
fun manualTargetAllowed(ip: InetAddress): Boolean =
    ip is Inet4Address && !ip.isMulticastAddress && !ip.isAnyLocalAddress && isPrivateLanAddress(ip)

/** What an add by address came to. Nothing is saved except by [ManualSpeakers], and only for Added. */
sealed interface ManualAddResult {
    /** The speaker answered and now has a card. */
    data class Added(val udn: String, val name: String) : ManualAddResult

    /** A speaker at that address already had a card. */
    data class AlreadyListed(val udn: String, val name: String) : ManualAddResult

    /** The speaker answered, but the author hid it. */
    data class Hidden(val udn: String) : ManualAddResult

    /** Nothing answered the probe within the timeout. */
    data object NoAnswer : ManualAddResult

    /** Something answered, but it is not a speaker that can play audio here. */
    data class NotUsable(val reason: String) : ManualAddResult

    /** The address was not a private LAN IPv4 address; nothing was sent. */
    data object Refused : ManualAddResult
}

/** What a probe round made of one saved manual speaker. */
enum class ManualVerdict { ONLINE, MISSED, OFFLINE }

/**
 * The missed-probe counter of one saved manual speaker (one instance per saved UDN). A round in which the speaker
 * replied is [ManualVerdict.ONLINE] and resets the count; [offlineAfter] rounds in a row without a reply make it
 * [ManualVerdict.OFFLINE], and it stays OFFLINE until a reply. Pure: no clock and no network.
 */
class ManualLiveness(private val offlineAfter: Int = 2) {
    private var missed = 0

    fun round(replied: Boolean): ManualVerdict {
        if (replied) {
            missed = 0
            return ManualVerdict.ONLINE
        }
        if (missed < offlineAfter) missed++
        return if (missed >= offlineAfter) ManualVerdict.OFFLINE else ManualVerdict.MISSED
    }
}
