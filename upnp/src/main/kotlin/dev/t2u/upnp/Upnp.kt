package dev.t2u.upnp

// Port of tube2upnp src/upnp.js, GPL-3.0-only.
//
// UPnP helpers that upnp-device-client gets wrong:
//  * it only reads the ROOT device's serviceList, so renderers that are embedded devices (Sonos,
//    Denon/Marantz, Yamaha, many AVRs) fail with "Service urn:upnp-org:serviceId:AVTransport not
//    provided by device";
//  * it requires the exact serviceId "urn:upnp-org:serviceId:X", which some vendors spell differently.
// MIME_ALIASES lives here (not in :media) because sinkAccepts needs it and :upnp does not depend on
// :media; PARITY.md records the split from formats.js.

import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit

data class DeviceDescription(
    val deviceType: String,
    val friendlyName: String,
    val manufacturer: String,
    val modelName: String,
    val modelNumber: String,
    val modelDescription: String,
    val udn: String,
    /** Keyed "urn:upnp-org:serviceId:<last serviceId segment>", for example ...:AVTransport. */
    val services: Map<String, UpnpService>,
)

private val MEDIA_RENDERER = Regex("urn:schemas-upnp-org:device:MediaRenderer:\\d")

private fun local(e: Element): String = e.tagName().substringAfterLast(':')

private fun Element.kids(name: String): List<Element> = children().filter { local(it) == name }

private fun Element.childText(name: String): String = kids(name).firstOrNull()?.text()?.trim() ?: ""

/** Breadth-first from the root device; the first device whose deviceType is a MediaRenderer wins. */
private fun findRendererDevice(rootDevice: Element?): Element? {
    val queue = ArrayDeque<Element>()
    if (rootDevice != null) queue.add(rootDevice)
    while (queue.isNotEmpty()) {
        val dev = queue.removeFirst()
        if (MEDIA_RENDERER.containsMatchIn(dev.childText("deviceType"))) return dev
        for (list in dev.kids("deviceList")) queue.addAll(list.kids("device"))
    }
    return null
}

/** Parses a device description XML into the shape the renderer needs; embedded Sonos renderers included. */
fun parseDescription(xml: String, location: String): DeviceDescription {
    val doc = Jsoup.parse(xml, "", Parser.xmlParser())
    val root = doc.children().firstOrNull() ?: throw IllegalArgumentException("No <device> element in description")
    val base = root.childText("URLBase").ifEmpty { location }
    val rootDevice = root.kids("device").firstOrNull()
    val dev = findRendererDevice(rootDevice) ?: rootDevice
        ?: throw IllegalArgumentException("No <device> element in description")

    fun abs(u: String): String {
        if (u.isEmpty()) return ""
        return try {
            URI(base).resolve(u).toString()
        } catch (_: Exception) {
            u
        }
    }

    val services = LinkedHashMap<String, UpnpService>()
    for (list in dev.kids("serviceList")) {
        for (s in list.kids("service")) {
            val short = s.childText("serviceId").substringAfterLast(':')
            services["urn:upnp-org:serviceId:$short"] = UpnpService(
                serviceType = s.childText("serviceType"),
                scpdURL = abs(s.childText("SCPDURL")),
                controlURL = abs(s.childText("controlURL")),
                eventSubURL = abs(s.childText("eventSubURL")),
            )
        }
    }

    return DeviceDescription(
        deviceType = dev.childText("deviceType"),
        friendlyName = dev.childText("friendlyName").ifEmpty { "Unknown renderer" },
        manufacturer = dev.childText("manufacturer"),
        modelName = dev.childText("modelName"),
        modelNumber = dev.childText("modelNumber"),
        modelDescription = dev.childText("modelDescription"),
        udn = dev.childText("UDN"),
        services = services,
    )
}

/** GETs and parses a description; a non-2xx answer throws [IOException] "HTTP <code> fetching <location>". */
suspend fun fetchDescription(http: OkHttpClient, location: String, timeoutMs: Long = 5000): DeviceDescription {
    val call = http.newCall(Request.Builder().url(location).build())
    call.timeout().timeout(timeoutMs, TimeUnit.MILLISECONDS)
    val (status, text) = executeForText(call)
    if (status !in 200..299) throw IOException("HTTP $status fetching $location")
    return parseDescription(text, location)
}

fun xmlEsc(s: String): String {
    val sb = StringBuilder(s.length + 16)
    for (c in s) {
        when (c) {
            '<' -> sb.append("&lt;")
            '>' -> sb.append("&gt;")
            '&' -> sb.append("&amp;")
            '"' -> sb.append("&quot;")
            '\'' -> sb.append("&apos;")
            else -> sb.append(c)
        }
    }
    return sb.toString()
}

/**
 * Drops code points outside the XML 1.0 Char production: controls below 0x20 except tab, LF and CR,
 * U+FFFE, U+FFFF and unpaired surrogates (RESEARCH Pitfall 10). A single such character in a title
 * makes the whole DIDL, and so the whole SetAVTransportURI, unparseable on the speaker.
 */
fun stripInvalidXmlChars(s: String): String {
    val sb = StringBuilder(s.length)
    var i = 0
    while (i < s.length) {
        val cp = s.codePointAt(i)
        val n = Character.charCount(cp)
        val ok = cp == 0x9 || cp == 0xA || cp == 0xD ||
            cp in 0x20..0xD7FF || cp in 0xE000..0xFFFD || cp in 0x10000..0x10FFFF
        if (ok) sb.append(s, i, i + n)
        i += n
    }
    return sb.toString()
}

private fun esc(s: String): String = xmlEsc(stripInvalidXmlChars(s))

/** H:MM:SS with unpadded hours, floored, never negative (the DIDL duration form; Seek uses HH:MM:SS elsewhere). */
fun didlHms(sec: Double): String {
    val s = maxOf(0L, Math.floor(sec).toLong())
    return "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60)
}

data class DidlItem(
    val url: String,
    val protocolInfo: String,
    val title: String,
    val artist: String = "",
    val album: String = "",
    val albumArt: String = "",
    val duration: Double = 0.0,
    val isVideo: Boolean = false,
)

/** DIDL-Lite for SetAVTransportURI, with title/artist/cover so renderer displays show them. */
fun buildDidl(item: DidlItem): String {
    val cls = if (item.isVideo) "object.item.videoItem" else "object.item.audioItem.musicTrack"
    return listOf(
        "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\"",
        " xmlns:dc=\"http://purl.org/dc/elements/1.1/\"",
        " xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">",
        "<item id=\"0\" parentID=\"-1\" restricted=\"1\">",
        "<dc:title>${esc(item.title)}</dc:title>",
        if (item.artist.isNotEmpty()) "<dc:creator>${esc(item.artist)}</dc:creator><upnp:artist>${esc(item.artist)}</upnp:artist>" else "",
        if (item.album.isNotEmpty()) "<upnp:album>${esc(item.album)}</upnp:album>" else "",
        if (item.albumArt.isNotEmpty()) "<upnp:albumArtURI>${esc(item.albumArt)}</upnp:albumArtURI>" else "",
        "<upnp:class>$cls</upnp:class>",
        "<res protocolInfo=\"${esc(item.protocolInfo)}\"" +
            (if (item.duration != 0.0) " duration=\"${didlHms(item.duration)}.000\"" else "") +
            ">${esc(item.url)}</res>",
        "</item></DIDL-Lite>",
    ).joinToString("")
}

/** What a DIDL-Lite string says about its first item; every field is "" when the element is absent. */
data class DidlSummary(
    val title: String,
    val creator: String,
    val artist: String,
    val album: String,
    val albumArtUri: String,
    val upnpClass: String,
    val streamContent: String,
)

private const val DIDL_MAX_CHARS = 65_536

/**
 * Reads the first `item` of a DIDL-Lite string, ours or a speaker's (plan 02-15 evidence: what we sent and
 * what the speaker keeps). The text is untrusted LAN input: it is cut at 65,536 characters, parsed with
 * jsoup's XML parser (no DTD or entity resolution) and never throws. Null for null, blank, NOT_IMPLEMENTED,
 * a parse failure or a document without an item. Callers log lengths and flags only, never the text.
 */
fun summarizeDidl(xml: String?): DidlSummary? {
    if (xml.isNullOrBlank() || xml.contains("NOT_IMPLEMENTED", ignoreCase = true)) return null
    return try {
        val doc = Jsoup.parse(xml.take(DIDL_MAX_CHARS), "", Parser.xmlParser())
        val item = doc.allElements.firstOrNull { local(it) == "item" } ?: return null
        DidlSummary(
            title = item.childText("title"),
            creator = item.childText("creator"),
            artist = item.childText("artist"),
            album = item.childText("album"),
            albumArtUri = item.childText("albumArtURI"),
            upnpClass = item.childText("class"),
            streamContent = item.childText("streamContent"),
        )
    } catch (_: Exception) {
        null
    }
}

/** Alternative content-format spellings per MIME type, from formats.js. */
val MIME_ALIASES: Map<String, List<String>> = mapOf(
    "audio/mp4" to listOf("audio/m4a", "audio/x-m4a", "audio/mp4a-latm"),
    "audio/aac" to listOf("audio/aacp", "audio/x-aac", "audio/aac-adts", "audio/vnd.dlna.adts", "audio/mp4a-latm"),
    "audio/mpeg" to listOf("audio/mp3", "audio/x-mpeg"),
    "audio/flac" to listOf("audio/x-flac"),
)

/**
 * Does the renderer's GetProtocolInfo sink list accept this MIME type?
 * Returns null if unknown (no list), so callers can fall back to trying.
 */
fun sinkAccepts(protocols: List<SinkProtocol>?, mime: String): Boolean? {
    if (protocols.isNullOrEmpty()) return null
    val type = mime.substringBefore('/')
    val names = listOf(mime) + (MIME_ALIASES[mime] ?: emptyList())
    return protocols.any { p ->
        val cf = p.contentFormat.lowercase()
        p.protocol == "http-get" && (cf == "*" || cf == "$type/*" || cf in names)
    }
}
