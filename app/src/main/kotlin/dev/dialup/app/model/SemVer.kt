// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.dialup.app.model

/**
 * A semantic version (semver.org 2.0.0) without build metadata, which never takes part in the order. Pure: it never throws.
 */
data class SemVer(val major: Int, val minor: Int, val patch: Int, val pre: List<String>) : Comparable<SemVer> {

    override fun compareTo(other: SemVer): Int {
        major.compareTo(other.major).let { if (it != 0) return it }
        minor.compareTo(other.minor).let { if (it != 0) return it }
        patch.compareTo(other.patch).let { if (it != 0) return it }
        // A version with a pre-release ranks below the same version without one.
        if (pre.isEmpty() && other.pre.isEmpty()) return 0
        if (pre.isEmpty()) return 1
        if (other.pre.isEmpty()) return -1
        val n = minOf(pre.size, other.pre.size)
        for (i in 0 until n) {
            val c = compareIdentifier(pre[i], other.pre[i])
            if (c != 0) return c
        }
        return pre.size.compareTo(other.pre.size)
    }

    override fun toString(): String =
        "$major.$minor.$patch" + if (pre.isEmpty()) "" else "-" + pre.joinToString(".")

    companion object {
        private val IDENT = Regex("[0-9A-Za-z-]+")

        private fun isNumeric(s: String) = s.all { it in '0'..'9' }

        private fun compareIdentifier(a: String, b: String): Int {
            val an = isNumeric(a)
            val bn = isNumeric(b)
            return when {
                an && bn -> {
                    // Compare by length first so a number too large for Long still orders correctly (no leading zeros).
                    if (a.length != b.length) a.length.compareTo(b.length) else a.compareTo(b)
                }
                an -> -1
                bn -> 1
                else -> a.compareTo(b)
            }
        }

        private fun number(s: String): Int? {
            if (s.isEmpty() || !isNumeric(s)) return null
            if (s.length > 1 && s[0] == '0') return null
            return s.toIntOrNull()
        }

        /** The version in [text] (one leading v or V and build metadata after '+' are tolerated), or null when it is not semantic. */
        fun parse(text: String): SemVer? {
            var t = text
            if (t.startsWith("v") || t.startsWith("V")) t = t.substring(1)
            val plus = t.indexOf('+')
            if (plus >= 0) t = t.substring(0, plus)
            val dash = t.indexOf('-')
            val core = if (dash >= 0) t.substring(0, dash) else t
            val parts = core.split('.')
            if (parts.size != 3) return null
            val major = number(parts[0]) ?: return null
            val minor = number(parts[1]) ?: return null
            val patch = number(parts[2]) ?: return null
            val pre = if (dash >= 0) {
                val ids = t.substring(dash + 1).split('.')
                for (id in ids) {
                    if (!IDENT.matches(id)) return null
                    if (isNumeric(id) && id.length > 1 && id[0] == '0') return null
                }
                ids
            } else {
                emptyList()
            }
            return SemVer(major, minor, patch, pre)
        }
    }
}
