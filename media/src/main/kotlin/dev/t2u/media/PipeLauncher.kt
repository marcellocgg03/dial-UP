package dev.t2u.media

import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Starts the ffmpeg process for a pipe or buffered entry. The JVM side ([JvmPipeLauncher]) uses a binary on
 * PATH-like absolute location; the Android side (`FfmpegRunner` in :app) adds the library environment.
 * [args] never contain the binary name and are passed as a list, never through a shell.
 */
fun interface PipeLauncher {
    fun start(args: List<String>): Process

    /** The last bytes ffmpeg wrote to stderr for [process], or empty when the launcher does not capture it. */
    fun stderrTail(process: Process): String = ""
}

/** One finished ffmpeg run, for the exit log. [args] are exactly what was passed to [JvmPipeLauncher.start]. */
data class ProcessExit(val args: List<String>, val exitCode: Int, val millis: Long, val stderrTail: String)

/**
 * [PipeLauncher] over [ProcessBuilder]. Runs [binary] with [args] as a list (no shell), closes stdin at once,
 * drains stderr on a daemon thread into a 4 KiB tail, tracks live processes for [liveCount] and reports each
 * exit through [onExit]. [environment] is added to the child's environment (the Android runner uses it for
 * LD_LIBRARY_PATH and SSL_CERT_FILE).
 */
class JvmPipeLauncher(
    private val binary: String,
    private val environment: Map<String, String> = emptyMap(),
    private val onExit: ((ProcessExit) -> Unit)? = null,
) : PipeLauncher {
    private class Watched(val thread: Thread, val tail: StringBuilder)

    private val live: MutableSet<Process> = ConcurrentHashMap.newKeySet()
    private val watched = object : LinkedHashMap<Process, Watched>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Process, Watched>): Boolean = size > MAX_WATCHED
    }

    override fun start(args: List<String>): Process {
        val pb = ProcessBuilder(listOf(binary) + args)
        pb.environment().putAll(environment)
        val started = System.nanoTime()
        val process = pb.start()
        runCatching { process.outputStream.close() }
        live.add(process)
        val tail = StringBuilder()
        val thread = Thread({
            try {
                val buf = ByteArray(1024)
                process.errorStream.use { input ->
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        synchronized(tail) {
                            tail.append(String(buf, 0, n, Charsets.UTF_8))
                            if (tail.length > TAIL_BYTES) tail.delete(0, tail.length - TAIL_BYTES)
                        }
                    }
                }
            } catch (_: IOException) {
                // stream closed by destroy
            }
            val code = try {
                process.waitFor()
            } catch (_: InterruptedException) {
                -1
            }
            live.remove(process)
            val text = synchronized(tail) { tail.toString() }
            runCatching { onExit?.invoke(ProcessExit(args, code, (System.nanoTime() - started) / 1_000_000, text.trim())) }
        }, "ffmpeg-stderr").also {
            it.isDaemon = true
            it.start()
        }
        synchronized(watched) { watched[process] = Watched(thread, tail) }
        return process
    }

    override fun stderrTail(process: Process): String {
        val w = synchronized(watched) { watched.remove(process) } ?: return ""
        w.thread.join(1000)
        return synchronized(w.tail) { w.tail.toString() }.trim()
    }

    /** Processes started by this launcher that have not exited yet. */
    fun liveCount(): Int = live.count { it.isAlive }

    private companion object {
        const val TAIL_BYTES = 4096
        const val MAX_WATCHED = 64
    }
}
