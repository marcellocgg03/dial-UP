package dev.t2u.app

import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors

/**
 * Pure formatter for one evidence line (no android.* so it runs in a plain JVM unit test).
 *
 * Evidence text comes partly from untrusted LAN peers (SSDP and HTTP requests), so every line is one
 * JSON object with escaped control characters, string values capped at [MAX_STRING] chars and whole
 * lines capped at [MAX_LINE_BYTES] bytes (threat T-01-02, log injection and log bloat).
 */
object EvidenceLine {
    const val MAX_STRING = 2048
    const val MAX_LINE_BYTES = 8192
    private const val MAX_DEPTH = 6

    fun format(ts: String, mono: Long, kind: String, fields: Map<String, Any?>): String {
        val head = StringBuilder(256)
        head.append("{\"ts\":")
        quote(head, ts)
        head.append(",\"mono\":").append(mono).append(",\"kind\":")
        quote(head, kind)

        val line = StringBuilder(head)
        for ((key, value) in fields) {
            line.append(',')
            quote(line, key)
            line.append(':')
            value(line, value, 0)
        }
        line.append('}')

        val bytes = utf8Length(line)
        if (bytes <= MAX_LINE_BYTES) return line.toString()

        head.append(",\"truncated\":true,\"bytes\":").append(bytes).append('}')
        return head.toString()
    }

    /** Appends a JSON value. Unknown types are written as their capped toString(). */
    private fun value(out: StringBuilder, v: Any?, depth: Int) {
        when (v) {
            null -> out.append("null")
            is Boolean -> out.append(v)
            is Byte, is Short, is Int, is Long -> out.append(v.toString())
            is Double -> if (v.isFinite()) out.append(v) else out.append("null")
            is Float -> if (v.isFinite()) out.append(v) else out.append("null")
            is CharSequence -> quote(out, v)
            is Map<*, *> -> {
                if (depth >= MAX_DEPTH) {
                    quote(out, "…[depth]")
                    return
                }
                out.append('{')
                var first = true
                for ((k, item) in v) {
                    if (!first) out.append(',')
                    first = false
                    quote(out, k.toString())
                    out.append(':')
                    value(out, item, depth + 1)
                }
                out.append('}')
            }
            is Iterable<*> -> array(out, v.iterator(), depth)
            is Array<*> -> array(out, v.iterator(), depth)
            else -> quote(out, v.toString())
        }
    }

    private fun array(out: StringBuilder, it: Iterator<*>, depth: Int) {
        if (depth >= MAX_DEPTH) {
            quote(out, "…[depth]")
            return
        }
        out.append('[')
        var first = true
        while (it.hasNext()) {
            if (!first) out.append(',')
            first = false
            value(out, it.next(), depth + 1)
        }
        out.append(']')
    }

    /** Appends a quoted, escaped, length-capped JSON string. */
    private fun quote(out: StringBuilder, s: CharSequence) {
        var end = s.length
        var dropped = 0
        if (end > MAX_STRING) {
            end = MAX_STRING
            // never cut between the halves of a surrogate pair
            if (Character.isHighSurrogate(s[end - 1])) end--
            dropped = s.length - end
        }
        out.append('"')
        for (i in 0 until end) {
            when (val c = s[i]) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                else ->
                    if (c < ' ' || c == '\u007F' || c == ' ' || c == ' ') {
                        out.append("\\u").append(String.format("%04x", c.code))
                    } else {
                        out.append(c)
                    }
            }
        }
        if (dropped > 0) out.append("…[+").append(dropped).append(']')
        out.append('"')
    }

    private fun utf8Length(s: CharSequence): Int {
        var n = 0
        var i = 0
        while (i < s.length) {
            val c = s[i]
            n += when {
                c.code < 0x80 -> 1
                c.code < 0x800 -> 2
                Character.isHighSurrogate(c) && i + 1 < s.length && Character.isLowSurrogate(s[i + 1]) -> {
                    i++
                    4
                }
                else -> 3
            }
            i++
        }
        return n
    }
}

/**
 * Append-only JSONL evidence file, mirrored to logcat. Never throws to callers.
 * Rotates at [ROTATE_BYTES]: current -> .1 -> .2 (old .2 deleted).
 */
class EvidenceLog(private val dir: File, private val tag: String = "T2U") {
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "t2u-evidence").apply { isDaemon = true } }
    private val tsFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSxxx")
    private val file = File(dir, FILE_NAME)
    private var stream: FileOutputStream? = null
    private var size = 0L

    fun event(kind: String, fields: Map<String, Any?> = emptyMap()) {
        val ts = OffsetDateTime.now().format(tsFormat)
        val mono = SystemClock.elapsedRealtimeNanos()
        val line = try {
            EvidenceLine.format(ts, mono, kind, fields)
        } catch (t: Throwable) {
            Log.w(tag, "evidence format failed for $kind", t)
            return
        }
        Log.i(tag, line.take(4000))
        try {
            executor.execute { append(line) }
        } catch (t: Throwable) {
            Log.w(tag, "evidence executor rejected $kind", t)
        }
    }

    /** Drains queued writes (used before the service stops). */
    fun flush(timeoutMs: Long = 2000) {
        try {
            executor.submit {}.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (t: Throwable) {
            Log.w(tag, "evidence flush failed", t)
        }
    }

    private fun append(line: String) {
        try {
            val bytes = (line + "\n").toByteArray(Charsets.UTF_8)
            var out = stream
            if (out == null) {
                dir.mkdirs()
                out = FileOutputStream(file, true)
                stream = out
                size = file.length()
            }
            if (size + bytes.size > ROTATE_BYTES) {
                out.close()
                stream = null
                rotate()
                dir.mkdirs()
                out = FileOutputStream(file, true)
                stream = out
                size = 0
            }
            out.write(bytes)
            out.flush()
            size += bytes.size
        } catch (t: Throwable) {
            Log.w(tag, "evidence write failed", t)
            try {
                stream?.close()
            } catch (_: Throwable) {
            }
            stream = null
        }
    }

    private fun rotate() {
        val one = File(dir, "$FILE_NAME.1")
        val two = File(dir, "$FILE_NAME.2")
        two.delete()
        if (one.exists()) one.renameTo(two)
        file.renameTo(one)
    }

    companion object {
        const val FILE_NAME = "spike-events.jsonl"
        const val ROTATE_BYTES = 8L * 1024 * 1024
    }
}
