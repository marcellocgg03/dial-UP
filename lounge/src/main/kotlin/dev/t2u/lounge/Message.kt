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

/**
 * A message the receiver sends to the senders. [aid] is the id of the sender command it answers (null when the
 * receiver raised it itself); [payload] keeps the insertion order the Node receiver used, because the order of the
 * form fields on the wire follows it.
 */
data class OutgoingMessage(val aid: Int?, val name: String, val payload: Map<String, Any?>) {
    companion object {
        private fun active(status: PlayerStatus) =
            status == PlayerStatus.PLAYING || status == PlayerStatus.PAUSED || status == PlayerStatus.LOADING

        /** Tells the senders what plays now and its state (Message.NowPlaying). */
        fun nowPlaying(aid: Int?, state: PlayerState?): OutgoingMessage {
            val payload = LinkedHashMap<String, Any?>()
            val current = state?.queue?.current
            if (state != null && current != null) {
                payload["currentTime"] = state.position
                payload["duration"] = state.duration
                payload["cpn"] = state.cpn
                payload["loadedTime"] = 0
                payload["videoId"] = current.id
                payload["state"] = state.senderCode
                payload["seekableStartTime"] = 0
                payload["seekableEndTime"] = state.duration
                val context = current.context
                if (!context?.playlistId.isNullOrEmpty()) payload["listId"] = context.playlistId
                if (context?.index != null) payload["currentIndex"] = context.index
                if (!context?.ctt.isNullOrEmpty()) payload["ctt"] = context.ctt
                if (!context?.params.isNullOrEmpty()) payload["params"] = context.params
            }
            if (state != null && active(state.status)) payload["loadedTime"] = state.duration
            return OutgoingMessage(aid, "nowPlaying", payload)
        }

        /** Tells the senders the current player state (Message.OnStateChange). */
        fun onStateChange(aid: Int?, state: PlayerState): OutgoingMessage {
            val payload = LinkedHashMap<String, Any?>()
            payload["state"] = state.senderCode
            payload["currentTime"] = state.position
            payload["duration"] = state.duration
            payload["loadedTime"] = if (active(state.status)) state.duration else 0
            payload["seekableStartTime"] = 0
            payload["seekableEndTime"] = state.duration
            payload["cpn"] = state.cpn
            return OutgoingMessage(aid, "onStateChange", payload)
        }

        fun onVolumeChanged(aid: Int?, volume: Volume): OutgoingMessage =
            OutgoingMessage(aid, "onVolumeChanged", linkedMapOf("volume" to volume.level, "muted" to volume.muted))

        /** Whether the sender shows, enables or hides its autoplay button; null means unsupported. */
        fun onAutoplayModeChanged(aid: Int?, mode: AutoplayMode?): OutgoingMessage =
            OutgoingMessage(aid, "onAutoplayModeChanged", linkedMapOf("autoplayMode" to (mode ?: AutoplayMode.UNSUPPORTED).name))

        fun onHasPreviousNextChanged(aid: Int?, nav: NavInfo?): OutgoingMessage =
            OutgoingMessage(aid, "onHasPreviousNextChanged", linkedMapOf("hasPrevious" to (nav?.hasPrevious == true), "hasNext" to (nav?.hasNext == true)))

        /** What is queued for autoplay; no video gives an empty payload. */
        fun autoplayUpNext(aid: Int?, videoId: String?): OutgoingMessage =
            OutgoingMessage(aid, "autoplayUpNext", if (videoId.isNullOrEmpty()) linkedMapOf() else linkedMapOf("videoId" to videoId))

        /** Tells the senders the cast session ended. */
        fun loungeScreenDisconnected(): OutgoingMessage = OutgoingMessage(null, "loungeScreenDisconnected", linkedMapOf())
    }
}

/**
 * The form fields of one message POST in the order Session.js getSendMessagePayload builds them:
 * count, ofs, then `req{i}__sc` (double underscore) and `req{i}_<key>` per message. Values print as URLSearchParams
 * prints them (JavaScript number formatting, true/false).
 */
fun sendMessagePostData(messages: List<OutgoingMessage>, ofs: Int): List<Pair<String, String>> {
    val out = ArrayList<Pair<String, String>>()
    out.add("count" to messages.size.toString())
    out.add("ofs" to ofs.toString())
    messages.forEachIndexed { index, message ->
        val prefix = "req${index}_"
        out.add("${prefix}_sc" to message.name)
        for ((key, value) in message.payload) {
            out.add(prefix + key to (value?.let { jsString(it) } ?: "null"))
        }
    }
    return out
}

/**
 * The WHATWG application/x-www-form-urlencoded serializer URLSearchParams uses: alphanumerics and `*-._` stay,
 * a space becomes `+`, everything else is %XX of its UTF-8 bytes.
 */
fun formEncode(pairs: List<Pair<String, String>>): String = pairs.joinToString("&") { (k, v) -> formComponent(k) + "=" + formComponent(v) }

private const val HEX = "0123456789ABCDEF"

private fun formComponent(s: String): String {
    val sb = StringBuilder(s.length + 8)
    for (b in s.toByteArray(Charsets.UTF_8)) {
        val c = b.toInt() and 0xFF
        when {
            (c in 'A'.code..'Z'.code) || (c in 'a'.code..'z'.code) || (c in '0'.code..'9'.code) ||
                c == '*'.code || c == '-'.code || c == '.'.code || c == '_'.code -> sb.append(c.toChar())
            c == ' '.code -> sb.append('+')
            else -> sb.append('%').append(HEX[c shr 4]).append(HEX[c and 0xF])
        }
    }
    return sb.toString()
}
