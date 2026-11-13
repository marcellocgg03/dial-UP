// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.dialup.app

private val STREAM_TOKEN_PATH = Regex("(/stream/)([0-9a-f]{6})[0-9a-f]{26}")

/**
 * [uri] with the stream token of a `/stream/<token>.<ext>` path cut to its first six characters. The token is the only
 * control over a track that is playing; the log is shared by design, and six characters still tell two tokens apart.
 */
internal fun redactStreamToken(uri: String): String = STREAM_TOKEN_PATH.replace(uri) { "${it.groupValues[1]}${it.groupValues[2]}.." }

/**
 * Lets one log line per key through in each [windowNanos], and says how many were held back since the previous one. At most
 * [maxKeys] keys are remembered; a new key at the limit first drops the expired ones and then the oldest.
 */
internal class SourceLogLimiter(
    private val windowNanos: Long,
    private val maxKeys: Int,
    private val clockNanos: () -> Long = System::nanoTime,
) {
    private class Slot(var lastLogged: Long, var held: Int)

    private val slots = LinkedHashMap<String, Slot>()

    /** Null: hold this one back. Otherwise: log it, [Int] being how many were held back for [key] since the last line. */
    @Synchronized
    fun admit(key: String): Int? {
        val now = clockNanos()
        val slot = slots[key]
        if (slot != null) {
            if (now - slot.lastLogged < windowNanos) {
                slot.held++
                return null
            }
            val held = slot.held
            slots.remove(key)
            slots[key] = Slot(now, 0)
            return held
        }
        if (slots.size >= maxKeys) {
            slots.entries.removeAll { now - it.value.lastLogged >= windowNanos }
            while (slots.size >= maxKeys) slots.remove(slots.keys.first())
        }
        slots[key] = Slot(now, 0)
        return 0
    }
}

/** True for a media request that named no registered token (a scan or a stale link): status 404 with no label and no note. */
internal fun isUnregisteredTokenMiss(status: Int?, label: String?, note: String?): Boolean =
    status == 404 && label == null && note == null
