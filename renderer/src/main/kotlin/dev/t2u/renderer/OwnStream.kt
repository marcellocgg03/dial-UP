package dev.t2u.renderer

// The classifier for "is this transport URI one of this app's own streams", GPL-3.0-only.

/**
 * True when [uri] is one of this app's own stream URLs: an optional scheme, then one of [hosts], a colon, [port] and the
 * path prefix `/stream/`, in that order and from the start of the string.
 *
 * The match is anchored at the start. The old test looked for the text anywhere in the string, so a foreign URL that
 * carried ours in a query string (`http://other/x?u=http://192.168.50.7:8000/stream/a`) or behind userinfo
 * (`http://192.168.50.7:8000@evil.example/stream/x`) counted as ours and the takeover and clear logic was misapplied.
 *
 * The scheme is accepted whatever it is, or absent: a Sonos speaker reports a stream it was given as
 * `http://` back as `x-rincon-mp3radio://`, and that must still match.
 */
internal fun isOwnStreamUri(uri: String, hosts: Collection<String>, port: Int): Boolean {
    if (uri.isEmpty() || hosts.isEmpty()) return false
    val alternatives = hosts.joinToString("|") { Regex.escape(it) }
    return Regex("^(?:[A-Za-z][A-Za-z0-9+.-]*://)?(?:$alternatives):$port/stream/").containsMatchIn(uri)
}
