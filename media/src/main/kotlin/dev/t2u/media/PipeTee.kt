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
import java.io.InputStream
import java.util.concurrent.TimeUnit

private const val PIPE_CHUNK = 16 * 1024
private const val FIRST_BYTE_TIMEOUT_MS = 20_000L

/** Kills [proc] and waits up to 2 s for it to be gone (Node `res.on('close', kill)`). */
internal suspend fun killAndWait(proc: Process) {
    withContext(NonCancellable + Dispatchers.IO) {
        proc.destroyForcibly()
        runCatching { proc.waitFor(2, TimeUnit.SECONDS) }
    }
}

/**
 * One attempt's process for a live GET: starts it, waits for the first byte under a watchdog, then pumps stdout to
 * the speaker while teeing the same bytes into the range cache. The process never outlives the call.
 */
internal class PipeTee(private val launcher: PipeLauncher) {
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
    ): String? {
        val proc = withContext(Dispatchers.IO) { launcher.start(args) }
        try {
            val stdout = proc.inputStream
            val buf = ByteArray(PIPE_CHUNK)
            // Wait for the first audio byte; headers go out only after it, so a dead start is a 502, not an empty 200.
            val (firstLen, watchdogNote) = awaitFirstByte(proc, stdout, buf)
            if (firstLen <= 0) return startFailure(proc, watchdogNote)
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
                        while (true) {
                            // file writes stay on the IO dispatcher, never on the Ktor event loop
                            val n = withContext(Dispatchers.IO) {
                                val k = stdout.read(buf)
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
