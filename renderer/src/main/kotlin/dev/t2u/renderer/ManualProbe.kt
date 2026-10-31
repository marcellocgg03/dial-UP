// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for Tube2U[PnP] for Android.
package dev.t2u.renderer

// GUI-04 (plan 07-12, D-06): adding a speaker by the IPv4 address the author types. Two layers keep the typed text from
// aiming the app at anything but a device on the home network (T-07-29): [parseManualIp] for the text, and
// [manualTargetAllowed] inside Bridge.addManual, so no caller can make the bridge send to a public address.

import dev.t2u.upnp.isPrivateLanAddress
import dev.t2u.upnp.parseIpv4Literal
import java.net.Inet4Address
import java.net.InetAddress

/** The UDP port a speaker listens on for SSDP searches. */
const val MANUAL_SSDP_PORT = 1900

/** How long an add waits for the speaker to answer the probe and its description to load. */
const val MANUAL_ANSWER_TIMEOUT_MS = 5_000L

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

/** What an add by address came to. Nothing is saved in any case except by plan 07-13, and only for Added or AlreadyListed. */
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
