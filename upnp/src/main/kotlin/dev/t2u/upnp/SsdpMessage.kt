package dev.t2u.upnp

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One parsed SSDP datagram: the start line plus headers keyed UPPERCASE with letters only. */
data class SsdpMessage(val startLine: String, val headers: Map<String, String>)

object SsdpConstants {
    const val GROUP = "239.255.255.250"
    const val PORT = 1900

    /** peer-dial.js:43 serviceTypes, in the same order. */
    fun dialServiceTypes(uuid: String): List<String> = listOf(
        "urn:dial-multiscreen-org:service:dial:1",
        "urn:dial-multiscreen-org:device:dial:1",
        "upnp:rootdevice",
        "ssdp:all",
        "uuid:$uuid",
    )
}

/**
 * Port of ssdp.js parseMessage: UTF-8, split on CRLF or LF, header key upper-cased with every
 * non-letter removed (so CONFIGID.UPNP.ORG becomes CONFIGIDUPNPORG), value trimmed, lines without a
 * colon skipped.
 */
fun parseMessage(bytes: ByteArray, length: Int = bytes.size): SsdpMessage {
    val text = String(bytes, 0, length, Charsets.UTF_8)
    val lines = text.split("\r\n", "\n").toMutableList()
    val startLine = (if (lines.isEmpty()) "" else lines.removeAt(0)).trim()
    val headers = LinkedHashMap<String, String>()
    for (line in lines) {
        val i = line.indexOf(':')
        if (i <= 0) continue
        val key = buildString {
            for (c in line.substring(0, i).uppercase(Locale.ROOT)) if (c in 'A'..'Z') append(c)
        }
        if (key.isNotEmpty()) headers[key] = line.substring(i + 1).trim()
    }
    return SsdpMessage(startLine, headers)
}

/** HTTP date as Node's Date#toUTCString prints it: zero-padded day, always GMT. */
private val HTTP_DATE: DateTimeFormatter =
    DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).withZone(ZoneOffset.UTC)

fun httpDate(now: Instant = Instant.now()): String = HTTP_DATE.format(now)

private const val CRLF = "\r\n"

/**
 * Unicast M-SEARCH reply in the exact order peer-ssdp writes it (golden: ssdp-reply-dial.txt):
 * LOCATION, ST, CONFIGID, BOOTID, SERVER, USN, then HOST, CACHE-CONTROL, EXT, DATE, blank line.
 */
fun buildSearchReply(location: String, st: String, server: String, usn: String, date: String): ByteArray =
    buildString {
        append("HTTP/1.1 200 OK").append(CRLF)
        append("LOCATION: ").append(location).append(CRLF)
        append("ST: ").append(st).append(CRLF)
        append("CONFIGID.UPNP.ORG: 7337").append(CRLF)
        append("BOOTID.UPNP.ORG: 7337").append(CRLF)
        append("SERVER: ").append(server).append(CRLF)
        append("USN: ").append(usn).append(CRLF)
        append("HOST: ").append(SsdpConstants.GROUP).append(':').append(SsdpConstants.PORT).append(CRLF)
        append("CACHE-CONTROL: max-age=1800").append(CRLF)
        append("EXT: ").append(CRLF)
        append("DATE: ").append(date).append(CRLF)
        append(CRLF)
    }.toByteArray(Charsets.UTF_8)
