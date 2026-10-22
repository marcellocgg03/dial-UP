package dev.t2u.renderer

// A software Sonos speaker for the G4 end-to-end test (quick task 261021-c6t), GPL-3.0-only.
//
// Built on the shared upnp FakeRenderer (its SOAP side, device description and state) plus one raw-socket HTTP
// client that behaves the way the SYMFONISK Lamp did in the device logs
// (.planning/phases/02-first-cast-plays-on-a-speaker/evidence/g4-planning/early-pause-events.jsonl and
// evidence/session-b/lamp-session-events.jsonl):
//
//  * SetAVTransportURI + Play: one plain GET of the token URL (no Range); the whole body is consumed at once
//    (the Lamp held the full 3-4.7 MB track within about a second) and the speaker reports PLAYING with the first
//    body byte.
//  * Pause: the mimic records the byte offset it has played (taken from [offsets], the values seen on the device:
//    7227, 23888, 29981, 41357, 18630, never more than it received). A Pause that comes early in the session
//    (within [earlyWindowMs] of the first GET) also drops the connection, the Lamp's "early Pause" reaction; a late
//    Pause keeps the buffer and nothing else happens (no later Pause re-requested in the logs).
//  * The next Play after a dropped connection: a NEW GET with `Range: bytes=<offset>-`. A 206 whose Content-Range
//    starts at the offset is accepted and consumed (PLAYING). A 200 answer to that Range request is the logged
//    failure: the mimic hangs up after [rejectAfterMs] and never reports PLAYING for it.
//
// Plan 03-15 adds a slow native Seek ([SonosMimic.slowSeek], device session C, Lamp, 2026-10-21): after an accepted
// Seek the speaker reports TRANSITIONING with a RelTime of about 1 s for several seconds, then PLAYING (or PAUSED when
// it was paused) at the target. It runs on a caller-supplied clock so a test moves time by hand, and works without
// the HTTP side (`fetchStream = false`) for tests that use the plain RendererHarness, whose URLs nobody serves.
//
// Plan 03-16 adds the lag the first version missed ([SonosMimic.laggingSeek], Lamp, 2026-10-21 20:59 poll from the
// laptop): for about 0.7 s after an accepted Seek the speaker keeps reporting its OLD state (PLAYING with the old
// RelTime), and only then goes TRANSITIONING at the target (RelTime already the target) before it plays on.
//
// Plan 03-17 adds a speaker that is slow to start ([SonosMimic.holdStartWhile], Lamp, 2026-10-21 22:01): after Play it
// stays TRANSITIONING and does not reach PLAYING by itself; only an accepted Seek (see slowSeek / laggingSeek) ends it, by
// way of that Seek's own TRANSITIONING time and then PLAYING at the target.
//
// Everything runs on 127.0.0.1. Time is the only non-deterministic input, so [earlyWindowMs] is a test parameter
// with a wide margin on both sides of the Pauses the tests make.

import dev.t2u.upnp.FakeRenderer
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList

/** One GET the mimic sent. [range] is the Range header it sent, or null for a plain GET. */
class MimicRequest(val range: String?) {
    @Volatile var status: Int = 0

    @Volatile var contentRange: String? = null

    /** Body bytes read so far. */
    @Volatile var bytes: Long = 0

    /** The whole body was read to its end. */
    @Volatile var complete: Boolean = false

    /** The mimic closed the connection before the body ended (an early Pause, or a refused 200). */
    @Volatile var hungUp: Boolean = false

    /** What was read; final once [done]. */
    @Volatile var body: ByteArray = ByteArray(0)

    @Volatile var done: Boolean = false

    override fun toString(): String = "GET range=$range status=$status contentRange=$contentRange bytes=$bytes complete=$complete hungUp=$hungUp"
}

class SonosMimic(
    private val fake: FakeRenderer,
    private val offsets: List<Long> = DEVICE_OFFSETS,
    private val earlyWindowMs: Long = 2000,
    private val rejectAfterMs: Long = 300,
    private val bytesPerSecond: Long = 16_000,
    private val fetchStream: Boolean = true,
) : AutoCloseable {
    val requests = CopyOnWriteArrayList<MimicRequest>()

    /** The offset recorded at each Pause, in order. */
    val pauseOffsets = CopyOnWriteArrayList<Long>()

    /** How many times the mimic went to PLAYING (a body byte arrived, or a late Play kept the buffer). */
    @Volatile var playingReports: Int = 0
        private set

    private val lock = Any()
    private var connSeq = 0
    private var socket: Socket? = null
    private var uri: URI? = null
    private var firstGetAtNanos = 0L
    private var pendingOffset: Long? = null
    private var pauses = 0

    @Volatile private var firstReq: MimicRequest? = null

    init {
        fake.onAction = ::onAction
    }

    /** The complete body of the first plain GET, or null while it is unfinished or was cut off. */
    val firstBody: ByteArray? get() = firstReq?.takeIf { it.complete }?.body

    /** Milliseconds since the first GET of this session started (0 before it). */
    fun ageMs(): Long = synchronized(lock) { if (firstGetAtNanos == 0L) 0 else (System.nanoTime() - firstGetAtNanos) / 1_000_000 }

    fun awaitPlaying(timeoutMs: Long = 10_000) = awaitCondition("the speaker to report PLAYING", timeoutMs) { fake.state.transport == "PLAYING" }

    fun awaitFirstBodyComplete(timeoutMs: Long = 10_000) = awaitCondition("the first GET to be consumed", timeoutMs) { firstBody != null }

    /** Waits until [n] requests were sent and the latest one has ended (complete, refused or cut). */
    fun awaitRequestDone(n: Int, timeoutMs: Long = 10_000) =
        awaitCondition("request #$n to end: $requests", timeoutMs) { requests.size >= n && requests[n - 1].done }

    override fun close() {
        fake.onAction = null
        synchronized(lock) { dropConnection() }
    }

    // ------------------------------------------------------------------------------------------ SOAP side

    private fun onAction(action: String) {
        steadyAction(action)
        slowSeekAction(action)
        if (!fetchStream) return
        when (action) {
            "SetAVTransportURI" -> synchronized(lock) {
                reset()
                uri = URI(fake.state.currentUri)
                fake.state.relTime = hms(0)
            }
            "Stop" -> synchronized(lock) { reset() }
            "Play" -> onPlay()
            "Pause" -> onPause()
        }
    }

    private fun reset() {
        dropConnection()
        pendingOffset = null
        firstGetAtNanos = 0L
        firstReq = null
        fake.state.transport = "STOPPED"
    }

    private fun onPlay() = synchronized(lock) {
        val u = uri ?: return@synchronized
        val offset = pendingOffset
        when {
            firstGetAtNanos == 0L -> {
                firstGetAtNanos = System.nanoTime()
                fake.state.transport = "TRANSITIONING"
                startFetch(u, null)
            }
            offset != null -> {
                pendingOffset = null
                fake.state.transport = "TRANSITIONING"
                startFetch(u, offset)
            }
            else -> {
                // The buffer is still held (a late Pause): the Lamp just carries on, no request.
                fake.state.transport = "PLAYING"
                playingReports++
            }
        }
    }

    private fun onPause() = synchronized(lock) {
        if (firstGetAtNanos == 0L || pendingOffset != null) return@synchronized
        val first = firstReq
        val total = if (first != null && first.complete) first.body.size.toLong() else first?.bytes ?: 0L
        val offset = minOf(offsets[pauses % offsets.size], maxOf(total - 1, 0))
        pauses++
        pauseOffsets.add(offset)
        fake.state.relTime = hms(offset / bytesPerSecond)
        val early = (System.nanoTime() - firstGetAtNanos) / 1_000_000 < earlyWindowMs
        if (early) {
            dropConnection()
            pendingOffset = offset
        }
    }

    // ------------------------------------------------------------------------------------------ slow seek

    /**
     * One accepted Seek. For [lagMs] the speaker still reports its old state ([preTransport], [staleRelTime]); then it is
     * TRANSITIONING for [transitionMs] ([midRelTime], or the target when null); then in [after] at [target] seconds.
     */
    private class SeekState(
        val target: Long,
        val at: Long,
        val lagMs: Long,
        val transitionMs: Long,
        val after: String,
        val preTransport: String,
        val staleRelTime: String?,
    )

    private var slowTransitionMs: Long? = null
    private var slowLagMs = 0L
    private var slowStaleRelTime: String? = null
    private var slowMidRelTime: String? = "0:00:01"
    private var slowEndAtS: Long? = null
    private var slowClock: () -> Long = System::currentTimeMillis
    private var seek: SeekState? = null
    private var holdStart: (() -> Boolean)? = null

    /**
     * A speaker that is slow to start (plan 03-17): while [cond] holds, a Play that no Seek has followed leaves the speaker
     * TRANSITIONING at RelTime 0:00:00 and it never reaches PLAYING by itself. Where [cond] does not hold the fake's own
     * Play applies (PLAYING at once). Needs [slowSeek] or [laggingSeek] to have been called; a Seek then takes the
     * speaker through that Seek's TRANSITIONING time to PLAYING at the target.
     */
    fun holdStartWhile(cond: () -> Boolean = { true }) {
        synchronized(lock) { holdStart = cond }
    }

    /**
     * From now on a native Seek keeps the speaker TRANSITIONING with RelTime [midSeekRelTime] for [transitionMs] of
     * [clock] time, then PLAYING at the target and advancing one second per second (or PAUSED_PLAYBACK at the target
     * when it was paused). A track of [endAtSeconds] seconds goes STOPPED at its end. [transitionMs] very large means
     * the speaker never settles. Call [refresh] after moving the clock: the fake answers from its state.
     */
    fun slowSeek(transitionMs: Long, midSeekRelTime: String = "0:00:01", endAtSeconds: Long? = null, clock: () -> Long) {
        synchronized(lock) {
            slowTransitionMs = transitionMs
            slowLagMs = 0
            slowStaleRelTime = null
            slowMidRelTime = midSeekRelTime
            slowEndAtS = endAtSeconds
            slowClock = clock
        }
    }

    /**
     * The Lamp as polled from the laptop on 2026-10-21 (plan 03-16): for [lagMs] after an accepted Seek the speaker
     * keeps reporting what it reported before (PLAYING, RelTime [staleRelTime]); then TRANSITIONING with RelTime = the
     * target for [transitionMs]; then PLAYING advancing from the target. [transitionMs] = 0 is a fast speaker that
     * never shows TRANSITIONING. Virtual [clock] only.
     */
    fun laggingSeek(lagMs: Long = 700, transitionMs: Long = 5000, staleRelTime: String = "0:00:01", endAtSeconds: Long? = null, clock: () -> Long) {
        synchronized(lock) {
            slowTransitionMs = transitionMs
            slowLagMs = lagMs
            slowStaleRelTime = staleRelTime
            slowMidRelTime = null
            slowEndAtS = endAtSeconds
            slowClock = clock
        }
    }

    /** Brings the fake's transport state and RelTime up to the slow-seek clock. */
    fun refresh() = synchronized(lock) {
        applySteady()
        applySlowSeek()
    }

    // ------------------------------------------------------------------------------------------ steady play (plan 03-18)

    private var steadyClock: (() -> Long)? = null
    private var steadyPos = 0.0
    private var steadyAt = 0L
    private var skewS = 0L
    private var skewOnce = ArrayDeque<Long>()
    private var skewOnceActive = false
    private var jitterS: List<Long> = emptyList()
    private var jitterIdx = 0

    /** How many polls read a RelTime that [misreadNextPoll] or [jitter] moved. */
    @Volatile var skewedReads: Int = 0
        private set

    /**
     * A speaker that plays on by itself (plan 03-18): while it is PLAYING its RelTime advances one second per second of
     * [clock] from [startAtSeconds] (a new track starts at 0), and it holds while paused or stopped. [refresh] brings the
     * fake up to the clock. A native Seek hands the RelTime over to [slowSeek] or [laggingSeek]; [jumpTo] takes it back.
     */
    fun followClock(clock: () -> Long, startAtSeconds: Long = 0) {
        synchronized(lock) {
            steadyClock = clock
            steadyPos = startAtSeconds.toDouble()
            steadyAt = clock()
            writeSteady()
        }
    }

    /** A seek or scrub done in the Sonos app: the speaker's position jumps to [seconds] and nothing is sent by us. */
    fun jumpTo(seconds: Double) {
        synchronized(lock) {
            val clock = steadyClock ?: error("followClock first")
            seek = null
            steadyPos = seconds
            steadyAt = clock()
            writeSteady()
        }
    }

    /** The next poll reads a RelTime [offsetSeconds] ahead of where the speaker is; the polls after it read it right. */
    fun misreadNextPoll(offsetSeconds: Long) {
        synchronized(lock) { skewOnce.addLast(offsetSeconds) }
    }

    /** A speaker with a jittery RelTime: every poll reads the next of [offsetsSeconds] (cycling) ahead of where it is. */
    fun jitter(offsetsSeconds: List<Long>) {
        synchronized(lock) { jitterS = offsetsSeconds }
    }

    private fun applySteady() {
        val clock = steadyClock ?: return
        val now = clock()
        if (seek == null && fake.state.transport == "PLAYING") steadyPos += (now - steadyAt) / 1000.0
        steadyAt = now
        writeSteady()
    }

    private fun writeSteady() {
        if (steadyClock == null || seek != null) return
        fake.state.relTime = hms(Math.floor(steadyPos).toLong() + skewS)
    }

    private fun steadyAction(action: String) = synchronized(lock) {
        if (steadyClock == null) return@synchronized
        when (action) {
            "SetAVTransportURI" -> {
                seek = null
                steadyPos = 0.0
                steadyAt = steadyClock!!()
                writeSteady()
            }
            // The poll asks for the transport state first and then the position: skew what it reads next.
            "GetTransportInfo" -> {
                val once = skewOnce.removeFirstOrNull()
                skewOnceActive = once != null
                skewS = once ?: if (jitterS.isNotEmpty()) jitterS[jitterIdx++ % jitterS.size] else 0L
                writeSteady()
            }
            "GetPositionInfo" -> if (skewOnceActive) {
                skewOnceActive = false
                skewedReads++
                skewS = 0
                writeSteady()
            } else if (skewS != 0L) {
                skewedReads++
            }
        }
    }

    private fun slowSeekAction(action: String) = synchronized(lock) {
        val transition = slowTransitionMs ?: return@synchronized
        when (action) {
            "SetAVTransportURI" -> {
                seek = null
                fake.state.relTime = hms(0)
            }
            "Stop" -> seek = null
            "Play" -> if (seek == null && holdStart?.invoke() == true) {
                fake.state.transport = "TRANSITIONING"
                fake.state.relTime = hms(0)
            } else {
                rebase("PLAYING")
            }
            "Seek" -> {
                val body = fake.calls.last { it.action == "Seek" }.body
                val t = Regex("<Target>(\\d+):(\\d+):(\\d+)</Target>").find(body)?.groupValues?.drop(1)?.map { it.toLong() } ?: return@synchronized
                val before = fake.state.transport
                val after = if (before == "PAUSED_PLAYBACK") "PAUSED_PLAYBACK" else "PLAYING"
                seek = SeekState(t[0] * 3600 + t[1] * 60 + t[2], slowClock(), slowLagMs, transition, after, before, slowStaleRelTime)
                applySlowSeek()
            }
            "Pause" -> rebase("PAUSED_PLAYBACK")
        }
    }

    /** A Play or Pause once the seek settled: carry on from where the speaker is, in the new state. */
    private fun rebase(after: String) {
        val s = seek ?: return
        val elapsed = slowClock() - s.at
        if (elapsed < s.lagMs + s.transitionMs) {
            seek = SeekState(s.target, s.at, s.lagMs, s.transitionMs, after, s.preTransport, s.staleRelTime)
        } else {
            val now = if (s.after == "PLAYING") s.target + (elapsed - s.lagMs - s.transitionMs) / 1000 else s.target
            seek = SeekState(now, slowClock(), 0, 0, after, s.preTransport, null)
        }
        applySlowSeek()
    }

    private fun applySlowSeek() {
        val s = seek ?: return
        val elapsed = slowClock() - s.at
        if (elapsed < s.lagMs) {
            // The Seek was accepted but the speaker has not noticed yet: still the old state and the old position.
            fake.state.transport = s.preTransport
            s.staleRelTime?.let { fake.state.relTime = it }
            return
        }
        val sinceLag = elapsed - s.lagMs
        if (sinceLag < s.transitionMs) {
            fake.state.transport = "TRANSITIONING"
            fake.state.relTime = slowMidRelTime ?: hms(s.target)
            return
        }
        val position = if (s.after == "PLAYING") s.target + (sinceLag - s.transitionMs) / 1000 else s.target
        val end = slowEndAtS
        if (end != null && position >= end) {
            fake.state.transport = "STOPPED"
            fake.state.relTime = hms(end)
        } else {
            fake.state.transport = s.after
            fake.state.relTime = hms(position)
        }
    }

    // ------------------------------------------------------------------------------------------ HTTP side

    private fun dropConnection() {
        connSeq++ // a thread of an older connection no longer owns the transport state
        runCatching { socket?.close() }
        socket = null
    }

    private fun startFetch(u: URI, offset: Long?) {
        val id = ++connSeq
        val req = MimicRequest(offset?.let { "bytes=$it-" })
        if (offset == null) firstReq = req
        requests.add(req)
        val sock = Socket()
        socket = sock
        Thread({ fetch(id, sock, u, offset, req) }, "sonos-mimic-$id").also {
            it.isDaemon = true
            it.start()
        }
    }

    private fun owns(id: Int): Boolean = synchronized(lock) { id == connSeq }

    private fun setPlaying(id: Int) {
        synchronized(lock) {
            if (id != connSeq) return
            fake.state.transport = "PLAYING"
            playingReports++
        }
    }

    private fun fetch(id: Int, sock: Socket, u: URI, offset: Long?, req: MimicRequest) {
        val sink = ByteArrayOutputStream()
        try {
            sock.connect(InetSocketAddress(u.host, u.port), 3000)
            sock.soTimeout = 10_000
            val request = buildString {
                append("GET ${u.rawPath} HTTP/1.1\r\nHost: ${u.host}:${u.port}\r\n")
                append("User-Agent: Linux UPnP/1.0 Sonos/86.10-81100 (ZPS20)\r\n")
                if (offset != null) append("Range: bytes=$offset-\r\n")
                append("Connection: close\r\n\r\n")
            }
            sock.getOutputStream().apply { write(request.toByteArray(Charsets.ISO_8859_1)); flush() }
            val input = BufferedInputStream(sock.getInputStream())
            val statusLine = readLine(input) ?: throw IOException("no status line")
            req.status = statusLine.split(' ')[1].toInt()
            val headers = HashMap<String, String>()
            while (true) {
                val line = readLine(input) ?: throw IOException("headers cut")
                if (line.isEmpty()) break
                headers[line.substringBefore(':').trim().lowercase()] = line.substringAfter(':').trim()
            }
            req.contentRange = headers["content-range"]

            val acceptable = if (offset == null) {
                req.status == 200
            } else {
                // a 206 that starts exactly where the speaker asked; anything else is "the stream restarted"
                val start = req.contentRange?.let { Regex("bytes (\\d+)-").find(it)?.groupValues?.get(1)?.toLongOrNull() }
                req.status == 206 && start == offset
            }
            if (!acceptable) {
                // The logged failure: a 200 from byte 0 to a Range request. Read a little, then hang up, never PLAYING.
                val deadline = System.nanoTime() + rejectAfterMs * 1_000_000
                val buf = ByteArray(8192)
                sock.soTimeout = 100
                while (System.nanoTime() < deadline) {
                    val n = try { input.read(buf) } catch (_: java.net.SocketTimeoutException) { 0 }
                    if (n < 0) break
                    if (n > 0) { sink.write(buf, 0, n); req.bytes += n }
                }
                req.hungUp = true
                if (owns(id)) synchronized(lock) { fake.state.transport = "STOPPED" }
                return
            }

            var announced = false
            val onBytes: (ByteArray, Int) -> Unit = { b, n ->
                sink.write(b, 0, n)
                req.bytes += n
                if (!announced) {
                    announced = true
                    setPlaying(id)
                }
            }
            val chunked = headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true
            if (chunked) readChunked(input, onBytes) else readFixed(input, headers["content-length"]?.toLongOrNull() ?: 0L, onBytes)
            req.complete = true
        } catch (_: IOException) {
            // closed by dropConnection (an early Pause) or by the server
            if (!req.complete) req.hungUp = req.hungUp || !owns(id)
        } finally {
            req.body = sink.toByteArray()
            runCatching { sock.close() }
            req.done = true
        }
    }

    private fun readFixed(input: InputStream, length: Long, onBytes: (ByteArray, Int) -> Unit) {
        val buf = ByteArray(16 * 1024)
        var left = length
        while (left > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) throw IOException("body cut at $left left")
            onBytes(buf, n)
            left -= n
        }
    }

    private fun readChunked(input: InputStream, onBytes: (ByteArray, Int) -> Unit) {
        val buf = ByteArray(16 * 1024)
        while (true) {
            val size = readLine(input)?.substringBefore(';')?.trim()?.toIntOrNull(16) ?: throw IOException("bad chunk size")
            if (size == 0) {
                while (true) if (readLine(input).isNullOrEmpty()) break // trailers up to the blank line
                return
            }
            var left = size
            while (left > 0) {
                val n = input.read(buf, 0, minOf(buf.size, left))
                if (n < 0) throw IOException("chunk cut")
                onBytes(buf, n)
                left -= n
            }
            readLine(input) // CRLF after the chunk
        }
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
        }
    }

    companion object {
        /** Offsets the Lamp resumed with in the device log (evidence/g4-planning). */
        val DEVICE_OFFSETS: List<Long> = listOf(7227L, 23888L, 29981L, 41357L, 18630L)

        fun hms(totalSeconds: Long): String = "%d:%02d:%02d".format(totalSeconds / 3600, totalSeconds / 60 % 60, totalSeconds % 60)
    }
}

/** Polls [cond] every 10 ms until it holds; fails with [what] after [timeoutMs]. */
fun awaitCondition(what: String, timeoutMs: Long = 10_000, cond: () -> Boolean) {
    val deadline = System.nanoTime() + timeoutMs * 1_000_000
    while (!cond()) {
        if (System.nanoTime() > deadline) throw AssertionError("timed out after $timeoutMs ms waiting for $what")
        Thread.sleep(10)
    }
}
