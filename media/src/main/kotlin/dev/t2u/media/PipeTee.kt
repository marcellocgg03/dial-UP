package dev.t2u.media

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondBytesWriter
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

private const val PIPE_CHUNK = 16 * 1024
private const val FIRST_BYTE_TIMEOUT_MS = 20_000L

/** How long one read of ffmpeg's output may wait for bytes after the first one before the stream counts as stalled. */
internal const val STALL_TIMEOUT_MS = 60_000L
private const val STALL_CHECK_MS = 5_000L

/** Kills [proc] and waits up to 2 s for it to be gone (Node `res.on('close', kill)`). */
internal suspend fun killAndWait(proc: Process) {
    withContext(NonCancellable + Dispatchers.IO) {
        proc.destroyForcibly()
        runCatching { proc.waitFor(2, TimeUnit.SECONDS) }
    }
}

/**
 * Starts [args] and hands the process to the caller, or kills it when the caller is cancelled on the way. `withContext`
 * discards its result when the caller is cancelled as it resumes (even if the block itself cannot be cancelled), so a
 * process returned from it would have no owner. The block therefore leaves the process in a holder, and a cancellation that
 * arrives with the process already started kills it before it continues.
 */
internal suspend fun PipeLauncher.startOwned(args: List<String>): Process {
    val holder = AtomicReference<Process?>()
    try {
        withContext(Dispatchers.IO) { holder.set(start(args)) }
    } catch (e: CancellationException) {
        holder.get()?.let { killAndWait(it) }
        throw e
    }
    return checkNotNull(holder.get()) { "the launcher returned no process" }
}

/**
 * The failure detail of an attempt whose ffmpeg could not be started. Fixed text and the exception's class only: the
 * message of the IOException ProcessBuilder throws holds the binary's path (PARITY adaptation 77), and the text must not
 * match [isUpstreamRefusal], so a launch failure never ends the cast as a refusal.
 */
internal fun ffmpegStartFailure(e: IOException): String = "ffmpeg could not be started (${e.javaClass.simpleName})"

/**
 * One attempt's process for a live GET: starts it, waits for the first byte under a watchdog, then pumps stdout to
 * the speaker while teeing the same bytes into the range cache. The process never outlives the call.
 */
internal class PipeTee(
    private val launcher: PipeLauncher,
    private val stallTimeoutMs: Long = STALL_TIMEOUT_MS,
    private val stallCheckMs: Long = STALL_CHECK_MS,
) {
    /**
     * Runs [args] and answers [call] from its stdout. Returns null when the answer was written (the call is done,
     * whether the speaker stayed to the end or hung up), else the failure detail of this attempt with nothing sent yet.
     */
    suspend fun serve(
        call: ApplicationCall,
        entry: StreamEntry,
        out: Outcome,
        cache: PipeRangeCache?,
        token: String,
        args: List<String>,
        onFirstByte: () -> Unit = {},
    ): String? {
        val proc = launcher.startOwned(args)
        try {
            val stdout = proc.inputStream
            val buf = ByteArray(PIPE_CHUNK)
            // Wait for the first audio byte; headers go out only after it, so a dead start is a 502, not an empty 200.
            val (firstLen, watchdogNote) = awaitFirstByte(proc, stdout, buf)
            if (firstLen <= 0) return startFailure(proc, watchdogNote)
            runCatching { onFirstByte() }
            val first = buf.copyOf(firstLen)
            // The tee copies exactly what the client is sent, so a complete run is the byte total a later Range can use.
            var tee: TeeFill? = cache?.openTee(token)
            if (tee?.write(first, 0, first.size) == false) tee = null
            dlnaHeaders(call, entry)
            out.status = 200
            try {
                call.respondBytesWriter(contentType = ContentType.parse(entry.mime), status = HttpStatusCode.OK) {
                    try {
                        writeFully(first, 0, first.size)
                        out.bytes += first.size
                        flush()
                        // Nanotime at which the read now in progress began, 0 while none is. Time the speaker takes to
                        // accept bytes (a pause) is not counted: only a read that waits on ffmpeg is.
                        val readSince = AtomicLong(0)
                        coroutineScope {
                            val watchdog = launch {
                                while (true) {
                                    delay(stallCheckMs)
                                    val since = readSince.get()
                                    if (since != 0L && System.nanoTime() - since > TimeUnit.MILLISECONDS.toNanos(stallTimeoutMs)) {
                                        out.note = "ffmpeg stalled for $stallTimeoutMs ms"
                                        proc.destroyForcibly() // the read below ends, the answer closes, the speaker sees the end
                                        return@launch
                                    }
                                }
                            }
                        while (true) {
                            // file writes stay on the IO dispatcher, never on the Ktor event loop
                            val n = withContext(Dispatchers.IO) {
                                readSince.set(System.nanoTime())
                                val k = try {
                                    stdout.read(buf)
                                } finally {
                                    readSince.set(0)
                                }
                                val t = tee
                                if (t != null) {
                                    if (k > 0) {
                                        if (!t.write(buf, 0, k)) tee = null
                                    } else if (k < 0) {
                                        // End of stream: register the complete run BEFORE the body ends, so a Range
                                        // that follows the last byte already finds it. Only a clean exit counts.
                                        val clean = runCatching { proc.waitFor(2, TimeUnit.SECONDS) && proc.exitValue() == 0 }
                                            .getOrDefault(false)
                                        if (clean) t.complete() else t.abort()
                                        tee = null
                                    }
                                }
                                k
                            }
                            if (n < 0) break
                            writeFully(buf, 0, n)
                            out.bytes += n
                            flush()
                        }
                            watchdog.cancel()
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        // a write failure is the client hanging up; the finally below kills ffmpeg
                        out.note = "client closed: ${e.javaClass.simpleName}"
                    } finally {
                        tee?.abort()
                        killAndWait(proc)
                    }
                }
            } finally {
                tee?.abort()
            }
            return null
        } finally {
            killAndWait(proc)
        }
    }

    /** The first read of [stdout] into [buf] (its length, 0 or less when nothing came) and the watchdog's note if it fired. */
    private suspend fun awaitFirstByte(proc: Process, stdout: InputStream, buf: ByteArray): Pair<Int, String?> {
        var watchdogNote: String? = null
        val firstLen = coroutineScope {
            val watchdog = launch {
                delay(FIRST_BYTE_TIMEOUT_MS)
                watchdogNote = "no first byte after ${FIRST_BYTE_TIMEOUT_MS / 1000} s"
                proc.destroyForcibly()
            }
            try {
                withContext(Dispatchers.IO) { stdout.read(buf) }
            } finally {
                watchdog.cancel()
            }
        }
        return firstLen to watchdogNote
    }

    private suspend fun startFailure(proc: Process, watchdogNote: String?): String {
        withContext(Dispatchers.IO) { runCatching { proc.waitFor(2, TimeUnit.SECONDS) } }
        val err = withContext(Dispatchers.IO) { launcher.stderrTail(proc) }
        return listOfNotNull(watchdogNote, err.takeIf { it.isNotEmpty() }).joinToString(" | ")
            .ifEmpty { "ffmpeg produced no output" }
    }
}
