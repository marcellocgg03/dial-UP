package dev.t2u.renderer

// UPnP H:MM:SS time parsing and formatting, GPL-3.0-only.

import java.util.Locale

/** Parses a UPnP H:MM:SS(.fff) time; 0 for anything else, including NOT_IMPLEMENTED (renderer.js `parseHms`). */
fun parseHms(t: String?): Double {
    return parseHmsOrNull(t) ?: 0.0
}

internal fun parseHmsOrNull(t: String?): Double? {
    if (t.isNullOrEmpty() || t.contains("NOT_IMPLEMENTED", ignoreCase = true)) return null
    var total = 0.0
    for (part in t.split(':')) {
        val v = part.trim().toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 } ?: return null
        total = total * 60 + v
    }
    return total.takeIf { it.isFinite() }
}

/** HH:MM:SS with zero-padded hours, floored, never negative: the REL_TIME form of Seek (renderer.js `hms`). */
fun seekHms(sec: Double): String {
    val s = maxOf(0L, Math.floor(sec).toLong())
    return String.format(Locale.ROOT, "%02d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
}
