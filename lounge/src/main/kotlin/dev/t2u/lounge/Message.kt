package dev.t2u.lounge

// Port of the outgoing message classes of yt-cast-receiver 2.1.0 lib/app/Message.js and the payload
// encoding of lib/app/Session.js (MIT, Copyright (c) Patrick Kan).

import java.math.BigDecimal

/**
 * JavaScript `String(number)`: an integral value prints without ".0", no exponent between 1e-6 and 1e21.
 * Bind query values and message payload numbers must look exactly as the Node receiver printed them.
 */
fun jsNumber(d: Double): String {
    if (d.isNaN()) return "NaN"
    if (d.isInfinite()) return if (d > 0) "Infinity" else "-Infinity"
    if (d == 0.0) return "0"
    val abs = Math.abs(d)
    if (abs >= 1e21 || abs < 1e-6) {
        // JavaScript: 1e+21, 1.5e-7
        val s = d.toString() // e.g. 1.0E21, 1.5E-7
        val mantissa = s.substringBefore('E').let { if (it.endsWith(".0")) it.dropLast(2) else it }
        val exp = s.substringAfter('E').toInt()
        return mantissa + "e" + (if (exp >= 0) "+" else "-") + Math.abs(exp)
    }
    return BigDecimal(d.toString()).stripTrailingZeros().toPlainString()
}

/** `String(value)` as URLSearchParams and query-string apply it to a value. */
internal fun jsString(v: Any): String = when (v) {
    is Double -> jsNumber(v)
    is Float -> jsNumber(v.toString().toDouble())
    else -> v.toString()
}
