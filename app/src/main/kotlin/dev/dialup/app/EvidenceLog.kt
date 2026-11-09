package dev.dialup.app

import android.os.SystemClock
import android.util.Log
import java.io.File
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

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
 * Append-only JSONL evidence file (`dialup-events.jsonl`), mirrored to logcat. Never throws to callers.
 * The app owns one instance per process, obtained with [shared]; it has one writer thread and one open file handle. [close]
 * ends the thread and releases the handle. Rotation (current -> .1 -> .2, old .2 deleted at [ROTATE_BYTES]) lives in [RotatingFile]; every
 * file operation runs on the single writer thread.
 */
class EvidenceLog(
    val dir: File,
    private val tag: String = "T2U",
    private val monoNanos: () -> Long = { SystemClock.elapsedRealtimeNanos() },
    private val logcat: (String) -> Unit = { Log.i(tag, it) },
) {
    @Volatile
    private var level: LogLevel = LogLevel.INFO

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "t2u-evidence").apply { isDaemon = true } }
    private val tsFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSxxx")
    private val rotating = RotatingFile(dir, FILE_NAME, ROTATE_BYTES, keep = KEEP_ROTATED)
    private val closed = AtomicBoolean(false)

    val isClosed: Boolean get() = closed.get()

    /**
     * Drains queued writes, stops the writer thread and releases the file handle. Idempotent; once closed, [event], [clear] and
     * [flush] return quickly and write nothing.
     */
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            executor.submit {}.get(2000, TimeUnit.MILLISECONDS)
        } catch (t: Throwable) {
            warn("evidence close: drain", t)
        }
        executor.shutdown()
        try {
            if (!executor.awaitTermination(2000, TimeUnit.MILLISECONDS)) executor.shutdownNow()
        } catch (t: Throwable) {
            warn("evidence close: terminate", t)
            executor.shutdownNow()
        }
        try {
            rotating.close()
        } catch (t: Throwable) {
            warn("evidence close: file", t)
        }
    }

    fun setLevel(value: LogLevel) {
        level = value
    }

    fun level(): LogLevel = level

    fun event(kind: String, fields: Map<String, Any?> = emptyMap()) {
        if (closed.get()) return
        // D-08: one filter governs both the file and the logcat mirror; checked before any work
        if (!shouldWrite(level, kind, fields)) return
        val ts = OffsetDateTime.now().format(tsFormat)
        val mono = monoNanos()
        val line = try {
            EvidenceLine.format(ts, mono, kind, fields)
        } catch (t: Throwable) {
            warn("evidence format failed for $kind", t)
            return
        }
        logcat(line.take(4000))
        try {
            executor.execute { append(line) }
        } catch (t: Throwable) {
            warn("evidence executor rejected $kind", t)
        }
    }

    private fun warn(message: String, t: Throwable) {
        runCatching { Log.w(tag, message, t) }
    }

    /** The file that new events are appended to. */
    fun currentFile(): File = rotating.current

    /**
     * Deletes the log files on the writer thread, so a clear never races a write. True once the writer thread finished the delete
     * within 2 s; false (after a warning) on any failure or timeout.
     */
    fun clear(): Boolean {
        if (closed.get()) return false
        return try {
            executor.submit { rotating.clear() }.get(2000, java.util.concurrent.TimeUnit.MILLISECONDS)
            true
        } catch (t: Throwable) {
            warn("evidence clear failed", t)
            false
        }
    }

    /** Drains queued writes (used before the service stops). */
    fun flush(timeoutMs: Long = 2000) {
        if (closed.get()) return
        try {
            executor.submit {}.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (t: Throwable) {
            warn("evidence flush failed", t)
        }
    }

    private fun append(line: String) {
        try {
            rotating.append((line + "\n").toByteArray(Charsets.UTF_8))
        } catch (t: Throwable) {
            warn("evidence write failed", t)
        }
    }

    companion object {
        const val FILE_NAME = "dialup-events.jsonl"
        const val ROTATE_BYTES = 8L * 1024 * 1024

        /** How many rotated files are kept next to the current one. */
        const val KEEP_ROTATED = 2

        private val lock = Any()
        private var instance: EvidenceLog? = null

        /** The live process-wide log, for reporters that have no directory at hand; null when none is open. */
        fun current(): EvidenceLog? = synchronized(lock) { instance?.takeIf { !it.isClosed } }

        /**
         * The process-wide log (CR-01): the same directory returns the existing live instance, a different directory closes the
         * previous one and opens a new one, so a service restart or the Settings tab never starts a second writer.
         */
        fun shared(dir: File, logcat: ((String) -> Unit)? = null, monoNanos: (() -> Long)? = null): EvidenceLog = synchronized(lock) {
            val current = instance
            if (current != null && !current.isClosed && current.dir == dir) return current
            current?.close()
            val created = when {
                logcat == null && monoNanos == null -> EvidenceLog(dir)
                logcat == null -> EvidenceLog(dir, monoNanos = monoNanos!!)
                monoNanos == null -> EvidenceLog(dir, logcat = logcat)
                else -> EvidenceLog(dir, monoNanos = monoNanos, logcat = logcat)
            }
            instance = created
            created
        }
    }
}
